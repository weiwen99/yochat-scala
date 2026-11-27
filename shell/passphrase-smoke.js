"use strict";

// Proves the desktop passphrase path against the real IPC handlers and preload
// bridge, in the session it actually runs in.
//
// Aqua: unlocking with "remember" writes passphrase.bin; a relaunch on the same
// user-data directory prefills the field from the keychain.
// Background (ssh without GUI injection): the keychain is unreachable, so
// savePassphrase must resolve false instead of rejecting. That rejection was the
// defect this gate was written for.

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { _electron: electron } = require("playwright");

const { passphrase, serverJar, root, freePort, gitHead, sleep } = require("./ui-smoke");

const java = process.env.YOCHAT_JAVA || "java";
const receiptPath = path.join(root, "tmp", "checks", `passphrase-${Date.now()}.json`);
const started = Date.now();

function sessionManager() {
  try {
    return execFileSync("/bin/launchctl", ["managername"], { encoding: "utf8" }).trim();
  } catch (_) {
    return "unknown";
  }
}

function launch(port, userData) {
  return electron.launch({
    args: [".", `--user-data-dir=${userData}`],
    cwd: __dirname,
    env: { ...process.env, YOCHAT_PORT: String(port), YOCHAT_SERVER_JAR: serverJar, YOCHAT_JAVA: java }
  });
}

async function openWindow(app) {
  const page = await app.firstWindow({ timeout: 60_000 });
  await page.waitForLoadState("domcontentloaded");
  await page.locator(".unlock").waitFor({ timeout: 30_000 });
  return page;
}

async function unlockAndEnter(page) {
  const passwords = page.locator(".unlock-form input[type=password]");
  await passwords.first().fill(passphrase);
  if ((await passwords.count()) > 1) await passwords.nth(1).fill(passphrase);
  await page.getByRole("button", { name: /创建并进入|解锁/ }).click();
  await page.locator(".shell").waitFor({ timeout: 30_000 });
}

async function waitForFile(target, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(target)) return true;
    await sleep(100);
  }
  return false;
}

async function main() {
  const mode = sessionManager();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "yochat-passphrase-"));
  let app = null;
  let result = null;
  let failure = null;
  let stage = "init";
  try {
    stage = "first-launch";
    app = await launch(await freePort(), dir);
    let page = await openWindow(app);

    stage = "unlock";
    await unlockAndEnter(page);

    stage = "save-through-bridge";
    const saved = await page.evaluate((value) => window.yochat.savePassphrase(value), passphrase);
    const stored = path.join(dir, "passphrase.bin");

    if (mode === "Aqua") {
      if (saved !== true) throw new Error(`savePassphrase resolved ${JSON.stringify(saved)}, expected true`);
      if (!(await waitForFile(stored))) throw new Error("passphrase.bin was not written");
      if (fs.statSync(stored).size === 0) throw new Error("passphrase.bin is empty");

      stage = "relaunch";
      await app.close();
      app = null;
      await sleep(500);
      app = await launch(await freePort(), dir);
      page = await openWindow(app);
      await page.waitForFunction(
        (selector) => {
          const input = document.querySelector(selector);
          return Boolean(input && input.value);
        },
        ".unlock-form input[type=password]",
        { timeout: 15_000 }
      );
      const restored = await page.locator(".unlock-form input[type=password]").first().inputValue();
      if (restored !== passphrase) throw new Error("stored passphrase was not restored before typing");
      result = { mode, saved: true, stored: true, restored: true };
    } else {
      // Outside Aqua the keychain may or may not be reachable: item creation
      // needs an interactive session, while later reads can succeed once the
      // item exists. The contract is a boolean, never a rejected IPC call.
      if (typeof saved !== "boolean") throw new Error(`savePassphrase resolved ${JSON.stringify(saved)}, expected a boolean`);
      if (saved === false && fs.existsSync(stored)) throw new Error("passphrase.bin written although savePassphrase reported false");
      result = { mode, saved, stored: fs.existsSync(stored), restored: null };
    }
  } catch (error) {
    failure = { stage, error: error && error.message ? error.message : String(error) };
    if (app) {
      const pages = app.windows();
      if (pages.length > 0) await pages[0].screenshot({ path: "/tmp/yochat-passphrase.png" }).catch(() => {});
    }
  } finally {
    if (app) await app.close().catch(() => {});
    fs.rmSync(dir, { recursive: true, force: true });
  }

  const receipt = {
    command: "shell/passphrase-smoke.js",
    commit: gitHead(),
    node: process.version,
    session: mode,
    durationMs: Date.now() - started,
    result,
    failure,
    verdict: failure ? "FAIL" : "PASS"
  };
  fs.mkdirSync(path.dirname(receiptPath), { recursive: true });
  fs.writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`);
  if (failure) {
    console.error(`PASSPHRASE FAIL stage=${failure.stage} detail=${failure.error}`);
    console.error(`PASSPHRASE_RECEIPT path=${receiptPath}`);
    process.exit(1);
  }
  console.log(`PASSPHRASE PASS mode=${mode} duration_ms=${receipt.durationMs}`);
  console.log(`PASSPHRASE_RECEIPT path=${receiptPath}`);
}

main().catch((error) => {
  console.error(`PASSPHRASE FAIL uncaught=${error && error.message ? error.message : String(error)}`);
  process.exit(1);
});
