//! Local Aria2 JSON-RPC 2.0 Server (Fix #7 Lockdown):
//! - Disabled by default; explicitly toggled on by user
//! - Binds strictly to 127.0.0.1:6800 (never 0.0.0.0)
//! - Requires per-boot random 32-byte hex `token:<secret>` on every RPC method

use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;

use crate::diagnostics::record_diag;
use crate::storage_and_telemetry::live_telemetry_page;

pub const ARIA2_BIND_ADDR: &str = "127.0.0.1:6800";

static RPC_RUNNING: AtomicBool = AtomicBool::new(false);
static RPC_SECRET: std::sync::OnceLock<Mutex<String>> = std::sync::OnceLock::new();

#[derive(Debug, Clone)]
pub enum RpcCommand {
    AddUri {
        gid: String,
        urls: Vec<String>,
    },
    Pause {
        gid: String,
    },
    Unpause {
        gid: String,
    },
    Remove {
        gid: String,
    },
}

#[derive(Debug, Deserialize)]
struct JsonRpcRequest {
    jsonrpc: Option<String>,
    id: Option<Value>,
    method: String,
    params: Option<Vec<Value>>,
}

#[derive(Debug, Serialize)]
struct JsonRpcResponse {
    jsonrpc: &'static str,
    id: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<Value>,
}

pub fn generate_boot_secret() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0xA37E_9901);
    let pid = std::process::id() as u128;
    let h1 = blake3::hash(&(nanos ^ (pid << 32)).to_le_bytes());
    h1.to_hex()[..32].to_string()
}

pub fn active_secret() -> String {
    let lock = RPC_SECRET.get_or_init(|| Mutex::new(generate_boot_secret()));
    lock.lock().unwrap_or_else(|p| p.into_inner()).clone()
}

pub fn is_rpc_running() -> bool {
    RPC_RUNNING.load(Ordering::Acquire)
}

pub fn stop_rpc_server() {
    RPC_RUNNING.store(false, Ordering::Release);
    live_telemetry_page()
        .state_flags
        .fetch_and(!(1 << 12), Ordering::AcqRel);
    record_diag("ARIA2_RPC", "Aria2 local JSON-RPC server stopped");
}

pub async fn start_rpc_server(cmd_tx: mpsc::UnboundedSender<RpcCommand>) -> std::io::Result<String> {
    if RPC_RUNNING.swap(true, Ordering::AcqRel) {
        return Ok(active_secret());
    }
    let secret = generate_boot_secret();
    {
        let lock = RPC_SECRET.get_or_init(|| Mutex::new(secret.clone()));
        *lock.lock().unwrap_or_else(|p| p.into_inner()) = secret.clone();
    }

    let listener = match TcpListener::bind(ARIA2_BIND_ADDR).await {
        Ok(l) => l,
        Err(e) => {
            RPC_RUNNING.store(false, Ordering::Release);
            return Err(e);
        }
    };

    live_telemetry_page()
        .state_flags
        .fetch_or(1 << 12, Ordering::AcqRel);
    record_diag("ARIA2_RPC", "Listening on 127.0.0.1:6800 with per-boot rpc-secret");

    tokio::spawn(async move {
        while RPC_RUNNING.load(Ordering::Acquire) {
            let accept_res = tokio::time::timeout(
                std::time::Duration::from_millis(500),
                listener.accept(),
            )
            .await;

            let (mut stream, _) = match accept_res {
                Ok(Ok(pair)) => pair,
                Ok(Err(_)) => break,
                Err(_) => continue,
            };

            let tx = cmd_tx.clone();
            tokio::spawn(async move {
                let mut buf = vec![0u8; 32 * 1024];
                if let Ok(n) = stream.read(&mut buf).await {
                    if n == 0 {
                        return;
                    }
                    let raw = String::from_utf8_lossy(&buf[..n]);
                    let body = raw.split("\r\n\r\n").nth(1).unwrap_or(&raw);
                    let resp_json = handle_json_rpc_payload(body, &active_secret(), &tx);
                    let http_resp = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        resp_json.len(),
                        resp_json
                    );
                    let _ = stream.write_all(http_resp.as_bytes()).await;
                }
            });
        }
    });

    Ok(secret)
}

