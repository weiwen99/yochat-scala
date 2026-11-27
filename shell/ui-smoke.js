"use strict";

// Real two-device web UI smoke: setup, safety gate, bidirectional 100 messages.
// Requires ./mill server.assembly and ./mill transport.assembly, plus Chrome.

const { execFileSync, spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { chromium } = require("playwright");
const { chromeLaunchOptions } = require("./browser-runtime");

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
  const close = page.locator('.modal button[aria-label="关闭"]').first();
  if (await close.count() > 0) await close.click({ force: true }).catch(() => {});
  if (await page.locator(".modal").count() > 0) {
    await page.keyboard.press("Escape");
    await page.locator(".modal").waitFor({ state: "detached", timeout: 5_000 }).catch(() => {});
  }
  if (await page.locator(".modal").count() > 0) {
    await page.mouse.click(10, 10);
    await page.locator(".modal").waitFor({ state: "detached", timeout: 5_000 }).catch(() => {});
  }
}

async function checkStatusBanners(browser, url) {
  stage = "status-banners";
  const context = await browser.newContext();
  try {
    const loadError = await context.newPage();
    await loadError.route("**/api/session", (route) => route.abort());
    await loadError.goto(url);
    const errorBanner = loadError.locator(".status-banner.error").first();
    await errorBanner.waitFor({ timeout: 10_000 });
    const errorText = await errorBanner.innerText();
    if (!errorText.includes("本机服务暂时不可用")) throw new Error(`load error banner mismatch: ${errorText}`);
    await loadError.close();

    const envelope = await context.newPage();
    await envelope.route("**/api/session", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ error: "runtime-failed:test" })
      })
    );
    await envelope.goto(url);
    const envelopeBanner = envelope.locator(".status-banner.error").first();
    await envelopeBanner.waitFor({ timeout: 10_000 });
    const envelopeText = await envelopeBanner.innerText();
    if (!envelopeText.includes("无法恢复")) throw new Error(`error envelope banner mismatch: ${envelopeText}`);
    await envelope.close();

    const statePage = await context.newPage();
    await statePage.route("**/api/session", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ unlocked: false, instanceExists: true, fatal: "test-fatal", notification: "queue-full" })
      })
    );
    await statePage.goto(url);
    const fatal = statePage.locator(".status-banner.fatal").first();
    const notice = statePage.locator(".status-banner.notice").first();
    await fatal.waitFor({ timeout: 10_000 });
    await notice.waitFor({ timeout: 10_000 });
    const fatalText = await fatal.innerText();
    const noticeText = await notice.innerText();
    if (!fatalText.includes("无法恢复")) throw new Error(`fatal banner mismatch: ${fatalText}`);
    if (!noticeText.includes("待发消息已满")) throw new Error(`notice banner mismatch: ${noticeText}`);
    await statePage.close();

    const pushPage = await context.newPage();
    let sessionCalls = 0;
    await pushPage.addInitScript(() => {
      class FakeWebSocket {
        constructor() {
          window.__fakeSocket = this;
          setTimeout(() => {
            if (this.onopen) this.onopen();
          }, 0);
        }
        send() {}
        close() {
          if (this.onclose) this.onclose();
        }
      }
      Object.defineProperty(window, "WebSocket", { value: FakeWebSocket, configurable: true });
    });
    await pushPage.route("**/api/session", (route) => {
      sessionCalls += 1;
      const body =
        sessionCalls === 1
          ? { unlocked: true, instanceExists: true, fatal: null, notification: null }
          : sessionCalls === 2
            ? { unlocked: true, instanceExists: true, fatal: null, notification: null }
            : { unlocked: true, instanceExists: true, fatal: "test-fatal", notification: null };
      if (sessionCalls === 1) {
        return new Promise((resolve) => {
          setTimeout(() => resolve(route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) })), 300);
        });
      }
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    });
    await pushPage.goto(url);
    await pushPage.locator(".shell").waitFor({ timeout: 10_000 });
    await pushPage.evaluate(() => window.__fakeSocket.onmessage({ data: JSON.stringify({ type: "state" }) }));
    const pushedFatal = pushPage.locator(".status-banner.fatal").first();
    await pushedFatal.waitFor({ timeout: 10_000 });
    await pushPage.waitForTimeout(500);
    const pushedText = await pushedFatal.innerText();
    if (!pushedText.includes("无法恢复")) throw new Error(`pushed fatal banner mismatch: ${pushedText}`);
    await pushPage.close();
  } finally {
    await context.close();
  }
}

