"use strict";

const { app, safeStorage } = require("electron");

app.whenReady().then(() => {
  const available = typeof safeStorage.isAsyncEncryptionAvailable === "function"
    ? safeStorage.isAsyncEncryptionAvailable()
    : safeStorage.isEncryptionAvailable();
  if (!available) {
    console.error("KEYCHAIN_SMOKE unavailable");
    app.exit(2);
    return;
  }
  const source = "yochat-keychain-smoke";
  const encrypted = safeStorage.encryptString(source);
  const plain = safeStorage.decryptString(encrypted);
  if (plain !== source) {
    console.error("KEYCHAIN_SMOKE mismatch");
    app.exit(1);
    return;
  }
  console.log("KEYCHAIN_SMOKE ok");
  app.exit(0);
});
