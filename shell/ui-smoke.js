"use strict";

// Real two-device web UI smoke: setup, safety gate, bidirectional 100 messages.
// Requires ./mill server.assembly and ./mill transport.assembly, plus Chrome.

const { execFileSync, spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");

const root = path.resolve(__dirname, "..");
const serverJar = process.env.YOCHAT_SERVER_JAR || path.join(root, "out", "server", "assembly.dest", "out.jar");
const relayJar = process.env.YOCHAT_RELAY_JAR || path.join(root, "out", "transport", "assembly.dest", "out.jar");
const java = process.env.YOCHAT_JAVA || "java";
const passphrase = "correct horse battery staple";
const receiptPath = path.join(root, "tmp", "checks", `ui-${Date.now()}.json`);
const children = [];
const started = Date.now();
let stage = "init";
fs.mkdirSync(path.dirname(receiptPath), { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function gitHead() {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
  } catch (_) {
    return "unknown";
  }
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

function startProcess(label, args) {
  const child = spawn(args[0], args.slice(1), { stdio: ["ignore", "pipe", "pipe"] });
  child.label = label;
  child.logBuffer = "";
  child.stdout.on("data", (data) => { child.logBuffer += data.toString(); });
  child.stderr.on("data", (data) => { child.logBuffer += data.toString(); });
  children.push(child);
  return child;
}

async function waitLog(child, pattern, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`${child.label} exited before readiness`);
    if (pattern.test(child.logBuffer)) return;
    await sleep(50);
  }
  throw new Error(`${child.label} readiness timeout`);
}

async function waitHttp(url, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2_000) });
      if (response.ok) return;
    } catch (_) {
      // Server not ready yet.
    }
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${url}`);
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  const exited = new Promise((resolve) => child.once("exit", resolve));
  child.kill("SIGTERM");
  await Promise.race([exited, sleep(5_000)]);
  if (child.exitCode === null) {
    child.kill("SIGKILL");
    await Promise.race([exited, sleep(2_000)]);
  }
}

async function unlock(page) {
  stage = "unlock";
  await page.locator(".unlock").waitFor();
  const passwords = page.locator(".unlock-form input[type=password]");
  await passwords.first().fill(passphrase);
  if (await passwords.count() > 1) await passwords.nth(1).fill(passphrase);
  await page.getByRole("button", { name: /创建并进入|解锁/ }).click();
  await page.locator(".shell").waitFor({ timeout: 30_000 });
}

async function waitRelayOnline(page) {
  stage = "relay-online";
  await page.waitForFunction(
    async () => {
      const response = await fetch("/api/state");
      if (!response.ok) return false;
      const state = await response.json();
      return state.relay && state.relay.online === true;
    },
    null,
    { timeout: 30_000 }
  );
}

async function issueInvitation(page, own, peer) {
  stage = "issue-invitation";
  await page.locator('button[aria-label="添加联系人"]').first().click();
  await page.locator(".modal").waitFor();
  const inputs = page.locator(".modal .dialog-body .form-grid input");
  await inputs.nth(0).fill("微信");
  await inputs.nth(1).fill(own);
  await inputs.nth(2).fill(peer);
  await page.getByRole("button", { name: "生成邀请" }).click();
  const code = page.locator(".modal .receipt textarea").first();
  await code.waitFor();
  return code.inputValue();
}

async function acceptInvitation(page, text, expected, own) {
  stage = "accept-open";
  await page.locator('button[aria-label="添加联系人"]').first().click();
  await page.locator(".modal").waitFor();
  stage = "accept-tab";
  await page.locator(".modal .tabs button").nth(1).click();
  stage = "accept-expected";
  const expectedInput = page.locator(".modal .dialog-body .field input").first();
  await expectedInput.fill(expected);
  stage = "accept-text";
  await page.locator(".modal textarea.tall").fill(text);
  stage = "accept-read";
  await page.getByRole("button", { name: "读取邀请" }).click();
  stage = "accept-preview";
  await page.locator(".modal .receipt.preview").waitFor();
  stage = "accept-own";
  const previewInputs = page.locator(".modal .receipt.preview .form-grid input");
  await previewInputs.nth(1).fill(own);
  stage = "accept-submit";
  await page.getByRole("button", { name: "接受并生成应答" }).click();
  stage = "accept-reply";
  await page.waitForTimeout(1_000);
}

async function closeModal(page) {
  stage = "close-modal";
  if (await page.locator(".modal").count() === 0) return;
  await page.mouse.click(10, 10);
  await page.locator(".modal").waitFor({ state: "detached", timeout: 5_000 }).catch(() => {});
}

async function openChat(page) {
  stage = "open-chat";
  if (await page.locator(".composer-input").count() === 0) {
    const open = page.getByRole("button", { name: "打开聊天" });
    if (await open.count() > 0) await open.first().click();
    else await page.locator(".conversation-row").first().click();
  }
  await page.locator(".composer-input").waitFor();
}

async function verifySafety(page) {
  stage = "verify-safety";
  const safety = page.locator(".ledger button").filter({ hasText: "我已当面核对" }).first();
  await safety.click();
  await page.locator(".safety-state.verified").waitFor();
}

async function checkSafetyCodes(a, b) {
  stage = "safety-codes";
  const codeA = (await a.locator(".safety-digits").first().innerText()).replace(/\s+/g, "");
  const codeB = (await b.locator(".safety-digits").first().innerText()).replace(/\s+/g, "");
  if (!/^\d{19,20}$/.test(codeA) || !/^\d{19,20}$/.test(codeB)) throw new Error("safety code shape");
  if (codeA === codeB) throw new Error("local safety segments did not differ");
}

async function assertSafetyClosed(a, b) {
  stage = "pre-safety-gate";
  const composer = a.locator(".composer-input");
  await composer.fill("pre-safety-ui");
  await composer.press("Enter");
  await sleep(500);
  if (await a.locator(".message.own").count() !== 0) throw new Error("A sent before safety");
  if (await b.locator(".message.peer").count() !== 0) throw new Error("B received before safety");
  await composer.fill("");
}

async function sendBodies(page, prefix, count) {
  stage = `send-${prefix}`;
  const composer = page.locator(".composer-input");
  for (let index = 1; index <= count; index += 1) {
    await composer.fill(`${prefix}-${index}`);
    await composer.press("Enter");
    await page.waitForFunction(() => document.querySelector(".composer-input")?.value === "");
  }
}

async function waitCount(page, selector, count, timeout = 60_000) {
  await page.waitForFunction(
    ({ selector: current, expected }) => document.querySelectorAll(current).length === expected,
    { selector, expected: count },
    { timeout }
  );
}

async function bubbleTexts(page, selector) {
  return page.locator(selector).allTextContents();
}

function assertSequence(actual, prefix, count) {
  const expected = Array.from({ length: count }, (_, index) => `${prefix}-${index + 1}`);
  if (actual.length !== count || actual.some((value, index) => value !== expected[index])) {
    throw new Error(`${prefix} rendered sequence mismatch (count=${actual.length})`);
  }
}

async function main() {
  if (!fs.existsSync(serverJar)) throw new Error(`missing server assembly: ${serverJar}`);
  if (!fs.existsSync(relayJar)) throw new Error(`missing relay assembly: ${relayJar}`);
  const relayPort = await freePort();
  const portA = await freePort();
  const portB = await freePort();
  const dirA = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-ui-a-"));
  const dirB = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-ui-b-"));
  let browser;
  let pageA = null;
  let pageB = null;
  let result = null;
  let failure = null;
  try {
    const relay = startProcess("relay", [java, "-jar", relayJar, String(relayPort)]);
    await waitLog(relay, /listening/i);
    const serverA = startProcess("A", [java, "-jar", serverJar, "--port", String(portA), "--data-dir", dirA, "--relay", `127.0.0.1:${relayPort}`, "--idle-timeout-minutes", "60"]);
    const serverB = startProcess("B", [java, "-jar", serverJar, "--port", String(portB), "--data-dir", dirB, "--relay", `127.0.0.1:${relayPort}`, "--idle-timeout-minutes", "60"]);
    await Promise.all([waitLog(serverA, /YoChat web:/), waitLog(serverB, /YoChat web:/)]);
    await Promise.all([waitHttp(`http://127.0.0.1:${portA}/api/session`), waitHttp(`http://127.0.0.1:${portB}/api/session`)]);

    stage = "launch-browser";
    browser = null;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        browser = await chromium.launch({ channel: "chrome", headless: true });
        break;
      } catch (error) {
        if (attempt === 3) throw error;
        await sleep(2_000);
      }
    }
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const a = await contextA.newPage();
    const b = await contextB.newPage();
    pageA = a;
    pageB = b;
    await Promise.all([a.goto(`http://127.0.0.1:${portA}/`), b.goto(`http://127.0.0.1:${portB}/`)]);
    await Promise.all([unlock(a), unlock(b)]);
    await Promise.all([waitRelayOnline(a), waitRelayOnline(b)]);

    const invitation = await issueInvitation(a, "@A", "@B");
    await acceptInvitation(b, invitation, "@A", "@B");
    await closeModal(a);
    await closeModal(b);
    stage = "wait-conversation-a";
    await a.locator(".conversation-row").first().waitFor({ timeout: 30_000 });
    stage = "wait-conversation-b";
    await b.locator(".conversation-row").first().waitFor({ timeout: 30_000 });
    await Promise.all([openChat(a), openChat(b)]);
    await checkSafetyCodes(a, b);
    await assertSafetyClosed(a, b);
    await Promise.all([verifySafety(a), verifySafety(b)]);
    await Promise.all([
      a.locator('button[aria-label="发送"]').isEnabled(),
      b.locator('button[aria-label="发送"]').isEnabled()
    ]).then((enabled) => {
      if (enabled.some((value) => !value)) throw new Error("composer did not open after safety");
    });

    await sendBodies(a, "ui", 100);
    await Promise.all([
      waitCount(a, ".message.own.delivered", 100),
      waitCount(b, ".message.peer", 100)
    ]);
    await sendBodies(b, "ui-b", 100);
    await Promise.all([
      waitCount(a, ".message.peer", 100),
      waitCount(b, ".message.own.delivered", 100)
    ]);

    assertSequence(await bubbleTexts(a, ".message.own .bubble"), "ui", 100);
    assertSequence(await bubbleTexts(b, ".message.peer .bubble"), "ui", 100);
    assertSequence(await bubbleTexts(a, ".message.peer .bubble"), "ui-b", 100);
    assertSequence(await bubbleTexts(b, ".message.own .bubble"), "ui-b", 100);
    result = { delivered: 100, received: 100, reciprocal: true };
  } catch (error) {
    failure = { stage, error: error.name || "Error", detail: stage === "init" || stage === "launch-browser" ? String(error.message || "").slice(0, 240) : undefined };
    if (pageA) await pageA.screenshot({ path: "/tmp/yochat-ui-a.png" }).catch(() => {});
    if (pageB) {
      await pageB.screenshot({ path: "/tmp/yochat-ui-b.png" }).catch(() => {});
      await pageB.content().then((html) => fs.writeFileSync("/tmp/yochat-ui-b.html", html)).catch(() => {});
    }
  } finally {
    if (browser) await browser.close().catch(() => {});
    for (const child of children.reverse()) await stopChild(child);
    fs.rmSync(dirA, { recursive: true, force: true });
    fs.rmSync(dirB, { recursive: true, force: true });
  }

  const receipt = {
    command: "shell/ui-smoke.js",
    commit: gitHead(),
    node: process.version,
    browser: "chrome",
    durationMs: Date.now() - started,
    serverJar,
    relayJar,
    result,
    failure,
    verdict: failure ? "FAIL" : "PASS"
  };
  fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n");
  if (failure) {
    console.error(`UI_SMOKE FAIL stage=${failure.stage} error=${failure.error}`);
    console.error(`UI_SMOKE_RECEIPT path=${receiptPath}`);
    process.exit(1);
  }
  console.log(`UI_SMOKE PASS delivered=100 received=100 reciprocal=1 duration_ms=${receipt.durationMs}`);
  console.log(`UI_SMOKE_RECEIPT path=${receiptPath}`);
}

main().catch((error) => {
  console.error(`UI_SMOKE FAIL stage=${stage} error=${error.name || "Error"}`);
  process.exit(1);
});
