"use strict";

// Cross-host S1 acceptance: m1 and w7 each own one server/database; one relay is on m1.
// Browser A runs on m1, browser B runs on w7 (Chromium CDP over an SSH tunnel); commands are
// driven by Playwright, so the user-facing flow is UI-only. Requires SSH alias "w7".
//
// Usage: node shell/cross-host-smoke.js
// Env: YOCHAT_W7_HOST (default w7), YOCHAT_M1_IP (default tailscale ip -4), YOCHAT_JAVA.

const { execFileSync, spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");

const {
  relayJar,
  root,
  freePort,
  startProcess,
  children,
  waitLog,
  waitHttp,
  stopChild,
  gitHead,
  sleep,
  unlock,
  waitRelayOnline,
  issueInvitation,
  acceptInvitation,
  closeModal,
  openChat,
  checkSafetyCodes,
  assertSafetyClosed,
  verifySafety,
  sendBodies,
  waitCount,
  bubbleTexts,
  assertSequence
} = require("./ui-smoke");

const w7 = process.env.YOCHAT_W7_HOST || "w7";
const java = process.env.YOCHAT_JAVA || "java";
const receiptPath = path.join(root, "tmp", "checks", `cross-host-${Date.now()}.json`);
const started = Date.now();
let stage = "init";
let remoteServer = null;
let remoteChrome = null;
let remoteJar = null;
let remoteData = null;
let dataA = null;
let tunnel = null;
let browserA = null;
let browserB = null;
fs.mkdirSync(path.dirname(receiptPath), { recursive: true });

const sleepMs = sleep;
const ssh = (args, opts = {}) => execFileSync("ssh", ["-o", "BatchMode=yes", w7, ...args], { encoding: "utf8", ...opts });
const scp = (local, remote) => execFileSync("scp", [local, `${w7}:${remote}`], { encoding: "utf8" });

function localJar() {
  return path.join(root, "out", "server", "assembly.dest", "out.jar");
}

function startLocalServer(port, dataDir, relay) {
  return startProcess("A-server", [
    java, "-jar", localJar(),
    "--port", String(port),
    "--data-dir", dataDir,
    "--relay", relay,
    "--idle-timeout-minutes", "60"
  ]);
}

function startRemoteServer(port, dataDir, relay) {
  const pidFile = `${dataDir}/server.pid`;
  const command = `mkdir -p ${dataDir}; echo $$ > ${pidFile}; exec ${java} -jar ${remoteJar} --port ${port} --data-dir ${dataDir} --relay ${relay} --idle-timeout-minutes 60`;
  const child = spawn("ssh", ["-o", "BatchMode=yes", w7, command], { stdio: ["ignore", "pipe", "pipe"] });
  child.label = "B-server";
  child.logBuffer = "";
  child.stdout.on("data", (data) => { child.logBuffer += data.toString(); });
  child.stderr.on("data", (data) => { child.logBuffer += data.toString(); });
  children.push(child);
  return child;
}

async function stopRemoteServer() {
  if (!remoteData) return;
  const pidFile = `${remoteData}/server.pid`;
  try { ssh([`if [ -f ${pidFile} ]; then kill $(cat ${pidFile}) 2>/dev/null || true; fi`]); } catch (_) {}
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const alive = await ssh([`if [ -f ${pidFile} ] && kill -0 $(cat ${pidFile}) 2>/dev/null; then echo 1; else echo 0; fi`]);
      if (alive.trim() === "0") break;
    } catch (_) {}
    await sleepMs(100);
  }
  if (remoteServer) await stopChild(remoteServer);
  remoteServer = null;
}

async function startRemoteChrome() {
  const binary = (await ssh(["command -v google-chrome"])).trim();
  if (!binary) throw new Error("w7 has no google-chrome");
  const profile = `${remoteData}/chrome-profile`;
  const command = `mkdir -p ${profile}; exec ${binary} --headless=new --no-sandbox --disable-dev-shm-usage --remote-debugging-port=9222 --user-data-dir=${profile} about:blank`;
  const child = spawn("ssh", ["-o", "BatchMode=yes", w7, command], { stdio: ["ignore", "pipe", "pipe"] });
  child.label = "B-chrome";
  child.logBuffer = "";
  child.stdout.on("data", (data) => { child.logBuffer += data.toString(); });
  child.stderr.on("data", (data) => { child.logBuffer += data.toString(); });
  remoteChrome = child;
  children.push(child);
}

