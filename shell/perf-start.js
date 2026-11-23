"use strict";

// Cold-start and idle-memory receipt for the real Electron + JVM path.
// Requires a relay at YOCHAT_RELAY and a built server assembly.

const { spawn, execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const electron = path.join(__dirname, "node_modules", ".bin", "electron");
const port = Number(process.env.YOCHAT_PORT || 8791);
const relay = process.env.YOCHAT_RELAY || "127.0.0.1:7654";
const samples = Number(process.env.YOCHAT_SAMPLES || 5);
const idleMs = Number(process.env.YOCHAT_IDLE_MS || 8000);
const maxP50 = Number(process.env.YOCHAT_MAX_P50_MS || 2000);
const maxP95 = Number(process.env.YOCHAT_MAX_P95_MS || 4000);
const maxIdle = Number(process.env.YOCHAT_MAX_IDLE_MB || 800);
const userData = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-perf-"));
const receiptPath = process.env.YOCHAT_PERF_RECEIPT || path.join(process.cwd(), "tmp", "checks", `desktop-${Date.now()}.json`);
fs.mkdirSync(path.dirname(receiptPath), { recursive: true });

function spawnElectron(metric) {
  const env = {
    ...process.env,
    YOCHAT_PORT: String(port),
    YOCHAT_RELAY: relay,
    ...(metric ? { YOCHAT_START_METRIC: metric } : {})
  };
  return spawn(electron, [".", "--user-data-dir", userData], { cwd: __dirname, env, stdio: "ignore" });
}

function waitExit(child) {
  return new Promise((resolve, reject) => {
    child.once("exit", (code, signal) => (code === 0 ? resolve() : reject(new Error(`electron exit code=${code} signal=${signal}`))));
    child.once("error", reject);
  });
}

async function measuredRun(index) {
  const metric = path.join(userData, `metric-${index}.json`);
  fs.rmSync(metric, { force: true });
  await waitExit(spawnElectron(metric));
  return JSON.parse(fs.readFileSync(metric, "utf8")).elapsedMs;
}

function treeRssMb(rootPid) {
  const rows = execFileSync("ps", ["-axo", "pid=,ppid=,rss="], { encoding: "utf8" })
    .split("\n")
    .map((line) => line.trim().split(/\s+/).map(Number))
    .filter((row) => row.length === 3 && row.every(Number.isFinite));
  const children = new Map();
  rows.forEach(([pid, ppid]) => {
    if (!children.has(ppid)) children.set(ppid, []);
    children.get(ppid).push(pid);
  });
  const rss = new Map(rows.map(([pid, , value]) => [pid, value]));
  const seen = new Set();
  const stack = [rootPid];
  let total = 0;
  while (stack.length > 0) {
    const pid = stack.pop();
    if (seen.has(pid)) continue;
    seen.add(pid);
    total += rss.get(pid) || 0;
    stack.push(...(children.get(pid) || []));
  }
  return total / 1024;
}

async function idleRun() {
  const child = spawnElectron(null);
  await new Promise((resolve) => setTimeout(resolve, idleMs));
  const idleMb = treeRssMb(child.pid);
  child.kill("SIGTERM");
  await waitExit(child).catch(() => {});
  return idleMb;
}

async function main() {
  await measuredRun("warmup");
  const values = [];
  for (let index = 1; index <= samples; index += 1) values.push(await measuredRun(index));
  const sorted = [...values].sort((a, b) => a - b);
  const p50 = sorted[Math.max(0, Math.ceil(0.5 * sorted.length) - 1)];
  const p95 = sorted[Math.max(0, Math.ceil(0.95 * sorted.length) - 1)];
  const idleMb = await idleRun();
  const pass = p50 <= maxP50 && p95 <= maxP95 && idleMb <= maxIdle;
  const receipt = { command: "shell/perf-start.js", samplesMs: values, p50Ms: p50, p95Ms: p95, idleMb, limits: { p50Ms: maxP50, p95Ms: maxP95, idleMb: maxIdle }, verdict: pass ? "PASS" : "FAIL" };
  fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n");
  console.log(`COLD_START p50_ms=${p50.toFixed(3)} p95_ms=${p95.toFixed(3)} idle_mb=${idleMb.toFixed(1)} verdict=${receipt.verdict}`);
  console.log(`COLD_START_RECEIPT path=${receiptPath}`);
  fs.rmSync(userData, { recursive: true, force: true });
  if (!pass) process.exit(1);
}

main().catch((error) => {
  console.error(error.stack || String(error));
  process.exit(1);
});
