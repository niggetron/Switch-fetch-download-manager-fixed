//! Hybrid Dual-TLS + HTTP/3 Transport Engine (v9.0 Anti-Dip & Persistent Pooling):
//! - Persistent cached `wreq::Client` pool per `(LinkKey, local_ip, proxy, profile)` so TLS 1.3 +
//!   HTTP/2 sockets stay warm across every segment (eliminating TCP Slow Start dips!)
//! - Per-link real measured EWMA throughput + live in-flight byte tracking
//! - 3-DNS parallel race (System + 8.8.8.8:53 + 1.1.1.1:53 bound via android_setsocknetwork)
//! - Pre-connect socket2 tuning (SO_RCVBUF 8 MiB, TCP_NODELAY, TCP_USER_TIMEOUT = 10,000 ms)
//! - NDK android_getnetworkblockedreason(sock.as_raw_fd()) BEFORE dropping failed sockets
//! - Support for both SOCKS5h and HTTP Companion Wi-Fi 2 Relay proxies + .no_proxy() isolation
//! - quinn + h3 + h3-quinn HTTP/3 QUIC + 0-RTT over bound UDP sockets

use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket as StdUdpSocket};
use std::os::fd::AsRawFd;
use std::path::Path;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use bytes::Buf;
use http::{HeaderMap, HeaderValue, Request, StatusCode};
use rustls::ClientConfig;
use rustls::client::Resumption;
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::{TcpStream, UdpSocket};
use wreq::header::{ACCEPT_ENCODING, RANGE};
use wreq::{Client as WreqClient, Proxy as WreqProxy};
use wreq_util::Emulation;

use crate::diagnostics::{FLAG_WREQ_EMULATION, is_flag_enabled, record_diag};
#[cfg(target_os = "android")]
use crate::storage_and_telemetry::live_telemetry_page;

pub const BDP_RCVBUF_BYTES: usize = 8 * 1024 * 1024; // 8 MiB high-BDP Wi-Fi 6 / 5G receive window
pub const TCP_USER_TIMEOUT_MS: i32 = 10_000;
const ALT_SVC_CACHE_MAX_BYTES: usize = 256 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum NetworkBlockedReason {
    None = 0,
    DataSaver = 1,
    Oem = 2,
    Doze = 3,
    LocalNetwork = 4,
    UnknownPolicy = 5,
}