async function checkModalKeyboard(page) {
  stage = "modal-keyboard";
  const trigger = page.locator('button[aria-label="添加联系人"]').first();
  await trigger.focus();
  await page.keyboard.press("Enter");
  const dialog = page.locator('.modal[role="dialog"][aria-modal="true"]');
  await dialog.waitFor({ timeout: 5_000 });
  const focusInside = await page.evaluate(() => !!document.activeElement?.closest?.(".modal"));
  if (!focusInside) throw new Error("modal did not receive focus");
  for (let index = 0; index < 20; index += 1) {
    await page.keyboard.press("Tab");
    const inside = await page.evaluate(() => !!document.activeElement?.closest?.(".modal"));
    if (!inside) throw new Error("focus escaped the modal");
  }

  const pattern = "**/api/invitations";
  const handler = (route) => {
    if (route.request().method() !== "POST") return route.continue();
    return new Promise((resolve) => {
      setTimeout(
        () =>
          resolve(
            route.fulfill({
              status: 400,
              contentType: "application/json",
              body: JSON.stringify({ error: "bad-request:test" })
            })
          ),
        500
      );
    });
  };
  await page.route(pattern, handler);
  try {
    const inputs = page.locator(".modal .dialog-body .form-grid input");
    await inputs.nth(0).fill("微信");
    await inputs.nth(1).fill("@A");
    await page.getByRole("button", { name: "生成邀请" }).click();
    await page.getByRole("button", { name: "正在生成…" }).waitFor({ timeout: 5_000 });
    await page.keyboard.press("Escape");
    await page.locator(".modal").waitFor({ state: "detached", timeout: 5_000 });
    await page.waitForTimeout(600);
  } finally {
    await page.unroute(pattern, handler);
  }
  const restored = await page.evaluate(() => document.activeElement?.getAttribute("aria-label") === "添加联系人");
  if (!restored) throw new Error("modal did not restore focus");
}

async function checkDisclosure(page) {
  stage = "disclosure";
  await page.locator('button[aria-label="添加联系人"]').first().click();
  await page.locator(".modal").waitFor({ timeout: 5_000 });
  const items = page.locator(".disclosure-list li");
  if ((await items.count()) < 5) throw new Error("disclosure list is short");
  const text = await page.locator(".disclosure-body").innerText();
  for (const needle of ["中继能看到", "公网地址", "拉黑", "在线状态", "中转地址"]) {
    if (!text.includes(needle)) throw new Error(`disclosure missing: ${needle}`);
  }
  await page.getByRole("button", { name: "我已了解" }).click();
  await page.locator(".disclosure-body").waitFor({ state: "detached", timeout: 5_000 });
  await page.keyboard.press("Escape");
  await page.locator(".modal").waitFor({ state: "detached", timeout: 5_000 });
}

async function checkKeyboardJourney(page) {
  stage = "keyboard-journey";
  const contacts = page.locator('button[aria-label="联系人"]').first();
  await contacts.focus();
  await page.keyboard.press("Enter");
  await page.locator(".contacts-panel").waitFor({ timeout: 10_000 });
  let reached = false;
  const seen = [];
  for (let index = 0; index < 40; index += 1) {
    await page.keyboard.press("Tab");
    const label = await page.evaluate(() => (document.activeElement?.innerText || "").trim().slice(0, 24));
    seen.push(label);
    if (label.includes("打开聊天")) {
      reached = true;
      break;
    }
  }
  if (!reached) throw new Error(`keyboard could not reach 打开聊天: ${seen.join(" | ")}`);
  await page.keyboard.press("Enter");
  await page.locator(".composer-input").waitFor({ timeout: 10_000 });
  if ((await page.locator(".conversation-row.on").count()) !== 1) {
    throw new Error("keyboard switch did not select the conversation");
  }
  const route = await page.locator('.head-route[data-route="relay"]').innerText();
  if (!route.includes("经中继")) throw new Error(`route text missing 经中继: ${route}`);
  const composer = page.locator(".composer-input");
  await composer.focus();
  await composer.fill("keyboard-journey");
  await composer.press("Enter");
  await page.waitForFunction(() => document.querySelector(".composer-input")?.value === "");
}

async function checkSearch(page) {
  stage = "search";
  const input = page.locator(".search-input");
  await input.fill("ui-42");
  const hit = page.locator(".search-hit").first();
  await hit.waitFor({ timeout: 15_000 });
  const text = await hit.innerText();
  if (!text.includes("ui-42")) throw new Error(`search hit mismatch: ${text}`);
  await hit.click();
  await page.locator(".composer-input").waitFor({ timeout: 10_000 });
  if ((await page.locator(".conversation-row.on").count()) !== 1) {
    throw new Error("search click did not select the conversation");
  }
  if ((await input.inputValue()) !== "") throw new Error("search query was not cleared after selecting a hit");
}

