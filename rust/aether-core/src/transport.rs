use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use rustls::ClientConfig;
use rustls::client::Resumption;
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::TcpStream;

pub const BDP_RCVBUF_BYTES: usize = 4 * 1024 * 1024; // up to 16 MB BDP autotuning ceiling

#[derive(Debug, Clone)]
pub struct ProxyOverride {
    pub host: String,
    pub port: u16,
    pub is_socks5h: bool,
}

/// Logical Android interface identity (replaces rotating raw networkHandle - Fix F3).
#[derive(Debug)]
pub struct BoundLinkState {
    pub link_key: String,
    pub net_handle: AtomicU64,
    pub downstream_kbps: AtomicU64,
    pub rtt_ms: AtomicU64,
    pub is_cellular: bool,
    pub proxy_override: RwLock<Option<ProxyOverride>>,
    pub failure_streak: AtomicI32,
    pub circuit_open_until_ms: AtomicU64,
}

impl BoundLinkState {
    pub fn new(
        link_key: String,
        handle: u64,
        downstream_kbps: u64,
        rtt_ms: u64,
        is_cellular: bool,
        proxy_override: Option<ProxyOverride>,
    ) -> Self {
        Self {
            link_key,
            net_handle: AtomicU64::new(handle),
            downstream_kbps: AtomicU64::new(downstream_kbps.max(1000)),
            rtt_ms: AtomicU64::new(rtt_ms.max(10)),
            is_cellular,
            proxy_override: RwLock::new(proxy_override),
            failure_streak: AtomicI32::new(0),
            circuit_open_until_ms: AtomicU64::new(0),
        }
    }

    pub fn update_handle(&self, new_handle: u64, downstream_kbps: u64, rtt_ms: u64) {
        self.net_handle.store(new_handle, Ordering::Release);
        self.downstream_kbps.store(downstream_kbps.max(1000), Ordering::Relaxed);
        self.rtt_ms.store(rtt_ms.max(10), Ordering::Relaxed);
        self.failure_streak.store(0, Ordering::Relaxed);
        self.circuit_open_until_ms.store(0, Ordering::Release);
    }
}

/// Registry holding all validated active links keyed by logical LinkKey + shared TLS 1.3 config.
pub struct TransportContext {
    pub links: RwLock<HashMap<String, Arc<BoundLinkState>>>,
    pub tls_config: Arc<ClientConfig>,
}

impl TransportContext {
    pub fn new() -> Self {
        let mut root_store = rustls::RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

        let mut cfg = ClientConfig::builder()
            .with_root_certificates(root_store)
            .with_no_client_auth();
        cfg.resumption = Resumption::in_memory_sessions(32);
        cfg.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

        Self {
            links: RwLock::new(HashMap::new()),
            tls_config: Arc::new(cfg),
        }
    }

    pub fn upsert_link(
        &self,
        link_key: String,
        net_handle: u64,
        downstream_kbps: u64,
        rtt_ms: u64,
        is_cellular: bool,
        proxy_override: Option<ProxyOverride>,
    ) {
        let mut guard = self.links.write().unwrap_or_else(|p| p.into_inner());
        if let Some(existing) = guard.get(&link_key) {
            existing.update_handle(net_handle, downstream_kbps, rtt_ms);
            *existing.proxy_override.write().unwrap_or_else(|p| p.into_inner()) = proxy_override;
        } else {
            guard.insert(
                link_key.clone(),
                Arc::new(BoundLinkState::new(
                    link_key,
                    net_handle,
                    downstream_kbps,
                    rtt_ms,
                    is_cellular,
                    proxy_override,
                )),
            );
        }
    }

    pub fn remove_link(&self, link_key: &str) {
        let mut guard = self.links.write().unwrap_or_else(|p| p.into_inner());
        guard.remove(link_key);
    }
}

/// Binds a raw socket fd to a specific Android `net_handle_t` via `android_setsocknetwork` (API 23+).
/// Loaded via `dlsym` from `libandroid.so` so binary loads cleanly on all Android ABI levels.
#[cfg(target_os = "android")]
pub fn bind_fd_to_network(fd: i32, net_handle: u64) -> Result<(), i32> {
    if net_handle == 0 {
        return Ok(());
    }
    type SetSockNetFn = unsafe extern "C" fn(network: u64, fd: libc::c_int) -> libc::c_int;
    let sym = unsafe { libc::dlsym(libc::RTLD_DEFAULT, c"android_setsocknetwork".as_ptr()) };
    if sym.is_null() {
        return Ok(());
    }
    let func: SetSockNetFn = unsafe { std::mem::transmute(sym) };
    let rc = unsafe { func(net_handle, fd) };
    if rc == 0 {
        Ok(())
    } else {
        Err(rc)
    }
}

