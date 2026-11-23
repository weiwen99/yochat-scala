"use strict";

// Cold-start and established-idle receipt for the real Electron + JVM path.
// Requires a built server assembly. The relay is not needed for launch.

const { spawn, execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const electron = path.join(__dirname, "node_modules", ".bin", "electron");
const basePort = Number(process.env.YOCHAT_PORT || 8791);
const relay = process.env.YOCHAT_RELAY || "127.0.0.1:7654";
const samples = 5;
const idleMs = 300_000;
const maxP50 = 2_000;
const maxP95 = 4_000;
const maxIdle = 800;
const userData = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-perf-"));
const receiptPath = process.env.YOCHAT_PERF_RECEIPT || path.join(process.cwd(), "tmp", "checks", `desktop-${Date.now()}.json`);
fs.mkdirSync(path.dirname(receiptPath), { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function electronArgs() {
  return [".", `--user-data-dir=${userData}`];
}

function spawnElectron(port, metric, hold) {
  return spawn(electron, electronArgs(), {
    cwd: __dirname,
    stdio: "ignore",
    env: {
      ...process.env,
      YOCHAT_PORT: String(port),
      YOCHAT_RELAY: relay,
      YOCHAT_START_EPOCH: String(Date.now()),
      YOCHAT_START_METRIC: metric,
      ...(hold ? { YOCHAT_START_METRIC_HOLD: "1" } : {})
    }
  });
}

function exitPromise(child) {
  return new Promise((resolve) => {
    child.once("exit", (code, signal) => resolve({ code, signal }));
    child.once("error", (error) => resolve({ code: -1, signal: String(error) }));
  });
}

async function waitForFile(file, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(file)) return;
    await sleep(25);
  }
  throw new Error(`timeout waiting for ${file}`);
}

async function measuredRun(index) {
  const port = basePort + index;
  const metric = path.join(userData, `metric-${index}.json`);
  fs.rmSync(metric, { force: true });
  const child = spawnElectron(port, metric, false);
  const exited = exitPromise(child);
  await Promise.race([
    waitForFile(metric, 30_000),
    exited.then(({ code, signal }) => {
      throw new Error(`electron exited before metric code=${code} signal=${signal}`);
    })
  ]);
  const result = JSON.parse(fs.readFileSync(metric, "utf8"));
  const exit = await exited;
  if (exit.code !== 0) throw new Error(`electron exit code=${exit.code} signal=${exit.signal}`);
  if (!Number.isFinite(result.elapsedMs)) throw new Error(`invalid metric: ${JSON.stringify(result)}`);
  return result.elapsedMs;
}

function processTree(rootPid) {
  const rows = execFileSync("ps", ["-axo", "pid=,ppid=,rss=,command="], { encoding: "utf8" })
    .split("\n")
    .map((line) => {
      const match = line.match(/^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$/);
      return match ? { pid: Number(match[1]), ppid: Number(match[2]), rss: Number(match[3]), command: match[4] } : null;
    })
    .filter(Boolean);
  const children = new Map();
  rows.forEach((row) => {
    if (!children.has(row.ppid)) children.set(row.ppid, []);
    children.get(row.ppid).push(row.pid);
  });
  const byPid = new Map(rows.map((row) => [row.pid, row]));
  const seen = new Set();
  const stack = [rootPid];
  let totalKb = 0;
  let hasJvm = false;
  while (stack.length > 0) {
    const pid = stack.pop();
    if (seen.has(pid)) continue;
    seen.add(pid);
    const row = byPid.get(pid);
    if (!row) continue;
    totalKb += row.rss;
    if (row.command.includes("java") || row.command.includes("yochat.server.Main")) hasJvm = true;
    stack.push(...(children.get(pid) || []));
  }
  return { mb: totalKb / 1024, hasJvm };
}

async function idleRun() {
  const port = basePort + samples + 1;
  const metric = path.join(userData, "metric-idle.json");
  fs.rmSync(metric, { force: true });
  const child = spawnElectron(port, metric, true);
  const exited = exitPromise(child);
  await Promise.race([
    waitForFile(metric, 30_000),
    exited.then(({ code, signal }) => {
      throw new Error(`electron exited before idle readiness code=${code} signal=${signal}`);
    })
  ]);
  await sleep(idleMs);
  if (child.exitCode !== null) throw new Error("electron exited during idle window");
  const tree = processTree(child.pid);
  if (!tree.hasJvm) throw new Error("idle process tree does not contain the owned JVM");
  child.kill("SIGTERM");
  await exited;
  return tree.mb;
}

async function main() {
  await measuredRun(0);
  const values = [];
  for (let index = 1; index <= samples; index += 1) values.push(await measuredRun(index));
  const sorted = [...values].sort((a, b) => a - b);
  const p50 = sorted[Math.max(0, Math.ceil(0.5 * sorted.length) - 1)];
  const p95 = sorted[Math.max(0, Math.ceil(0.95 * sorted.length) - 1)];
  const idleMb = await idleRun();
  const pass = p50 <= maxP50 && p95 <= maxP95 && idleMb <= maxIdle;
  const receipt = {
    command: "shell/perf-start.js",
    node: process.version,
    samplesMs: values,
    p50Ms: p50,
    p95Ms: p95,
    idleMb,
    idleSeconds: idleMs / 1000,
    limits: { p50Ms: maxP50, p95Ms: maxP95, idleMb: maxIdle },
    verdict: pass ? "PASS" : "FAIL"
  };
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
