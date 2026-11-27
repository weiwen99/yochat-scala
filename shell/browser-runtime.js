"use strict";

// Resolves which Chrome a Playwright gate drives, and with which arguments.
//
// macOS finds Chrome through Playwright's `channel: "chrome"`. Other systems
// have no such registry entry: NixOS and most distributions install Chrome
// outside /opt, so resolve the binary with `command -v` instead. The probe is a
// parameter so cross-host gates can run the same lookup on a remote host over
// ssh instead of inventing a second mechanism.

const { execFileSync } = require("node:child_process");

const CANDIDATES = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"];

function localProbe(command) {
  try {
    return execFileSync("sh", ["-c", `command -v ${command}`], { encoding: "utf8" }).trim();
  } catch (_) {
    return "";
  }
}

function resolveChrome(probe = localProbe) {
  for (const candidate of CANDIDATES) {
    let found = "";
    try {
      found = probe(candidate) || "";
    } catch (_) {
      // A remote probe that exits non-zero (no such binary) is a miss, not an
      // error: keep looking at the next candidate.
      found = "";
    }
    if (found) return found;
  }
  return "";
}

// Chromium refuses to start its sandbox without a setuid helper (absent in a
// nix store) and needs shared memory that some containers do not provide.
function chromeArgs() {
  if (process.platform === "linux") return ["--no-sandbox", "--disable-dev-shm-usage"];
  return [];
}

function chromeLaunchOptions({ headless = true, probe } = {}) {
  if (process.env.YOCHAT_CHROME_PATH) {
    return { executablePath: process.env.YOCHAT_CHROME_PATH, headless, args: chromeArgs() };
  }
  if (process.platform === "darwin") {
    return { channel: "chrome", headless };
  }
  const binary = resolveChrome(probe);
  if (!binary) {
    throw new Error(
      "no Chrome found: set YOCHAT_CHROME_PATH, or install google-chrome/chromium on PATH"
    );
  }
  return { executablePath: binary, headless, args: chromeArgs() };
}

module.exports = { chromeLaunchOptions, resolveChrome, chromeArgs, localProbe };
