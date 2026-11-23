use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

use crate::codec::AudioCodec;

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CloodConfig {
    pub openai_api_key: String,
    pub agent_name: String,
    pub voice: String,
    pub orb_color: String,
    #[serde(default)]
    pub audio_codec: AudioCodec,
    pub tailscale_port: u16,
    pub api_port: u16,
    pub pairing_token: String,
    pub base_instructions: String,
    pub paired_device_name: Option<String>,
    #[serde(default)]
    pub launch_at_startup: bool,
    #[serde(default = "default_true")]
    pub start_in_tray: bool,
    #[serde(default)]
    pub onboarding_completed: bool,
}

impl Default for CloodConfig {
    fn default() -> Self {
        Self {
            openai_api_key: String::new(),
            agent_name: "Clood".to_string(),
            voice: "marin".to_string(),
            orb_color: "#7c8cff".to_string(),
            audio_codec: AudioCodec::Adpcm24k,
            tailscale_port: 19480,
            api_port: 19481,
            pairing_token: generate_token(),
            base_instructions: "You are calling the user over Clood. Be concise, natural, warm, and direct. Speak in the same language as the provided context or the user.".to_string(),
            paired_device_name: None,
            launch_at_startup: false,
            start_in_tray: true,
            onboarding_completed: false,
        }
    }
}

pub fn generate_token() -> String {
    let mut bytes = [0u8; 24];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn data_dir() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("Clood")
}

pub fn calls_dir() -> PathBuf {
    data_dir().join("calls")
}

fn config_path() -> PathBuf {
    data_dir().join("config.json")
}

pub fn load_config() -> CloodConfig {
    let path = config_path();
    let Ok(raw) = fs::read_to_string(&path) else {
        let cfg = CloodConfig::default();
        let _ = save_config(&cfg);
        return cfg;
    };
    let mut cfg: CloodConfig = serde_json::from_str(&raw).unwrap_or_default();
    if cfg.pairing_token.trim().is_empty() {
        cfg.pairing_token = generate_token();
        let _ = save_config(&cfg);
    }
    if cfg.agent_name.trim().is_empty() {
        cfg.agent_name = "Clood".to_string();
    }
    if cfg.voice.trim().is_empty() {
        cfg.voice = "marin".to_string();
    }
    if cfg.orb_color.trim().is_empty() {
        cfg.orb_color = "#7c8cff".to_string();
    }
    if cfg.tailscale_port < 1024 {
        cfg.tailscale_port = 19480;
    }
    if cfg.api_port < 1024 {
        cfg.api_port = 19481;
    }
    cfg
}

pub fn save_config(cfg: &CloodConfig) -> Result<(), String> {
    let dir = data_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("create config dir failed: {e}"))?;
    let json = serde_json::to_string_pretty(cfg).map_err(|e| e.to_string())?;
    fs::write(config_path(), json).map_err(|e| format!("write config failed: {e}"))
}
