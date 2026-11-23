"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("yochat", {
  isElectron: true,
  notify(title, body) {
    ipcRenderer.send("notify", { title, body });
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