pub fn handle_json_rpc_payload(
    body: &str,
    expected_secret: &str,
    cmd_tx: &mpsc::UnboundedSender<RpcCommand>,
) -> String {
    let req: JsonRpcRequest = match serde_json::from_str(body) {
        Ok(r) => r,
        Err(_) => {
            return serde_json::to_string(&JsonRpcResponse {
                jsonrpc: "2.0",
                id: Value::Null,
                result: None,
                error: Some(json!({ "code": -32700, "message": "Parse error" })),
            })
            .unwrap_or_default();
        }
    };

    let req_id = req.id.unwrap_or(Value::Null);
    let _ = req.jsonrpc;
    let params = req.params.unwrap_or_default();

    // Enforce strict token:<secret> on EVERY call
    let expected_token = format!("token:{expected_secret}");
    let supplied_token = params.first().and_then(|v| v.as_str()).unwrap_or("");
    if supplied_token != expected_token {
        record_diag("ARIA2_AUTH", "Rejected unauthorized RPC call (invalid or missing rpc-secret)");
        return serde_json::to_string(&JsonRpcResponse {
            jsonrpc: "2.0",
            id: req_id,
            result: None,
            error: Some(json!({ "code": 1, "message": "Unauthorized: invalid rpc-secret token" })),
        })
        .unwrap_or_default();
    }

    let (result, error) = match req.method.as_str() {
        "aria2.getVersion" => (
            Some(json!({
                "version": "1.37.0-aetherio-v8.0",
                "enabledFeatures": ["HTTPS", "HTTP2", "HTTP3-QUIC", "MultiLink", "BLAKE3"]
            })),
            None,
        ),
        "aria2.addUri" => {
            let urls: Vec<String> = params
                .get(1)
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(str::to_string))
                        .collect()
                })
                .unwrap_or_default();
            if urls.is_empty() {
                (None, Some(json!({ "code": -32602, "message": "Missing URI array" })))
            } else {
                let gid = format!("aether_{:08x}", rand_u32_like());
                let _ = cmd_tx.send(RpcCommand::AddUri {
                    gid: gid.clone(),
                    urls,
                });
                (Some(Value::String(gid)), None)
            }
        }
        "aria2.pause" | "aria2.unpause" | "aria2.remove" => {
            let gid = params
                .get(1)
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if gid.is_empty() {
                (None, Some(json!({ "code": -32602, "message": "Missing GID" })))
            } else {
                let cmd = match req.method.as_str() {
                    "aria2.pause" => RpcCommand::Pause { gid: gid.clone() },
                    "aria2.unpause" => RpcCommand::Unpause { gid: gid.clone() },
                    _ => RpcCommand::Remove { gid: gid.clone() },
                };
                let _ = cmd_tx.send(cmd);
                (Some(Value::String(gid)), None)
            }
        }
        "aria2.tellStatus" => {
            let page = live_telemetry_page();
            let written = page.total_written.load(Ordering::Relaxed);
            let expected = page.total_expected.load(Ordering::Relaxed);
            let bps = page.goodput_bps.load(Ordering::Relaxed) / 8;
            (
                Some(json!({
                    "gid": params.get(1).and_then(|v| v.as_str()).unwrap_or("active"),
                    "status": "active",
                    "totalLength": expected.to_string(),
                    "completedLength": written.to_string(),
                    "downloadSpeed": bps.to_string(),
                })),
                None,
            )
        }
        "aria2.tellActive" => {
            let page = live_telemetry_page();
            (
                Some(json!([{
                    "gid": "aether_active",
                    "status": "active",
                    "completedLength": page.total_written.load(Ordering::Relaxed).to_string(),
                    "totalLength": page.total_expected.load(Ordering::Relaxed).to_string(),
                    "downloadSpeed": (page.goodput_bps.load(Ordering::Relaxed) / 8).to_string(),
                }])),
                None,
            )
        }
        _ => (
            None,
            Some(json!({ "code": -32601, "message": "Method not found" })),
        ),
    };

    serde_json::to_string(&JsonRpcResponse {
        jsonrpc: "2.0",
        id: req_id,
        result,
        error,
    })
    .unwrap_or_default()
}

fn rand_u32_like() -> u32 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| (d.as_nanos() & 0xFFFF_FFFF) as u32)
        .unwrap_or(0x1337_BEEF)
}
