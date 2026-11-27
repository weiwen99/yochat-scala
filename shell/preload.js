"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("yochat", {
  isElectron: true,
  // The renderer decides whether a notice is allowed (mute, body toggle, document hidden) and
  // passes only an opaque conversation id plus an optional body snippet. The shell never reads
  // the chat store and never adds text of its own.
  notify(payload) {
    ipcRenderer.send("notify", payload || {});
  },
  onNotificationClick(callback) {
    if (typeof callback !== "function") return;
    ipcRenderer.on("notification-click", (_event, payload) => callback(String((payload && payload.conversationId) || "")));
  },
  onServerExited(callback) {
    if (typeof callback !== "function") return;
    ipcRenderer.on("server-exited", () => callback());
  },
  keychainAvailable() {
    return ipcRenderer.invoke("passphrase-available");
  },
  savePassphrase(value) {
    return ipcRenderer.invoke("passphrase-save", value);
  },
  loadPassphrase() {
    return ipcRenderer.invoke("passphrase-load");
  },
  clearPassphrase() {
    return ipcRenderer.invoke("passphrase-clear");
  }
});
