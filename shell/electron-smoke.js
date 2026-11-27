"use strict";

// Real Electron-window acceptance: two shells, two local JVMs, one relay; 100 messages
// each way, then both windows are closed/reopened on the same user-data directories and
// exchange further messages. The web-only ui-smoke remains a faster separate gate.

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { _electron: electron } = require("playwright");
const { resolveElectron } = require("./electron-runtime");

const {
  passphrase,
  serverJar,
  relayJar,
  root,
  freePort,
  startProcess,
  waitLog,
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
  waitCount
} = require("./ui-smoke");

const java = process.env.YOCHAT_JAVA || "java";
const receiptPath = path.join(root, "tmp", "checks", `electron-ui-${Date.now()}.json`);
const started = Date.now();
const children = [];
let stage = "init";
fs.mkdirSync(path.dirname(receiptPath), { recursive: true });

function launch(port, userData) {
  const runtime = resolveElectron();
  return electron.launch({
    executablePath: runtime.executablePath,
    args: [...runtime.args, ".", `--user-data-dir=${userData}`],
    cwd: __dirname,
    env: {
      ...process.env,
      YOCHAT_PORT: String(port),
      YOCHAT_RELAY: process.env.YOCHAT_RELAY || relayEndpoint,
      YOCHAT_SERVER_JAR: serverJar,
      YOCHAT_JAVA: java,
      // Delivery probe: the shell records notice descriptors instead of relying on the OS
      // notification centre, and forwards the click as if the user pressed the banner.
      YOCHAT_NOTIFY_LOG: path.join(userData, "notify.log"),
      YOCHAT_NOTIFY_CLICK: "1"
    }
  });
}

let relayEndpoint = "";

async function openWindow(app, label) {
  const page = await app.firstWindow({ timeout: 60_000 });
  await page.waitForLoadState("domcontentloaded");
  await page.locator(".unlock").waitFor({ timeout: 30_000 });
  return page;
}

