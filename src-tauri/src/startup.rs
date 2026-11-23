/// Manages launching Clood automatically when Windows boots (minimized to system tray).

#[cfg(windows)]
pub fn set_launch_at_startup(enabled: bool, start_in_tray: bool) -> Result<(), String> {
    use winreg::enums::*;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (run_key, _) = hkcu
        .create_subkey(r"Software\Microsoft\Windows\CurrentVersion\Run")
        .map_err(|e| format!("failed to open Windows startup registry key: {e}"))?;

    if enabled {
        let exe = std::env::current_exe()
            .map_err(|e| format!("failed to resolve Clood executable path: {e}"))?;
        let exe_str = exe.to_string_lossy();
        let value = if start_in_tray {
            format!("\"{exe_str}\" --minimized")
        } else {
            format!("\"{exe_str}\"")
        };
        run_key
            .set_value("Clood", &value)
            .map_err(|e| format!("failed to register startup entry: {e}"))?;
    } else {
        match run_key.delete_value("Clood") {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(format!("failed to remove startup entry: {e}")),
        }
    }

    Ok(())
}

#[cfg(not(windows))]
pub fn set_launch_at_startup(_enabled: bool, _start_in_tray: bool) -> Result<(), String> {
    Ok(())
}