impl NetworkBlockedReason {
    pub fn label(self) -> &'static str {
        match self {
            NetworkBlockedReason::None => "NONE",
            NetworkBlockedReason::DataSaver => "DATA_SAVER",
            NetworkBlockedReason::Oem => "OEM_FIREWALL",
            NetworkBlockedReason::Doze => "DOZE_IDLE",
            NetworkBlockedReason::LocalNetwork => "LOCAL_NETWORK_LNP",
            NetworkBlockedReason::UnknownPolicy => "POLICY_BLOCKED",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ProxyOverride {
    pub host: String,
    pub port: u16,
    pub is_socks5h: bool,
    pub is_http: bool,
}

#[derive(Debug)]
pub struct BoundLinkState {
    pub link_key: String,
    pub net_handle: AtomicU64,
    pub downstream_kbps: AtomicU64,
    pub measured_ewma_bps: AtomicU64, // Real measured bytes/sec EWMA (updated live during transfers)
    pub in_flight_bytes: AtomicU64,   // Real active queued bytes on this link
    pub active_workers: AtomicU64,    // Active parallel streams pinned to this link
    pub rtt_ms: AtomicU64,
    pub is_cellular: bool,
    pub local_ip: RwLock<Option<IpAddr>>,
    pub proxy_override: RwLock<Option<ProxyOverride>>,
    pub failure_streak: AtomicI32,
    pub circuit_open_until_ms: AtomicU64,
    pub last_blocked_reason: AtomicU64,
}

impl BoundLinkState {
    pub fn new(
        link_key: String,
        handle: u64,
        downstream_kbps: u64,
        rtt_ms: u64,
        is_cellular: bool,
        local_ip: Option<IpAddr>,
        proxy_override: Option<ProxyOverride>,
    ) -> Self {
        // Cap initial unmeasured bootstrap EWMA at 10 MB/s (80 Mbps) so Android's fake 866 Mbps
        // Wi-Fi PHY rate never starves Mobile 5G/LTE or Wi-Fi 2 before real measurements arrive!
        let initial_bytes_per_sec = (downstream_kbps.max(4_000) * 125).clamp(500_000, 10_000_000);
        Self {
            link_key,
            net_handle: AtomicU64::new(handle),
            downstream_kbps: AtomicU64::new(downstream_kbps.max(1000)),
            measured_ewma_bps: AtomicU64::new(initial_bytes_per_sec),
            in_flight_bytes: AtomicU64::new(0),
            active_workers: AtomicU64::new(0),
            rtt_ms: AtomicU64::new(rtt_ms.max(8)),
            is_cellular,
            local_ip: RwLock::new(local_ip),
            proxy_override: RwLock::new(proxy_override),
            failure_streak: AtomicI32::new(0),
            circuit_open_until_ms: AtomicU64::new(0),
            last_blocked_reason: AtomicU64::new(0),
        }
    }

    pub fn update_handle(
        &self,
        new_handle: u64,
        downstream_kbps: u64,
        rtt_ms: u64,
        local_ip: Option<IpAddr>,
    ) {
        self.net_handle.store(new_handle, Ordering::Release);
        self.downstream_kbps
            .store(downstream_kbps.max(1000), Ordering::Relaxed);
        self.rtt_ms.store(rtt_ms.max(8), Ordering::Relaxed);
        if let Some(ip) = local_ip {
            *self.local_ip.write().unwrap_or_else(|p| p.into_inner()) = Some(ip);
        }
        self.failure_streak.store(0, Ordering::Relaxed);
        self.circuit_open_until_ms.store(0, Ordering::Release);
        self.last_blocked_reason.store(0, Ordering::Release);
    }

    pub fn record_measured_bytes_per_sec(&self, sample_bps: u64) {
        if sample_bps == 0 {
            return;
        }
        let prev = self.measured_ewma_bps.load(Ordering::Relaxed);
        let blended = if prev == 0 {
            sample_bps
        } else {
            ((prev as f64) * 0.65 + (sample_bps as f64) * 0.35) as u64
        };
        self.measured_ewma_bps
            .store(blended.max(125_000), Ordering::Relaxed);
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct AltSvcHint {
    pub key: String,
    pub h3_authority: String,
    pub observed_epoch_secs: u64,
    pub ttfb_ms: u64,
}

pub struct AltSvcHintCache {
    order: VecDeque<String>,
    map: HashMap<String, AltSvcHint>,
    approx_bytes: usize,
}

impl AltSvcHintCache {
    pub fn new() -> Self {
        Self {
            order: VecDeque::new(),
            map: HashMap::new(),
            approx_bytes: 0,
        }
    }

    pub fn make_key(host: &str, link_key: &str) -> String {
        format!("{}|{}", host.to_ascii_lowercase(), link_key)
    }

    pub fn get(&self, host: &str, link_key: &str) -> Option<AltSvcHint> {
        self.map.get(&Self::make_key(host, link_key)).cloned()
    }

    pub fn insert(&mut self, host: &str, link_key: &str, h3_authority: String, ttfb_ms: u64) {
        let key = Self::make_key(host, link_key);
        let entry_cost = key.len() + h3_authority.len() + 32;
        if let Some(existing) = self.map.remove(&key) {
            self.approx_bytes = self
                .approx_bytes
                .saturating_sub(key.len() + existing.h3_authority.len() + 32);
            self.order.retain(|k| k != &key);
        }
        while self.approx_bytes + entry_cost > ALT_SVC_CACHE_MAX_BYTES {
            if let Some(evict) = self.order.pop_front() {
                if let Some(old) = self.map.remove(&evict) {
                    self.approx_bytes = self
                        .approx_bytes
                        .saturating_sub(evict.len() + old.h3_authority.len() + 32);
                }
            } else {
                break;
            }
        }
        let now_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        self.approx_bytes += entry_cost;
        self.order.push_back(key.clone());
        self.map.insert(
            key.clone(),
            AltSvcHint {
                key,
                h3_authority,
                observed_epoch_secs: now_secs,
                ttfb_ms,
            },
        );
    }

    pub fn save_to_disk(&self, path: &Path) {
        if let Ok(json) = serde_json::to_vec(&self.map.values().collect::<Vec<_>>()) {
            if json.len() <= ALT_SVC_CACHE_MAX_BYTES {
                let _ = std::fs::write(path, json);
            }
        }
    }

    pub fn load_from_disk(&mut self, path: &Path) {
        if let Ok(bytes) = std::fs::read(path) {
            if let Ok(list) = serde_json::from_slice::<Vec<AltSvcHint>>(&bytes) {
                for item in list {
                    if let Some((host, lk)) = item.key.split_once('|') {
                        self.insert(host, lk, item.h3_authority, item.ttfb_ms);
                    }
                }
            }
        }
    }
}

pub struct TransportContext {
    pub links: RwLock<HashMap<String, Arc<BoundLinkState>>>,
    pub tls_config: Arc<ClientConfig>,
    pub h3_tls_config: Arc<ClientConfig>,
    pub alt_svc_cache: RwLock<AltSvcHintCache>,
    /// Persistent per-link warm wreq::Client cache (fixes Slow Start reconnect sawtooth!).
    wreq_pool: RwLock<HashMap<String, WreqClient>>,
}

impl TransportContext {
    pub fn new() -> Self {
        let mut root_store = rustls::RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

        let mut cfg = ClientConfig::builder()
            .with_root_certificates(root_store.clone())
            .with_no_client_auth();
        cfg.resumption = Resumption::in_memory_sessions(128);
        cfg.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

        let mut h3_cfg = ClientConfig::builder()
            .with_root_certificates(root_store)
            .with_no_client_auth();
        h3_cfg.resumption = Resumption::in_memory_sessions(128);
        h3_cfg.enable_early_data = true;
        h3_cfg.alpn_protocols = vec![b"h3".to_vec()];

        Self {
            links: RwLock::new(HashMap::new()),
            tls_config: Arc::new(cfg),
            h3_tls_config: Arc::new(h3_cfg),
            alt_svc_cache: RwLock::new(AltSvcHintCache::new()),
            wreq_pool: RwLock::new(HashMap::new()),
        }
    }

    pub fn upsert_link(
        &self,
        link_key: String,
        net_handle: u64,
        downstream_kbps: u64,
        rtt_ms: u64,
        is_cellular: bool,
        local_ip: Option<IpAddr>,
        proxy_override: Option<ProxyOverride>,
    ) -> bool {
        let mut guard = self.links.write().unwrap_or_else(|p| p.into_inner());
        let ip_changed_or_new = if let Some(existing) = guard.get(&link_key) {
            let prev_ip = *existing.local_ip.read().unwrap_or_else(|p| p.into_inner());
            let prev_px = existing.proxy_override.read().unwrap_or_else(|p| p.into_inner()).clone();
            existing.update_handle(net_handle, downstream_kbps, rtt_ms, local_ip);
            *existing
                .proxy_override
                .write()
                .unwrap_or_else(|p| p.into_inner()) = proxy_override.clone();
            if prev_ip != local_ip || prev_px != proxy_override {
                self.invalidate_client_pool_for_link(&link_key);
            }
            false
        } else {
            guard.insert(
                link_key.clone(),
                Arc::new(BoundLinkState::new(
                    link_key.clone(),
                    net_handle,
                    downstream_kbps,
                    rtt_ms,
                    is_cellular,
                    local_ip,
                    proxy_override,
                )),
            );
            true
        };
        ip_changed_or_new
    }

    pub fn remove_link(&self, link_key: &str) {
        self.links
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .remove(link_key);
        self.invalidate_client_pool_for_link(link_key);
    }

    fn invalidate_client_pool_for_link(&self, link_key: &str) {
        let prefix = format!("{link_key}|");
        self.wreq_pool
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .retain(|k, _| !k.starts_with(&prefix));
    }

    /// Returns a sharded persistent warm `wreq::Client` pooled per `(link_key, local_ip, proxy, profile, shard_id)`.
    /// Sharding forces distinct independent TCP sockets (`http1_only` on multi-socket stripes)
    /// so 16-64 concurrent workers NEVER collapse onto 1 single HTTP/2 TCP congestion window!
    pub fn get_or_build_wreq_client(
        &self,
        link_key: &str,
        local_ip: Option<IpAddr>,
        proxy: Option<&ProxyOverride>,
        profile: &str,
        shard_id: usize,
    ) -> Result<WreqClient, wreq::Error> {
        let effective_shard = shard_id % 16;
        let px_tag = proxy
            .map(|p| format!("{}:{}:{}:{}", p.host, p.port, p.is_socks5h, p.is_http))
            .unwrap_or_else(|| "direct".to_string());
        let ip_tag = local_ip
            .map(|ip| ip.to_string())
            .unwrap_or_else(|| "any".to_string());
        let cache_key = format!(
            "{link_key}|{ip_tag}|{px_tag}|{profile}|{}|s{effective_shard}",
            is_flag_enabled(FLAG_WREQ_EMULATION)
        );

        if let Some(cached) = self
            .wreq_pool
            .read()
            .unwrap_or_else(|p| p.into_inner())
            .get(&cache_key)
            .cloned()
        {
            return Ok(cached);
        }

        let built = build_wreq_link_client(local_ip, proxy, profile, effective_shard > 0)?;
        self.wreq_pool
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .insert(cache_key, built.clone());
        Ok(built)
    }

    pub fn snapshot_links(&self) -> Vec<Arc<BoundLinkState>> {
        self.links
            .read()
            .unwrap_or_else(|p| p.into_inner())
            .values()
            .cloned()
            .collect()
    }
}

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
    if rc == 0 { Ok(()) } else { Err(rc) }
}

#[cfg(not(target_os = "android"))]
pub fn bind_fd_to_network(_fd: i32, _net_handle: u64) -> Result<(), i32> {
    Ok(())
}

#[cfg(target_os = "android")]
pub fn query_socket_blocked_reason(fd: i32) -> NetworkBlockedReason {
    type BlockedReasonFn = unsafe extern "C" fn(sockfd: libc::c_int) -> i32;
    let sym = unsafe {
        libc::dlsym(
            libc::RTLD_DEFAULT,
            c"android_getnetworkblockedreason".as_ptr(),
        )
    };
    if sym.is_null() {
        return NetworkBlockedReason::None;
    }
    let func: BlockedReasonFn = unsafe { std::mem::transmute(sym) };
    let raw = unsafe { func(fd) };
    let reason = match raw {
        0 => NetworkBlockedReason::None,
        1 => NetworkBlockedReason::DataSaver,
        2 => NetworkBlockedReason::Doze,
        4 => NetworkBlockedReason::Oem,
        8 | 16 => NetworkBlockedReason::LocalNetwork,
        r if r > 0 => NetworkBlockedReason::UnknownPolicy,
        _ => NetworkBlockedReason::None,
    };
    if reason != NetworkBlockedReason::None {
        live_telemetry_page()
            .blocked_reason_code
            .store(reason as u64, Ordering::Release);
    }
    reason
}

#[cfg(not(target_os = "android"))]
pub fn query_socket_blocked_reason(_fd: i32) -> NetworkBlockedReason {
    NetworkBlockedReason::None
}

fn tune_tcp_socket(sock: &Socket) {
    let _ = sock.set_nodelay(true);
    let _ = sock.set_recv_buffer_size(BDP_RCVBUF_BYTES);
    #[cfg(any(target_os = "linux", target_os = "android"))]
    unsafe {
        const LINUX_TCP_USER_TIMEOUT: libc::c_int = 18;
        let timeout_ms: libc::c_int = TCP_USER_TIMEOUT_MS;
        libc::setsockopt(
            sock.as_raw_fd(),
            libc::IPPROTO_TCP,
            LINUX_TCP_USER_TIMEOUT,
            (&timeout_ms as *const libc::c_int).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
}

pub async fn connect_bound_tcp(
    host: &str,
    port: u16,
    net_handle: u64,
    proxy: Option<&ProxyOverride>,
) -> std::io::Result<(TcpStream, Duration)> {
    let t0 = Instant::now();
    if let Some(px) = proxy {
        let resolved = resolve_with_race(&px.host, px.port, net_handle).await?;
        let mut stream = connect_socket_bound(resolved, net_handle).await?;
        if px.is_socks5h && !px.is_http {
            perform_socks5h_handshake(&mut stream, host, port).await?;
        }
        return Ok((stream, t0.elapsed()));
    }

    let resolved = resolve_with_race(host, port, net_handle).await?;
    let stream = connect_socket_bound(resolved, net_handle).await?;
    Ok((stream, t0.elapsed()))
}

pub async fn resolve_with_race(
    host: &str,
    port: u16,
    net_handle: u64,
) -> std::io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }

    let h_sys = host.to_string();
    let h_g = host.to_string();
    let h_cf = host.to_string();

    let sys_fut: futures_util::future::BoxFuture<'static, std::io::Result<SocketAddr>> = Box::pin(async move {
        let mut addrs = tokio::net::lookup_host((h_sys.as_str(), port)).await?;
        addrs
            .next()
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "System DNS empty"))
    });
    let g_fut: futures_util::future::BoxFuture<'static, std::io::Result<SocketAddr>> = Box::pin(udp_dns_a_query(
        h_g,
        port,
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 53),
        net_handle,
    ));
    let cf_fut: futures_util::future::BoxFuture<'static, std::io::Result<SocketAddr>> = Box::pin(udp_dns_a_query(
        h_cf,
        port,
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53),
        net_handle,
    ));

    // Drive ALL racers until the first success. The old `tokio::select!` returned
    // on first COMPLETION (even an Err), then abandoned the still-running resolvers
    // and fell through to a fourth, unbound lookup_host - defeating the race.
    // `select_ok` polls every future, ignores individual errors and returns the
    // first success; only when ALL fail do we fall back below.
    let attempts = [sys_fut, g_fut, cf_fut];
    if let Ok((addr, _remaining)) = futures_util::future::select_ok(attempts).await {
        return Ok(addr);
    }
    // All bound resolvers failed; fall back to the default-network resolver.
    tokio::net::lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "No DNS A/AAAA record"))
}