async function waitRelayState(page, expected, timeout = 30_000) {
  await page.waitForFunction(
    async (online) => {
      const response = await fetch("/api/state");
      if (!response.ok) return false;
      const state = await response.json();
      return Boolean(state.relay && state.relay.online === online);
    },
    expected,
    { timeout }
  );
}

async function restartServer(page, label, starter) {
  stage = `restart-${label}`;
  await starter();
  await page.reload();
  await unlock(page);
  await waitRelayOnline(page);
  await openChat(page);
}

const shellQuote = (value) => `'${String(value).replace(/'/g, "'\\''")}'`;

function remoteFreePort() {
  const script = "import socket;s=socket.socket();s.bind(('127.0.0.1',0));p=s.getsockname()[1];s.close();print(p)";
  return Number(ssh([`python3 -c ${shellQuote(script)}`]).trim());
}

function localDeliveredCount() {
  const output = execFileSync("sqlite3", [
    path.join(dataA, "data.db"),
    "select count(*) from messages where state = 5"
  ], { encoding: "utf8" });
  return Number(output.trim());
}

function remoteReceivedCount() {
  const script = "import sqlite3,sys;print(sqlite3.connect(sys.argv[1]).execute('select count(*) from messages where state = 3').fetchone()[0])";
  const encoded = Buffer.from(script, "utf8").toString("base64");
  const command = `python3 -c ${shellQuote(`import base64;exec(base64.b64decode("${encoded}"))`)} ${shellQuote(`${remoteData}/data.db`)}`;
  return Number(ssh([command]).trim());
}

async function waitLocalDelivered(target, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (localDeliveredCount() >= target) return;
    await sleepMs(25);
  }
  throw new Error(`A delivered timeout: target=${target} current=${localDeliveredCount()}`);
}

