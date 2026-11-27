"use strict";

// Drives the real preload bridge and IPC handlers of the desktop shell.
//
// Default: unlocking with "remember" (checked by default) must write
// passphrase.bin; an explicit bridge save must resolve true; a relaunch on the
// same user-data directory must prefill the field from the keychain. Outside
// Aqua only the contract is asserted: the calls resolve to booleans instead of
// rejecting. YOCHAT_KEYCHAIN_UNAVAILABLE=1 forces the unavailable branch and
// asserts save=false / load=null.

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { _electron: electron } = require("playwright");

const { passphrase, serverJar, root, freePort, gitHead, sleep } = require("./ui-smoke");

const java = process.env.YOCHAT_JAVA || "java";
const forcedUnavailable = process.env.YOCHAT_KEYCHAIN_UNAVAILABLE === "1";
const receiptPath = path.join(root, "tmp", "checks", `passphrase-${Date.now()}.json`);
const screenshotPath = "/tmp/yochat-passphrase.png";
const started = Date.now();

const watchdog = setTimeout(() => {
  console.error("PASSPHRASE timeout");
  process.exit(3);
}, 180_000);
watchdog.unref();

function sessionManager() {
  try {
    return execFileSync("/bin/launchctl", ["managername"], { encoding: "utf8" }).trim();
  } catch (_) {
    return "unknown";
  }
}

function dirtyFiles() {
  try {
    return execFileSync("git", ["status", "--porcelain"], { cwd: root, encoding: "utf8" }).trim().split("\n").filter(Boolean).length;
  } catch (_) {
    return -1;
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
  const stored = path.join(dir, "passphrase.bin");
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

    stage = "available";
    const available = await page.evaluate(() => window.yochat.keychainAvailable());

    if (forcedUnavailable) {
      stage = "unavailable-contract";
      const saved = await page.evaluate((value) => window.yochat.savePassphrase(value), passphrase);
      const loaded = await page.evaluate(() => window.yochat.loadPassphrase());
      if (available !== false) throw new Error(`keychainAvailable resolved ${JSON.stringify(available)}, expected false`);
      if (saved !== false) throw new Error(`savePassphrase resolved ${JSON.stringify(saved)}, expected false`);
      if (loaded !== null) throw new Error(`loadPassphrase resolved ${JSON.stringify(loaded)}, expected null`);
      if (fs.existsSync(stored)) throw new Error("passphrase.bin written while the keychain was unavailable");
      result = { mode, forcedUnavailable: true, available, saved, loaded };
    } else if (mode === "Aqua") {
      stage = "ui-remember";
      if (available !== true) throw new Error(`keychainAvailable resolved ${JSON.stringify(available)}, expected true`);
      if (!(await waitForFile(stored))) throw new Error('UI "remember" did not write passphrase.bin');
      if (fs.statSync(stored).size === 0) throw new Error("passphrase.bin is empty");

      stage = "save-through-bridge";
      const saved = await page.evaluate((value) => window.yochat.savePassphrase(value), passphrase);
      if (saved !== true) throw new Error(`savePassphrase resolved ${JSON.stringify(saved)}, expected true`);
      const loaded = await page.evaluate(() => window.yochat.loadPassphrase());
      if (loaded !== passphrase) throw new Error("loadPassphrase did not return the saved value");

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
      result = { mode, available, saved: true, loaded: true, stored: true, restored: true };
    } else {
      // Outside Aqua the keychain may or may not be reachable: item creation
      // needs an interactive session, while later reads can succeed once the
      // item exists. The contract is a boolean, never a rejected IPC call.
      stage = "background-contract";
      const saved = await page.evaluate((value) => window.yochat.savePassphrase(value), passphrase);
      if (typeof available !== "boolean") throw new Error("keychainAvailable did not resolve a boolean");
      if (typeof saved !== "boolean") throw new Error(`savePassphrase resolved ${JSON.stringify(saved)}, expected a boolean`);
      if (saved === false && fs.existsSync(stored)) throw new Error("passphrase.bin written although savePassphrase reported false");
      result = { mode, available, saved, stored: fs.existsSync(stored), restored: null };
    }
  } catch (error) {
    failure = { stage, error: error && error.message ? error.message : String(error) };
    if (app) {
      const pages = app.windows();
      if (pages.length > 0) await pages[0].screenshot({ path: screenshotPath }).catch(() => {});
    }
  } finally {
    if (app) await app.close().catch(() => {});
    fs.rmSync(dir, { recursive: true, force: true });
  }

  const receipt = {
    command: "shell/passphrase-smoke.js",
    commit: gitHead(),
    dirtyFiles: dirtyFiles(),
    node: process.version,
    session: mode,
    forcedUnavailable,
    durationMs: Date.now() - started,
    screenshot: failure && fs.existsSync(screenshotPath) ? screenshotPath : null,
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
  console.log(`PASSPHRASE PASS mode=${mode} unavailable=${forcedUnavailable} duration_ms=${receipt.durationMs}`);
  console.log(`PASSPHRASE_RECEIPT path=${receiptPath}`);
}

main().catch((error) => {
  console.error(`PASSPHRASE FAIL uncaught=${error && error.message ? error.message : String(error)}`);
  process.exit(1);
});