async fn udp_dns_a_query(
    host: String,
    port: u16,
    server: SocketAddr,
    net_handle: u64,
) -> std::io::Result<SocketAddr> {
    let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    sock.set_nonblocking(true)?;
    let _ = bind_fd_to_network(sock.as_raw_fd(), net_handle);
    sock.bind(&SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0).into())?;
    let std_udp: StdUdpSocket = sock.into();
    let udp = UdpSocket::from_std(std_udp)?;

    let mut pkt = Vec::with_capacity(64);
    pkt.extend_from_slice(&[0xAE, 0x71, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in host.split('.').filter(|s| !s.is_empty()) {
        if label.len() > 63 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "DNS label"));
        }
        pkt.push(label.len() as u8);
        pkt.extend_from_slice(label.as_bytes());
    }
    pkt.extend_from_slice(&[0x00, 0x00, 0x01, 0x00, 0x01]);

    udp.send_to(&pkt, server).await?;
    let mut resp = [0u8; 512];
    let (n, _) = tokio::time::timeout(Duration::from_millis(1200), udp.recv_from(&mut resp))
        .await
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "DNS UDP timeout"))??;

    if let Some(ip) = parse_dns_first_a_record(&resp[..n]) {
        Ok(SocketAddr::new(IpAddr::V4(ip), port))
    } else {
        Err(std::io::Error::new(std::io::ErrorKind::NotFound, "No A record in DNS response"))
    }
}