async function main() {
  if (!fs.existsSync(serverJar)) throw new Error(`missing server assembly: ${serverJar}`);
  if (!fs.existsSync(relayJar)) throw new Error(`missing relay assembly: ${relayJar}`);
  const relayPort = await freePort();
  relayEndpoint = `127.0.0.1:${relayPort}`;
  const portA1 = await freePort();
  const portB1 = await freePort();
  const portA2 = await freePort();
  const portB2 = await freePort();
  const dirA = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-electron-a-"));
  const dirB = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-electron-b-"));
  const relay = startProcess("relay", [java, "-jar", relayJar, String(relayPort)]);
  let appA = null;
  let appB = null;
  let pageA = null;
  let pageB = null;
  let result = null;
  let failure = null;
  try {
    await waitLog(relay, /listening/i);

    stage = "launch-a";
    appA = await launch(portA1, dirA);
    pageA = await openWindow(appA, "A");
    stage = "launch-b";
    appB = await launch(portB1, dirB);
    pageB = await openWindow(appB, "B");

    stage = "unlock-initial";
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
    await sendBodies(pageA, "electron", 100);
    await Promise.all([
      waitCount(pageA, ".message.own.delivered", 100),
      waitCount(pageB, ".message.peer", 100)
    ]);
    await sendBodies(pageB, "electron-b", 100);
    await Promise.all([
      waitCount(pageA, ".message.peer", 100),
      waitCount(pageB, ".message.own.delivered", 100)
    ]);

    stage = "restart-b";
    await appB.close();
    appB = null;
    await sleep(500);
    appB = await launch(portB2, dirB);
    pageB = await openWindow(appB, "B-restart");
    await unlock(pageB);
    await waitRelayOnline(pageB);
    await openChat(pageB);
    await Promise.all([
      waitCount(pageB, ".message.own.delivered", 100),
      waitCount(pageB, ".message.peer", 100)
    ]);
    await sendBodies(pageB, "restart-b", 1);
    await waitCount(pageA, ".message.peer", 101);

    stage = "restart-a";
    await appA.close();
    appA = null;
    await sleep(500);
    appA = await launch(portA2, dirA);
    pageA = await openWindow(appA, "A-restart");
    await unlock(pageA);
    await waitRelayOnline(pageA);
    await openChat(pageA);
    await Promise.all([
      waitCount(pageA, ".message.own.delivered", 100),
      waitCount(pageA, ".message.peer", 101)
    ]);
    await sendBodies(pageA, "after-restart", 1);
    await Promise.all([
      waitCount(pageA, ".message.own.delivered", 101),
      waitCount(pageB, ".message.peer", 101)
    ]);

    stage = "notifications";
    const notifyLog = path.join(dirA, "notify.log");
    const readNotices = () => {
      if (!fs.existsSync(notifyLog)) return [];
      return fs
        .readFileSync(notifyLog, "utf8")
        .split("\n")
        .filter((line) => line.trim())
        .map((line) => JSON.parse(line));
    };
    const awaitNotices = async (expected) => {
      const deadline = Date.now() + 20_000;
      while (Date.now() < deadline) {
        const notices = readNotices();
        if (notices.length >= expected) return notices;
        await sleep(100);
      }
      throw new Error(`notification count did not reach ${expected}: ${readNotices().length}`);
    };
    const conversationA = await pageA.evaluate(async () => {
      const state = await (await fetch("/api/state")).json();
      return state.conversations[0].id;
    });
    const sendNotice = async (body) => {
      const composer = pageB.locator(".composer-input");
      await composer.fill(body);
      await composer.press("Enter");
      await pageB.waitForFunction(() => document.querySelector(".composer-input")?.value === "");
    };
    // The renderer only notifies while the document is hidden; make that state explicit.
    await pageA.evaluate(() =>
      Object.defineProperty(document, "hidden", { configurable: true, get: () => true })
    );
    await pageA.locator('button[aria-label="联系人"]').first().click();
    await sendNotice("notify-default");
    const firstNotices = await awaitNotices(1);
    const firstNotice = firstNotices[0];
    if (firstNotice.conversationId !== conversationA) {
      throw new Error(`notification carried the wrong conversation: ${firstNotice.conversationId} != ${conversationA}`);
    }
    if (firstNotice.body !== "有一条新消息") {
      throw new Error(`default notification leaked the body: ${firstNotice.body}`);
    }
    await pageA.locator(".composer-input").waitFor({ timeout: 15_000 });
    const selectedAfterClick = await pageA
      .locator(".conversation-row.on")
      .first()
      .getAttribute("data-conversation-id");
    if (selectedAfterClick !== conversationA) {
      throw new Error(`notification click selected ${selectedAfterClick}, expected ${conversationA}`);
    }
    await pageA.locator(".notify-body-toggle").check();
    await pageA.locator('button[aria-label="联系人"]').first().click();
    await sendNotice("notify-with-body");
    const secondNotices = await awaitNotices(2);
    if (!secondNotices[1].body.includes("notify-with-body")) {
      throw new Error(`body toggle did not include the text: ${secondNotices[1].body}`);
    }
    await pageA.locator(".composer-input").waitFor({ timeout: 15_000 });
    await pageA.locator(".notify-mute-toggle").check();
    await pageA.locator('button[aria-label="联系人"]').first().click();
    await sendNotice("notify-muted");
    await sleep(2_000);
    if (readNotices().length !== 2) {
      throw new Error(`muted conversation produced a notification: ${readNotices().length}`);
    }
    await pageA.locator(".conversation-row").first().click();
    await pageA.locator(".notify-mute-toggle").uncheck();

    result = {
      delivered: 100,
      received: 100,
      reciprocal: true,
      restarted: ["B", "A"],
      notifications: { conversationScoped: true, bodyDefaultOff: true, bodyToggle: true, mute: true, click: true }
    };
  } catch (error) {
    failure = { stage, error: error.name || "Error", detail: String(error.message || "").slice(0, 240) };
    if (pageA) await pageA.screenshot({ path: "/tmp/yochat-electron-a.png" }).catch(() => {});
    if (pageB) await pageB.screenshot({ path: "/tmp/yochat-electron-b.png" }).catch(() => {});
  } finally {
    if (appA) await appA.close().catch(() => {});
    if (appB) await appB.close().catch(() => {});
    for (const child of children.reverse()) await stopChild(child);
    fs.rmSync(dirA, { recursive: true, force: true });
    fs.rmSync(dirB, { recursive: true, force: true });
  }

  const receipt = {
    command: "shell/electron-smoke.js",
    commit: gitHead(),
    node: process.version,
    electron: "window",
    durationMs: Date.now() - started,
    serverJar,
    relayJar,
    result,
    failure,
    verdict: failure ? "FAIL" : "PASS"
  };
  fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n");
  if (failure) {
    console.error(`ELECTRON_UI FAIL stage=${failure.stage} error=${failure.error}`);
    console.error(`ELECTRON_UI_RECEIPT path=${receiptPath}`);
    process.exit(1);
  }
  console.log(`ELECTRON_UI PASS delivered=100 received=100 restarted=B,A duration_ms=${receipt.durationMs}`);
  console.log(`ELECTRON_UI_RECEIPT path=${receiptPath}`);
  process.exit(0);
}

main().catch((error) => {
  console.error(`ELECTRON_UI FAIL stage=${stage} error=${error.name || "Error"}`);
  console.error(String(error.stack || error));
  process.exit(1);
});