async function checkQueueFullFeedback(page) {
  stage = "queue-full-feedback";
  const pattern = "**/api/conversations/*/messages";
  let posts = 0;
  const handler = (route) => {
    if (route.request().method() !== "POST") return route.continue();
    posts += 1;
    if (posts === 1) {
      return new Promise((resolve) => {
        setTimeout(
          () => resolve(route.fulfill({ status: 200, contentType: "application/json", body: "{}" })),
          500
        );
      });
    }
    return route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({ error: "send-refused:queue-full" })
    });
  };
  await page.route(pattern, handler);
  try {
    const composer = page.locator(".composer-input");
    await composer.fill("queue-full-a");
    await composer.press("Enter");
    await composer.fill("queue-full-b");
    await composer.press("Enter");
    const toast = page.locator(".toast.error").first();
    await toast.waitFor({ timeout: 10_000 });
    const toastText = await toast.innerText();
    if (!toastText.includes("待发消息已满")) throw new Error(`queue-full toast mismatch: ${toastText}`);
    await composer.fill("queue-full-a");
    await page.waitForTimeout(1_200);
    const value = await composer.inputValue();
    if (value !== "queue-full-a") throw new Error(`delayed success cleared the newer revision: ${value}`);
    await composer.fill("");
  } finally {
    await page.unroute(pattern, handler);
  }
}

async function checkElectronExitReason(browser, url, server) {
  stage = "electron-exit-reason";
  const context = await browser.newContext();
  await context.addInitScript(() => {
    window.yochat = {
      isElectron: true,
      onServerExited(callback) {
        window.__serverExited = callback;
      },
      notify() {},
      keychainAvailable() {
        return Promise.resolve(false);
      },
      loadPassphrase() {
        return Promise.resolve(null);
      },
      savePassphrase() {
        return Promise.resolve(false);
      },
      clearPassphrase() {
        return Promise.resolve(true);
      }
    };
    class FakeWebSocket {
      constructor() {
        window.__fakeSocket = this;
        setTimeout(() => {
          if (this.onopen) this.onopen();
        }, 0);
      }
      send() {}
      close() {
        if (this.onclose) this.onclose();
      }
    }
    Object.defineProperty(window, "WebSocket", { value: FakeWebSocket, configurable: true });
  });
  const page = await context.newPage();
  try {
    await page.goto(url);
    stage = "electron-exit-shell";
    await page.locator(".shell").waitFor({ timeout: 10_000 });
    stage = "electron-exit-callback";
    await page.evaluate(() => window.__serverExited());
    stage = "electron-exit-banner";
    const banner = page.locator(".status-banner.error").first();
    await banner.waitFor({ timeout: 10_000 });
    const first = await banner.innerText();
    if (!first.includes("已退出")) throw new Error(`electron exit banner mismatch: ${first}`);
    await stopChild(server);
    stage = "electron-exit-lock";
    const lockButton = page.locator('button[aria-label="锁定本机"]').first();
    if ((await lockButton.count()) === 0) throw new Error("lock button missing after server exit");
    await lockButton.click({ force: true, timeout: 5_000 });
    await page.waitForTimeout(1_200);
    stage = "electron-exit-after";
    const after = await banner.innerText();
    if (!after.includes("已退出")) throw new Error(`electron exit reason overwritten: ${after}`);
  } finally {
    await context.close();
  }
}

async function checkServiceDown(page, server) {
  stage = "service-down";
  await stopChild(server);
  const banner = page.locator(".status-banner.error").first();
  await banner.waitFor({ timeout: 15_000 });
  const text = await banner.innerText();
  if (!text.includes("连接已断开")) throw new Error(`service-down banner mismatch: ${text}`);
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
        browser = await chromium.launch(chromeLaunchOptions({ headless: true }));
        break;
      } catch (error) {
        if (attempt === 3) throw error;
        await sleep(2_000);
      }
    }
    await checkStatusBanners(browser, `http://127.0.0.1:${portA}/`);
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const a = await contextA.newPage();
    const b = await contextB.newPage();
    pageA = a;
    pageB = b;
    await Promise.all([a.goto(`http://127.0.0.1:${portA}/`), b.goto(`http://127.0.0.1:${portB}/`)]);
    await Promise.all([unlock(a), unlock(b)]);
    await Promise.all([waitRelayOnline(a), waitRelayOnline(b)]);
    await checkModalKeyboard(a);
    await checkDisclosure(a);

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

    await checkQueueFullFeedback(a);
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
    await checkSearch(a);
    await checkKeyboardJourney(a);
    await checkElectronExitReason(browser, `http://127.0.0.1:${portA}/`, serverA);
    await checkServiceDown(a, serverA);
    result = {
      delivered: 100,
      received: 100,
      reciprocal: true,
      statusBanners: true,
      modalKeyboard: true,
      disclosure: true,
      keyboardJourney: true,
      search: true,
      queueFullFeedback: true,
      electronExitReason: true,
      serviceDown: true
    };
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

if (require.main === module) {
  main().catch((error) => {
    console.error(`UI_SMOKE FAIL stage=${stage} error=${error.name || "Error"}`);
    process.exit(1);
  });
}

module.exports = {
  passphrase,
  serverJar,
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
  checkDisclosure,
  checkKeyboardJourney,
  checkSearch,
  checkSafetyCodes,
  assertSafetyClosed,
  verifySafety,
  sendBodies,
  waitCount,
  bubbleTexts,
  assertSequence
};