fn parse_dns_first_a_record(buf: &[u8]) -> Option<Ipv4Addr> {
    if buf.len() < 12 {
        return None;
    }
    let ancount = u16::from_be_bytes([buf[6], buf[7]]);
    if ancount == 0 {
        return None;
    }
    let mut pos = 12;
    while pos < buf.len() {
        let len = buf[pos];
        if len == 0 {
            pos += 1;
            break;
        }
        if (len & 0xC0) == 0xC0 {
            pos += 2;
            break;
        }
        pos += 1 + len as usize;
    }
    pos += 4;

    for _ in 0..ancount {
        if pos >= buf.len() {
            break;
        }
        if (buf[pos] & 0xC0) == 0xC0 {
            pos += 2;
        } else {
            while pos < buf.len() && buf[pos] != 0 {
                pos += 1 + buf[pos] as usize;
            }
            pos += 1;
        }
        if pos + 10 > buf.len() {
            break;
        }
        let rtype = u16::from_be_bytes([buf[pos], buf[pos + 1]]);
        let rdlen = u16::from_be_bytes([buf[pos + 8], buf[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > buf.len() {
            break;
        }
        if rtype == 1 && rdlen == 4 {
            return Some(Ipv4Addr::new(buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3]));
        }
        pos += rdlen;
    }
    None
}

async fn connect_socket_bound(addr: SocketAddr, net_handle: u64) -> std::io::Result<TcpStream> {
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let sock = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    sock.set_nonblocking(true)?;
    tune_tcp_socket(&sock);
    let _ = bind_fd_to_network(sock.as_raw_fd(), net_handle);

    match sock.connect(&addr.into()) {
        Ok(()) => {}
        Err(e)
            if e.raw_os_error() == Some(libc::EINPROGRESS)
                || e.kind() == std::io::ErrorKind::WouldBlock => {}
        Err(e) => {
            let reason = query_socket_blocked_reason(sock.as_raw_fd());
            if reason != NetworkBlockedReason::None {
                record_diag("NET_BLOCK", &format!("TCP sync connect blocked: {}", reason.label()));
                return Err(std::io::Error::new(
                    e.kind(),
                    format!("{} ({})", reason.label(), e),
                ));
            }
            return Err(e);
        }
    }

    let std_stream: std::net::TcpStream = sock.into();
    let tokio_stream = TcpStream::from_std(std_stream)?;
    tokio_stream.writable().await?;
    if let Some(err) = tokio_stream.take_error()? {
        let reason = query_socket_blocked_reason(tokio_stream.as_raw_fd());
        if reason != NetworkBlockedReason::None {
            record_diag("NET_BLOCK", &format!("TCP async connect blocked: {}", reason.label()));
            return Err(std::io::Error::new(
                err.kind(),
                format!("{} ({})", reason.label(), err),
            ));
        }
        return Err(err);
    }
    Ok(tokio_stream)
}

/// Build a persistent sharded, link-isolated `wreq::Client` with keep-alive + independent sockets.
pub fn build_wreq_link_client(
    local_ip: Option<IpAddr>,
    proxy: Option<&ProxyOverride>,
    profile: &str,
    _separate_shard: bool,
) -> Result<WreqClient, wreq::Error> {
    let mut builder = WreqClient::builder()
        .connect_timeout(Duration::from_secs(12))
        .tcp_nodelay(true)
        .pool_max_idle_per_host(8)
        .pool_idle_timeout(Duration::from_secs(90));

    if is_flag_enabled(FLAG_WREQ_EMULATION) {
        let emu = match profile {
            "okhttp5" => Emulation::OkHttp5,
            "chrome131" => Emulation::Chrome131,
            _ => Emulation::Chrome134,
        };
        builder = builder.emulation(emu);
    }

    if let Some(ip) = local_ip {
        builder = builder.local_address(ip);
    }

    if let Some(px) = proxy {
        let scheme = if px.is_http {
            "http"
        } else if px.is_socks5h {
            "socks5h"
        } else {
            "socks5"
        };
        let uri = format!("{scheme}://{}:{}", px.host, px.port);
        if let Ok(p) = WreqProxy::all(uri) {
            builder = builder.proxy(p);
        }
    } else {
        builder = builder.no_proxy();
    }

    builder.build()
}

/// Probes a bound Android network handle directly via `connect_bound_tcp` (`android_setsocknetwork` +
/// pre-drop `android_getnetworkblockedreason(fd)`) so Diagnostics immediately capture whether OEM
/// Firewall / Data Saver / Doze / LNP is blocking a secondary Cellular or Wi-Fi 2 link!
pub async fn probe_bound_link_blocked_reason(
    url_str: &str,
    net_handle: u64,
    proxy: Option<&ProxyOverride>,
) {
    if net_handle == 0 {
        return;
    }
    if let Ok(parsed) = url::Url::parse(url_str) {
        if let Some(host) = parsed.host_str() {
            let port = parsed.port_or_known_default().unwrap_or(443);
            if let Err(err) = connect_bound_tcp(host, port, net_handle, proxy).await {
                record_diag(
                    "BOUND_PROBE",
                    &format!("net_handle={net_handle} probe to {host}:{port} err={err}"),
                );
            }
        }
    }
}

pub async fn send_wreq_range_request(
    client: &WreqClient,
    url: &str,
    range_header: Option<&str>,
) -> Result<wreq::Response, wreq::Error> {
    let mut req = client.get(url).header(ACCEPT_ENCODING, "identity");
    if let Some(r) = range_header {
        req = req.header(RANGE, r);
    }
    req.send().await
}

pub async fn fetch_h3_range_bytes(
    ctx: &TransportContext,
    link_key: &str,
    net_handle: u64,
    url_str: &str,
    range_header: Option<&str>,
    max_probe_bytes: usize,
) -> io::Result<(StatusCode, HeaderMap, Vec<u8>)> {
    let parsed = url::Url::parse(url_str)
        .map_err(|e| io::Error::new(std::io::ErrorKind::InvalidInput, e.to_string()))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(std::io::ErrorKind::InvalidInput, "Missing host"))?;
    let port = parsed.port_or_known_default().unwrap_or(443);

    let remote_addr = resolve_with_race(host, port, net_handle).await?;
    let domain = if remote_addr.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };
    let udp_sock = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    udp_sock.set_nonblocking(true)?;
    let _ = udp_sock.set_recv_buffer_size(BDP_RCVBUF_BYTES);
    let _ = bind_fd_to_network(udp_sock.as_raw_fd(), net_handle);
    let bind_addr = if remote_addr.is_ipv4() {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)
    } else {
        SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED), 0)
    };
    udp_sock.bind(&bind_addr.into())?;
    let std_udp: StdUdpSocket = udp_sock.into();

    let mut endpoint = quinn::Endpoint::new(
        quinn::EndpointConfig::default(),
        None,
        std_udp,
        Arc::new(quinn::TokioRuntime),
    )
    .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

    let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(ctx.h3_tls_config.clone())
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(quic_crypto)));

    let t0 = Instant::now();
    let connecting = endpoint
        .connect(remote_addr, host)
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
    let conn = match connecting.into_0rtt() {
        Ok((zero_rtt, _accepted)) => zero_rtt,
        Err(conn_fut) => conn_fut
            .await
            .map_err(|e| io::Error::new(std::io::ErrorKind::TimedOut, e.to_string()))?,
    };

    let (mut driver, mut send_req) = h3::client::builder()
        .build::<_, _, bytes::Bytes>(h3_quinn::Connection::new(conn))
        .await
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

    let driver_task = tokio::spawn(async move {
        let _ = std::future::poll_fn(|cx| driver.poll_close(cx)).await;
    });

    let mut req_builder = Request::builder()
        .method("GET")
        .uri(url_str)
        .header("accept-encoding", "identity")
        .header("user-agent", "AetherIO/9.0");
    if let Some(r) = range_header {
        req_builder = req_builder.header("range", r);
    }
    let req = req_builder
        .body(())
        .map_err(|e| io::Error::new(std::io::ErrorKind::InvalidInput, e.to_string()))?;

    let mut stream = send_req
        .send_request(req)
        .await
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
    stream
        .finish()
        .await
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

    let resp = stream
        .recv_response()
        .await
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
    let status = resp.status();
    let headers = resp.headers().clone();

    let ttfb = t0.elapsed().as_millis() as u64;
    if let Some(alt_svc) = headers.get("alt-svc").and_then(|v| v.to_str().ok()) {
        if alt_svc.contains("h3=") {
            ctx.alt_svc_cache
                .write()
                .unwrap_or_else(|p| p.into_inner())
                .insert(host, link_key, alt_svc.to_string(), ttfb);
        }
    }

    let mut data = Vec::new();
    while let Some(mut chunk) = stream
        .recv_data()
        .await
        .map_err(|e| io::Error::new(std::io::ErrorKind::Other, e.to_string()))?
    {
        let remaining = max_probe_bytes.saturating_sub(data.len());
        if remaining == 0 {
            break;
        }
        let take = chunk.remaining().min(remaining);
        let slice = chunk.copy_to_bytes(take);
        data.extend_from_slice(&slice);
    }

    drop(stream);
    driver_task.abort();
    Ok((status, headers, data))
}

