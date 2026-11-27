"use strict";

// Resolves which Electron binary a desktop gate runs, and with which arguments.
//
// macOS: the npm package in shell/node_modules works as-is.
// Linux: the npm distribution is built for FHS systems and cannot load on NixOS
// (it asks for libglib-2.0.so.0 and a long chain after it), so resolve
// YOCHAT_ELECTRON, then a `electron` on PATH, then nixpkgs.
//
// Also usable as a CLI: `node shell/electron-runtime.js <script> [args...]`
// spawns the resolved binary and forwards the exit code.

const { execFileSync, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const shellDir = __dirname;
const repoRoot = path.resolve(shellDir, "..");
const cachePath = path.join(repoRoot, "tmp", "checks", "electron-path");

// The .bin shim is the cross-platform entry npm installs: a script on POSIX that
// execs the platform binary under dist/. Playwright spawns it like any executable.
function npmElectron() {
  const binary = path.join(shellDir, "node_modules", ".bin", "electron");
  return fs.existsSync(binary) ? binary : null;
}

function runnable(binary) {
  const result = spawnSync(binary, ["--version"], { encoding: "utf8", timeout: 20_000 });
  return result.status === 0;
}

function nixpkgsElectron() {
  if (fs.existsSync(cachePath)) {
    const cached = fs.readFileSync(cachePath, "utf8").trim();
    if (cached && fs.existsSync(cached) && runnable(cached)) return cached;
  }
  try {
    const out = execFileSync("nix", ["build", "--no-link", "--print-out-paths", "nixpkgs#electron"], {
      encoding: "utf8",
      timeout: 300_000
    }).trim().split("\n").pop();
    if (!out) return null;
    const binary = path.join(out, "bin", "electron");
    if (!fs.existsSync(binary)) return null;
    fs.mkdirSync(path.dirname(cachePath), { recursive: true });
    fs.writeFileSync(cachePath, `${binary}\n`);
    return binary;
  } catch (_) {
    return null;
  }
}

function onPath() {
  try {
    const out = execFileSync("sh", ["-c", "command -v electron"], { encoding: "utf8" }).trim();
    return out || null;
  } catch (_) {
    return null;
  }
}

// Windows path separator differs; keep the Linux/macOS lookup simple and explicit.
function resolveElectron() {
  if (process.env.YOCHAT_ELECTRON) {
    const binary = process.env.YOCHAT_ELECTRON;
    if (!fs.existsSync(binary)) {
      throw new Error(`YOCHAT_ELECTRON does not exist: ${binary}`);
    }
    return { executablePath: binary, args: sandboxArgs("env"), source: "env" };
  }
  if (process.platform === "linux") {
    const fromPath = onPath();
    if (fromPath) return { executablePath: fromPath, args: sandboxArgs("path"), source: "path" };
    const fromNix = nixpkgsElectron();
    if (fromNix) return { executablePath: fromNix, args: sandboxArgs("nix"), source: "nix" };
    throw new Error(
      "no Electron binary for Linux: set YOCHAT_ELECTRON to an Electron >= 45 binary, " +
        "or install one on PATH. The npm package cannot load on NixOS (missing FHS libraries)."
    );
  }
  const npm = npmElectron();
  if (npm) return { executablePath: npm, args: sandboxArgs("npm"), source: "npm" };
  throw new Error("shell/node_modules/electron is missing; run npm ci in shell");
}

// A nix-store Electron has no setuid chrome-sandbox, so Chromium refuses to start
// unless the sandbox is disabled. That is a test-fixture concession, not a
// product default: packaged builds ship their own sandbox helper.
function sandboxArgs(source) {
  if (process.platform !== "linux") return [];
  if (source === "npm") return [];
  return ["--no-sandbox"];
}

function version(binary) {
  try {
    return execFileSync(binary, ["--version"], { encoding: "utf8", timeout: 20_000 }).trim();
  } catch (_) {
    return "unknown";
  }
}

module.exports = { resolveElectron, version };

if (require.main === module) {
  const [script, ...rest] = process.argv.slice(2);
  if (!script) {
    console.error("usage: node shell/electron-runtime.js <script> [args...]");
    process.exit(2);
  }
  let resolved;
  try {
    resolved = resolveElectron();
  } catch (error) {
    console.error(`electron-runtime: ${error.message}`);
    process.exit(2);
  }
  const child = spawnSync(resolved.executablePath, [...resolved.args, script, ...rest], {
    stdio: "inherit",
    env: process.env
  });
  if (child.error) {
    console.error(`electron-runtime: ${child.error.message}`);
    process.exit(2);
  }
  process.exit(child.status === null ? 1 : child.status);
}