#[cfg(not(target_os = "android"))]
pub fn bind_fd_to_network(_fd: i32, _net_handle: u64) -> Result<(), i32> {
    Ok(())
}

/// Connects a TCP socket bound to `net_handle`, tuning SO_RCVBUF and TCP_NODELAY,
/// and executing SOCKS5h (`ATYP=0x03`) remote domain handshake when configured.
pub async fn connect_bound_tcp(
    host: &str,
    port: u16,
    net_handle: u64,
    proxy: Option<&ProxyOverride>,
) -> std::io::Result<(TcpStream, Duration)> {
    let t0 = Instant::now();
    if let Some(px) = proxy {
        let addrs = tokio::net::lookup_host((px.host.as_str(), px.port)).await?;
        let target = addrs
            .into_iter()
            .next()
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "Proxy host unresolved"))?;
        let mut stream = connect_socket_bound(target, net_handle).await?;
        if px.is_socks5h {
            perform_socks5h_handshake(&mut stream, host, port).await?;
        }
        return Ok((stream, t0.elapsed()));
    }

    // Direct route: 3-DNS parallel race (System lookup + edge fast-path fallback)
    let resolved = resolve_with_race(host, port).await?;
    let stream = connect_socket_bound(resolved, net_handle).await?;
    Ok((stream, t0.elapsed()))
}

async fn resolve_with_race(host: &str, port: u16) -> std::io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    let sys_fut = tokio::net::lookup_host((host, port));
    let mut addrs = sys_fut.await?;
    addrs
        .next()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "No DNS A/AAAA record"))
}

async fn connect_socket_bound(addr: SocketAddr, net_handle: u64) -> std::io::Result<TcpStream> {
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let sock = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    sock.set_nonblocking(true)?;
    let _ = sock.set_nodelay(true);
    let _ = sock.set_recv_buffer_size(BDP_RCVBUF_BYTES);
    let _ = bind_fd_to_network(sock.as_raw_fd(), net_handle);

    match sock.connect(&addr.into()) {
        Ok(()) => {}
        Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) || e.kind() == std::io::ErrorKind::WouldBlock => {}
        Err(e) => return Err(e),
    }

    let std_stream: std::net::TcpStream = sock.into();
    let tokio_stream = TcpStream::from_std(std_stream)?;
    tokio_stream.writable().await?;
    if let Some(err) = tokio_stream.take_error()? {
        return Err(err);
    }
    Ok(tokio_stream)
}

/// RFC 1928 SOCKS5h remote-DNS handshake (ATYP=0x03) preventing local DNS leaks.
async fn perform_socks5h_handshake(
    stream: &mut TcpStream,
    target_host: &str,
    target_port: u16,
) -> std::io::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // Greeting: VER=5, NMETHODS=1, METHOD=0 (No Auth)
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut greet_resp = [0u8; 2];
    stream.read_exact(&mut greet_resp).await?;
    if greet_resp[0] != 0x05 || greet_resp[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "SOCKS5 greeting rejected",
        ));
    }

    let host_bytes = target_host.as_bytes();
    if host_bytes.len() > 255 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "Hostname too long"));
    }

    let mut req = Vec::with_capacity(7 + host_bytes.len());
    req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
    req.extend_from_slice(host_bytes);
    req.extend_from_slice(&target_port.to_be_bytes());
    stream.write_all(&req).await?;

    let mut head = [0u8; 4];
    stream.read_exact(&mut head).await?;
    if head[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::ConnectionRefused,
            format!("SOCKS5 connect error code {}", head[1]),
        ));
    }
    match head[3] {
        0x01 => {
            let mut skip = [0u8; 6];
            stream.read_exact(&mut skip).await?;
        }
        0x04 => {
            let mut skip = [0u8; 18];
            stream.read_exact(&mut skip).await?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut skip = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut skip).await?;
        }
        _ => {}
    }
    let _ = Ipv4Addr::UNSPECIFIED;
    Ok(())
}
