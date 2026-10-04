//! Standalone mock server for e2e runs and manual testing.
//!
//! Usage: `mock-server [--host 127.0.0.1] [--port 8080] [--scenario success]`
//! Also honours `MOCK_HOST`, `MOCK_PORT`, `MOCK_SCENARIO`.

use std::net::{IpAddr, SocketAddr};

use mock_server::{spawn_server_with, Scenario};

fn arg_or_env(args: &[String], flag: &str, env: &str, default: &str) -> String {
    args.windows(2)
        .find(|w| w[0] == flag)
        .map(|w| w[1].clone())
        .or_else(|| std::env::var(env).ok())
        .unwrap_or_else(|| default.to_owned())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--help" || a == "-h") {
        println!("mock-server [--host 127.0.0.1] [--port 8080] [--scenario success|error|empty|slow|malformed]");
        return Ok(());
    }
    let host: IpAddr = arg_or_env(&args, "--host", "MOCK_HOST", "127.0.0.1").parse()?;
    let port: u16 = arg_or_env(&args, "--port", "MOCK_PORT", "8080").parse()?;
    let scenario: Scenario = arg_or_env(&args, "--scenario", "MOCK_SCENARIO", "success").parse()?;

    let (handle, addr) = spawn_server_with(SocketAddr::new(host, port), scenario).await?;
    println!(
        "mock-server listening on http://{addr} (scenario: {})",
        scenario.as_str()
    );
    tokio::select! {
        _ = handle.wait() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
    Ok(())
}