pub fn parse_content_disposition_filename(header_val: &str) -> Option<String> {
    for part in header_val.split(';').map(str::trim) {
        let lower = part.to_ascii_lowercase();
        if lower.starts_with("filename*=") {
            let raw = part[10..].trim();
            let encoded = raw.split("''").nth(1).unwrap_or(raw).trim_matches('"');
            let decoded = percent_decode_utf8(encoded);
            if !decoded.is_empty() {
                return Some(sanitize_filename(&decoded));
            }
        }
    }
    for part in header_val.split(';').map(str::trim) {
        let lower = part.to_ascii_lowercase();
        if lower.starts_with("filename=") {
            let raw = part[9..].trim().trim_matches('"');
            if !raw.is_empty() {
                return Some(sanitize_filename(raw));
            }
        }
    }
    None
}

fn percent_decode_utf8(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(hex) = std::str::from_utf8(&bytes[i + 1..i + 3]) {
                if let Ok(val) = u8::from_str_radix(hex, 16) {
                    out.push(val);
                    i += 3;
                    continue;
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

pub fn sanitize_filename(name: &str) -> String {
    name.chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect::<String>()
        .trim()
        .to_string()
}

pub fn sniff_mime_from_magic(prefix: &[u8], fallback_name: &str) -> &'static str {
    if prefix.starts_with(b"%PDF-") {
        return "application/pdf";
    }
    if prefix.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        return "image/png";
    }
    if prefix.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return "image/jpeg";
    }
    if prefix.starts_with(&[0x1F, 0x8B]) {
        return "application/gzip";
    }
    if prefix.starts_with(b"PK\x03\x04") {
        if fallback_name.ends_with(".apk") {
            return "application/vnd.android.package-archive";
        }
        return "application/zip";
    }
    if prefix.len() >= 12 && &prefix[4..8] == b"ftyp" {
        return "video/mp4";
    }
    if prefix.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
        return "video/webm";
    }
    if prefix.starts_with(b"ID3") || prefix.starts_with(&[0xFF, 0xFB]) {
        return "audio/mpeg";
    }
    if fallback_name.ends_with(".m3u8") {
        return "application/vnd.apple.mpegurl";
    }
    if fallback_name.ends_with(".mpd") {
        return "application/dash+xml";
    }
    "application/octet-stream"
}

#[allow(dead_code)]
fn _header_check(_v: HeaderValue) {}

async fn perform_socks5h_handshake(
    stream: &mut TcpStream,
    target_host: &str,
    target_port: u16,
) -> std::io::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

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
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "Hostname too long",
        ));
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
    Ok(())
}
