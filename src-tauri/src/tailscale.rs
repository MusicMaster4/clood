use serde::{Deserialize, Serialize};
use std::net::Ipv4Addr;
use std::process::Command;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TailscaleInfo {
    pub available: bool,
    pub ipv4: Option<String>,
    pub dns_name: Option<String>,
    pub tailnet_name: Option<String>,
    pub error: Option<String>,
}

impl Default for TailscaleInfo {
    fn default() -> Self {
        Self {
            available: false,
            ipv4: None,
            dns_name: None,
            tailnet_name: None,
            error: Some("Tailscale not detected".to_string()),
        }
    }
}

/// Returns true iff the address belongs to Tailscale's CGNAT range (100.64.0.0/10).
pub fn is_tailscale_ipv4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 100 && (o[1] & 0xC0) == 64
}

fn run_tailscale(args: &[&str]) -> Result<String, String> {
    let candidates: &[&str] = if cfg!(windows) {
        &[
            "tailscale",
            r"C:\Program Files\Tailscale\tailscale.exe",
            r"C:\Program Files (x86)\Tailscale\tailscale.exe",
        ]
    } else {
        &["tailscale", "/usr/bin/tailscale", "/usr/local/bin/tailscale"]
    };

    let mut last_err = "tailscale CLI not found".to_string();
    for bin in candidates {
        let mut cmd = Command::new(bin);
        cmd.args(args);
        #[cfg(windows)]
        cmd.creation_flags(CREATE_NO_WINDOW);
        match cmd.output() {
            Ok(out) if out.status.success() => {
                return Ok(String::from_utf8_lossy(&out.stdout).trim().to_string());
            }
            Ok(out) => {
                let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
                if !err.is_empty() {
                    last_err = err;
                }
            }
            Err(e) => {
                last_err = e.to_string();
            }
        }
    }
    Err(last_err)
}

pub async fn detect_tailscale() -> TailscaleInfo {
    tokio::task::spawn_blocking(detect_tailscale_sync)
        .await
        .unwrap_or_default()
}

fn detect_tailscale_sync() -> TailscaleInfo {
    if let Ok(raw) = run_tailscale(&["status", "--json"]) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) {
            let backend_state = v
                .get("BackendState")
                .and_then(|s| s.as_str())
                .unwrap_or_default();
            let ips = v
                .get("TailscaleIPs")
                .and_then(|a| a.as_array())
                .into_iter()
                .flatten()
                .filter_map(|x| x.as_str());

            let ipv4 = ips
                .filter_map(|s| s.parse::<Ipv4Addr>().ok())
                .find(|&ip| is_tailscale_ipv4(ip));

            let dns_name = v
                .pointer("/Self/DNSName")
                .and_then(|s| s.as_str())
                .map(|s| s.trim_end_matches('.').to_string())
                .filter(|s| !s.is_empty());

            let tailnet_name = v
                .pointer("/CurrentTailnet/MagicDNSSuffix")
                .and_then(|s| s.as_str())
                .or_else(|| v.pointer("/CurrentTailnet/Name").and_then(|s| s.as_str()))
                .map(|s| s.trim_end_matches('.').to_string())
                .filter(|s| !s.is_empty());

            if let Some(ip) = ipv4 {
                if backend_state.is_empty() || backend_state == "Running" {
                    return TailscaleInfo {
                        available: true,
                        ipv4: Some(ip.to_string()),
                        dns_name,
                        tailnet_name,
                        error: None,
                    };
                }
                return TailscaleInfo {
                    available: false,
                    ipv4: Some(ip.to_string()),
                    dns_name,
                    tailnet_name,
                    error: Some(format!("Tailscale state: {backend_state}")),
                };
            }
        }
    }

    // Fallback to `tailscale ip -4`
    match run_tailscale(&["ip", "-4"]) {
        Ok(out) => {
            if let Some(ip) = out
                .lines()
                .filter_map(|l| l.trim().parse::<Ipv4Addr>().ok())
                .find(|&ip| is_tailscale_ipv4(ip))
            {
                return TailscaleInfo {
                    available: true,
                    ipv4: Some(ip.to_string()),
                    dns_name: None,
                    tailnet_name: None,
                    error: None,
                };
            }
            TailscaleInfo {
                error: Some("No Tailscale IPv4 address assigned".to_string()),
                ..Default::default()
            }
        }
        Err(err) => TailscaleInfo {
            error: Some(err),
            ..Default::default()
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_tailscale_cgnat_range() {
        assert!(is_tailscale_ipv4(Ipv4Addr::new(100, 64, 0, 1)));
        assert!(is_tailscale_ipv4(Ipv4Addr::new(100, 113, 41, 57)));
        assert!(is_tailscale_ipv4(Ipv4Addr::new(100, 127, 255, 254)));
        assert!(!is_tailscale_ipv4(Ipv4Addr::new(100, 63, 255, 255)));
        assert!(!is_tailscale_ipv4(Ipv4Addr::new(100, 128, 0, 1)));
        assert!(!is_tailscale_ipv4(Ipv4Addr::new(192, 168, 1, 10)));
        assert!(!is_tailscale_ipv4(Ipv4Addr::new(127, 0, 0, 1)));
    }
}
