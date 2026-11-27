"use strict";

// Round-trips a string through the OS keychain via safeStorage.
// Every path exits with a code: a gate that hangs is not a passing gate.

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
      fail("KEYCHAIN_SMOKE unavailable");
      return finish(2);
    }
    const encrypted = await safeStorage.encryptStringAsync(source);
    const { result } = await safeStorage.decryptStringAsync(encrypted);
    if (result !== source) {
      fail("KEYCHAIN_SMOKE mismatch");
      return finish(1);
    }
    return finish(0, "KEYCHAIN_SMOKE ok");
  } catch (error) {
    fail(`KEYCHAIN_SMOKE error ${error && error.message ? error.message : String(error)}`);
    return finish(1);
  }
});
