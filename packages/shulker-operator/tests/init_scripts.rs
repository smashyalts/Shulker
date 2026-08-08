//! Runs the POSIX-shell test suite for the init-container helpers.
//!
//! The helpers in `assets/shulker-fetch.sh` are what actually enforce the
//! `sha256` field on a `ResourceRef`, and they run inside the init container
//! where nothing else validates them. Exercising them from the Rust test suite
//! keeps that behaviour under CI rather than relying on the e2e run.

use std::path::PathBuf;
use std::process::Command;

fn asset_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("assets")
}

fn script(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join(name)
}

#[test]
fn shulker_fetch_helpers_behave() {
    let output = Command::new("sh")
        .arg(script("shulker-fetch-test.sh"))
        .arg(asset_dir())
        .output()
        .expect("a POSIX shell is available to run the init-script tests");

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    assert!(
        output.status.success(),
        "init script tests failed\n--- stdout ---\n{stdout}\n--- stderr ---\n{stderr}"
    );
    assert!(stdout.contains("ALL PASS"), "unexpected output:\n{stdout}");
}

#[test]
fn init_scripts_are_posix_clean() {
    for name in [
        "shulker-fetch.sh",
        "server-init-fs.sh",
        "proxy-init-fs.sh",
        "proxy-probe-readiness.sh",
    ] {
        let path = asset_dir().join(name);

        // `sh -n` parses without executing. These scripts previously relied on
        // bash-only syntax (`[ x == y ]`, `${var//a/b}`) that happens to work
        // under Alpine's BusyBox build and would break on any other base image.
        let output = Command::new("sh")
            .arg("-n")
            .arg(&path)
            .output()
            .expect("a POSIX shell is available");

        assert!(
            output.status.success(),
            "{name} is not valid POSIX sh:\n{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
}

#[test]
fn init_scripts_use_lf_line_endings() {
    // They are embedded verbatim into ConfigMaps with `include_str!` and then
    // executed on Linux; a CRLF checkout would ship broken scripts.
    for name in [
        "shulker-fetch.sh",
        "server-init-fs.sh",
        "proxy-init-fs.sh",
        "proxy-probe-readiness.sh",
    ] {
        let contents = std::fs::read(asset_dir().join(name)).expect("asset is readable");
        assert!(
            !contents.contains(&b'\r'),
            "{name} contains CR characters; check .gitattributes"
        );
    }
}

#[test]
fn init_scripts_do_not_trace_commands() {
    // `set -o xtrace` echoed every command, and plugin URLs can embed Maven
    // credentials (`https://user:pass@host/...`), which put those credentials
    // into the init container's logs.
    for name in ["server-init-fs.sh", "proxy-init-fs.sh", "shulker-fetch.sh"] {
        let contents = std::fs::read_to_string(asset_dir().join(name)).expect("asset is readable");
        assert!(
            !contents.contains("xtrace") && !contents.contains("set -x"),
            "{name} enables command tracing, which leaks credentialed URLs to logs"
        );
    }
}