async function postMessage(port, conversationId, body) {
  const response = await fetch(`http://127.0.0.1:${port}/api/conversations/${conversationId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ body })
  });
  if (!response.ok) throw new Error(`send returned ${response.status}: ${await response.text()}`);
}

async function runCrossHostRate(port, conversationId) {
  stage = "throughput";
  const messages = Number(process.env.YOCHAT_CROSS_HOST_RATE_MESSAGES || 1_000);
  if (!Number.isInteger(messages) || messages <= 0) throw new Error(`invalid cross-host rate messages: ${messages}`);
  const warmup = 200;
  const window = 64;
  const backlog = 128;
  const bodyBytes = 128;
  const payload = (prefix, number) => {
    const head = `${prefix}-${String(number).padStart(5, "0")}`;
    return head + "x".repeat(bodyBytes - head.length);
  };
  const sendBatch = async (bodies) => {
    await Promise.all(bodies.map((body) => postMessage(port, conversationId, body)));
  };
  const sendAll = async (bodies, aBase, pace) => {
    let sent = 0;
    for (let offset = 0; offset < bodies.length; offset += window) {
      const batch = bodies.slice(offset, offset + window);
      await sendBatch(batch);
      sent += batch.length;
      if (pace) await waitLocalDelivered(aBase + Math.max(0, sent - backlog));
    }
  };

  console.log(`CROSS_HOST_RATE START messages=${messages} window=${window} body_bytes=${bodyBytes}`);
  const warmupBodies = Array.from({ length: warmup }, (_, index) => payload("crw", index + 1));
  const warmupStart = localDeliveredCount();
  await sendAll(warmupBodies, warmupStart, false);
  await waitLocalDelivered(warmupStart + warmup);
  console.log(`CROSS_HOST_RATE WARMUP aDelivered=${warmupStart + warmup}`);
  const aBase = localDeliveredCount();
  const bBase = remoteReceivedCount();
  if (aBase !== bBase) throw new Error(`cross-host baseline mismatch A=${aBase} B=${bBase}`);

  const bodies = Array.from({ length: messages }, (_, index) => payload("crt", index + 1));
  const startedAt = Date.now();
  await sendAll(bodies, aBase, true);
  await waitLocalDelivered(aBase + messages);
  const ackMs = Date.now() - startedAt;
  const aCount = localDeliveredCount();
  const bCount = remoteReceivedCount();
  const expected = aBase + messages;
  if (aCount !== expected) throw new Error(`cross-host A delivered=${aCount}, expected=${expected}`);
  if (bCount !== expected) throw new Error(`cross-host B received=${bCount}, expected=${expected}`);
  const rate = messages * 1000 / ackMs;
  console.log(`CROSS_HOST_RATE PASS messages=${messages} window=${window} body_bytes=${bodyBytes} ack_ms=${ackMs} rate_msg_s=${rate.toFixed(1)} bCount=${bCount} aDelivered=${aCount}`);
  return { direction: "A->B", hosts: { A: "m1", B: w7, relay: "m1" }, messages, warmup, window, backlog, bodyBytes, ackMs, rateMsgS: Number(rate.toFixed(1)), bCount, aDelivered: aCount };
}

async function main() {
  const m1ip = process.env.YOCHAT_M1_IP || execFileSync("tailscale", ["ip", "-4"], { encoding: "utf8" }).trim().split("\n")[0];
  if (!m1ip) throw new Error("missing m1 tailscale IPv4");
  const relayPort = await freePort();
  const portA1 = await freePort();
  const portA2 = await freePort();
  const portB1 = remoteFreePort();
  const portB2 = remoteFreePort();
  dataA = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-cross-a-"));
  remoteData = `/tmp/yochat-cross-b-${Date.now()}`;
  remoteJar = `/tmp/yochat-cross-server-${Date.now()}.jar`;
  scp(localJar(), remoteJar);
  fs.rmSync(dataA, { recursive: true, force: true });
  fs.mkdirSync(dataA, { recursive: true });
  const relay = startProcess("relay", [java, "-jar", relayJar, String(relayPort)]);
  await waitLog(relay, /listening/i);
  let serverA = startLocalServer(portA1, dataA, `127.0.0.1:${relayPort}`);
  await waitLog(serverA, /YoChat web:/);
  await waitHttp(`http://127.0.0.1:${portA1}/api/session`);
  remoteServer = startRemoteServer(portB1, remoteData, `${m1ip}:${relayPort}`);
  try { await waitLog(remoteServer, /YoChat web:/); }
  catch (_) { throw new Error(`B server start failed: ${remoteServer.logBuffer.slice(-500)}`); }

  stage = "launch-browser-a";
  browserA = await chromium.launch({ channel: "chrome", headless: true });
  const contextA = await browserA.newContext();
  const pageA = await contextA.newPage();
  await pageA.setViewportSize({ width: 1280, height: 860 });
  await pageA.goto(`http://127.0.0.1:${portA1}/`);

  stage = "launch-browser-b";
  tunnel = spawn("ssh", ["-N", "-L", "19222:127.0.0.1:9222", "-o", "BatchMode=yes", w7], { stdio: "ignore" });
  await sleepMs(500);
  await startRemoteChrome();
  await waitHttp("http://127.0.0.1:19222/json/version");
  browserB = await chromium.connectOverCDP("http://127.0.0.1:19222");
  const contextB = browserB.contexts()[0] || (await browserB.newContext());
  const pageB = await contextB.newPage();
  await pageB.setViewportSize({ width: 1280, height: 860 });
  await pageB.goto(`http://127.0.0.1:${portB1}/`);

  stage = "unlock";
  await Promise.all([unlock(pageA), unlock(pageB)]);
  await Promise.all([waitRelayOnline(pageA), waitRelayOnline(pageB)]);

  stage = "pair";
  const invitation = await issueInvitation(pageA, "@A", "@B");
  await acceptInvitation(pageB, invitation, "@A", "@B");
  await closeModal(pageA);
  await closeModal(pageB);
  await pageA.locator(".conversation-row").first().waitFor({ timeout: 30_000 });
  await pageB.locator(".conversation-row").first().waitFor({ timeout: 30_000 });
  await Promise.all([openChat(pageA), openChat(pageB)]);
  await checkSafetyCodes(pageA, pageB);
  await assertSafetyClosed(pageA, pageB);
  await Promise.all([verifySafety(pageA), verifySafety(pageB)]);

  stage = "messages-initial";
  await sendBodies(pageA, "xhost", 100);
  await Promise.all([waitCount(pageA, ".message.own.delivered", 100), waitCount(pageB, ".message.peer", 100)]);
  await sendBodies(pageB, "xhost-b", 100);
  await Promise.all([waitCount(pageA, ".message.peer", 100), waitCount(pageB, ".message.own.delivered", 100)]);

  assertSequence(await bubbleTexts(pageA, ".message.own .bubble"), "xhost", 100);
  assertSequence(await bubbleTexts(pageB, ".message.peer .bubble"), "xhost", 100);
  assertSequence(await bubbleTexts(pageA, ".message.peer .bubble"), "xhost-b", 100);
  assertSequence(await bubbleTexts(pageB, ".message.own .bubble"), "xhost-b", 100);

  stage = "relay-outage";
  await stopChild(relay);
  await Promise.all([waitRelayState(pageA, false), waitRelayState(pageB, false)]);
  stage = "relay-reconnect";
  const relay2 = startProcess("relay2", [java, "-jar", relayJar, String(relayPort)]);
  await waitLog(relay2, /listening/i);
  await Promise.all([waitRelayState(pageA, true), waitRelayState(pageB, true)]);
  await sendBodies(pageA, "net", 1);
  await Promise.all([waitCount(pageA, ".message.own.delivered", 101), waitCount(pageB, ".message.peer", 101)]);

  stage = "restart-b";
  await stopRemoteServer();
  remoteServer = startRemoteServer(portB2, remoteData, `${m1ip}:${relayPort}`);
  try { await waitLog(remoteServer, /YoChat web:/); }
  catch (_) { throw new Error(`B server restart failed: ${remoteServer.logBuffer.slice(-500)}`); }
  await pageB.goto(`http://127.0.0.1:${portB2}/`);
  await unlock(pageB);
  await waitRelayOnline(pageB);
  await openChat(pageB);
  await Promise.all([waitCount(pageB, ".message.own.delivered", 100), waitCount(pageB, ".message.peer", 101)]);
  await sendBodies(pageB, "b-restart", 1);
  await waitCount(pageA, ".message.peer", 101);

  stage = "restart-a";
  await stopChild(serverA);
  serverA = startLocalServer(portA2, dataA, `127.0.0.1:${relayPort}`);
  await waitLog(serverA, /YoChat web:/);
  await pageA.goto(`http://127.0.0.1:${portA2}/`);
  await unlock(pageA);
  await waitRelayOnline(pageA);
  await openChat(pageA);
  await Promise.all([waitCount(pageA, ".message.own.delivered", 101), waitCount(pageA, ".message.peer", 101)]);
  await sendBodies(pageA, "a-restart", 1);
  await Promise.all([waitCount(pageA, ".message.own.delivered", 102), waitCount(pageB, ".message.peer", 102)]);

  const stateResponse = await fetch(`http://127.0.0.1:${portA2}/api/state`);
  if (!stateResponse.ok) throw new Error(`A state returned ${stateResponse.status}`);
  const state = await stateResponse.json();
  const conversationId = state.conversations?.[0]?.id;
  if (!conversationId) throw new Error("A conversation missing before throughput");
  const throughput = await runCrossHostRate(portA2, conversationId);

  const receipt = {
    command: "shell/cross-host-smoke.js",
    commit: gitHead(),
    node: process.version,
    hosts: { A: "m1", B: w7, relay: "m1" },
    relayPort,
    ports: { a1: portA1, b1: portB1, b2: portB2, a2: portA2 },
    result: { delivered: 100, received: 100, relayOutage: true, restarted: ["B", "A"] },
    throughput,
    failure: null,
    verdict: "PASS"
  };
  fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n");
  console.log(`CROSS_HOST PASS delivered=100 received=100 relay_outage=1 restarted=B,A duration_ms=${Date.now() - started}`);
  console.log(`CROSS_HOST_RATE_RECEIPT path=${receiptPath}`);
  console.log(`CROSS_HOST_RECEIPT path=${receiptPath}`);
}

let failed = null;
main()
  .catch((error) => {
    failed = { stage, error: error.name || "Error", detail: String(error.message || "").slice(0, 240) };
  })
  .finally(async () => {
    if (browserA) await browserA.close().catch(() => {});
    if (browserB) await browserB.close().catch(() => {});
    for (const child of children.reverse()) await stopChild(child);
    if (tunnel) await stopChild(tunnel);
    await stopRemoteServer();
    if (remoteChrome) {
      await stopChild(remoteChrome);
      try { ssh(["pkill -f 'remote-debugging-port=9222' 2>/dev/null || true"]); } catch (_) {}
    }
    fs.rmSync(dataA, { recursive: true, force: true });
    if (remoteData) { try { ssh([`rm -rf ${remoteData}`]); } catch (_) {} }
    if (remoteJar) { try { ssh([`rm -f ${remoteJar}`]); } catch (_) {} }
    if (failed) {
      const receipt = { command: "shell/cross-host-smoke.js", commit: gitHead(), hosts: { A: "m1", B: w7 }, failure: failed, verdict: "FAIL" };
      fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n");
      console.error(`CROSS_HOST FAIL stage=${failed.stage} error=${failed.error}`);
      console.error(`CROSS_HOST_RECEIPT path=${receiptPath}`);
      process.exit(1);
    }
    process.exit(0);
  });
