"use strict";

// Round-trips a string through the OS keychain via safeStorage.
// Every path exits with a code: a gate that hangs is not a passing gate.
//
// Exit codes: 0 ok, 1 mismatch/error, 2 unavailable on macOS (a real fault there),
// 3 watchdog, 78 not applicable (Linux without a libsecret/kwallet backend).

const fs = require("node:fs");
const { app, safeStorage } = require("electron");

const source = "yochat-keychain-smoke";

// app.exit does not wait for asynchronous stdout; write synchronously so the
// verdict line survives the exit.
function log(message) {
  fs.writeSync(1, `${message}\n`);
}

function fail(message) {
  fs.writeSync(2, `${message}\n`);
}

function finish(code, message) {
  if (message) log(message);
  app.exit(code);
}

setTimeout(() => {
  fail("KEYCHAIN_SMOKE timeout");
  app.exit(3);
}, 30_000);

app.whenReady().then(async () => {
  try {
    const available = await safeStorage.isAsyncEncryptionAvailable();
    if (!available) {
      // On Linux the backend is libsecret/kwallet, not a per-app item: a host
      // without one cannot run this gate at all. That is "not applicable", not a
      // pass and not a defect. On macOS the same answer is a real fault.
      if (process.platform === "linux") {
        fail("KEYCHAIN_SMOKE skip reason=no-backend");
        return finish(78);
      }
      fail("KEYCHAIN_SMOKE unavailable");
      return finish(2);
    }
    // On Linux the backend is worth recording: "basic_text" means a hardcoded
    // key, so the API works but the secret is not protected.
    const backend =
      process.platform === "linux" && typeof safeStorage.getSelectedStorageBackend === "function"
        ? safeStorage.getSelectedStorageBackend()
        : process.platform;
    const encrypted = await safeStorage.encryptStringAsync(source);
    const { result } = await safeStorage.decryptStringAsync(encrypted);
    if (result !== source) {
      fail("KEYCHAIN_SMOKE mismatch");
      return finish(1);
    }
    // basic_text is a hardcoded key: the API works, the secret is not protected.
    // Pass, but say so, so a green run is not read as "encrypted at rest".
    const insecure = backend === "basic_text" ? " insecure=true" : "";
    return finish(0, `KEYCHAIN_SMOKE ok backend=${backend}${insecure}`);
  } catch (error) {
    fail(`KEYCHAIN_SMOKE error ${error && error.message ? error.message : String(error)}`);
    return finish(1);
  }
});
