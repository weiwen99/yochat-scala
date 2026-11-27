"use strict";

const { app, BrowserWindow, Notification, dialog, ipcMain, nativeTheme, safeStorage } = require("electron");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const START_NS = process.hrtime.bigint();
const START_EPOCH = Number(process.env.YOCHAT_START_EPOCH || 0);
const HOST = "127.0.0.1";
const PORT = Number(process.env.YOCHAT_PORT || 8787);
const BASE_URL = `http://${HOST}:${PORT}`;
const RELAY = process.env.YOCHAT_RELAY || "127.0.0.1:7654";

let mainWindow = null;
let serverProcess = null;
let quitting = false;

if (process.env.YOCHAT_CAPTURE) {
  app.commandLine.appendSwitch("disable-http-cache");
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
  app.whenReady().then(start).catch(fail);
}

async function start() {
  if (process.env.YOCHAT_THEME === "dark" || process.env.YOCHAT_THEME === "light") {
    nativeTheme.themeSource = process.env.YOCHAT_THEME;
  }
  if (!(await serverIsUp())) {
    const jar = findServerJar();
    if (!jar) {
      dialog.showErrorBox(
        "YoChat 服务未找到",
        "请先运行 ./mill server.assembly，或设置 YOCHAT_SERVER_JAR 指向可运行的 server assembly。"
      );
      app.quit();
      return;
    }
    spawnServer(jar);
    const ready = await waitForServer(30000);
    if (!ready) {
      dialog.showErrorBox("YoChat 服务未启动", "本机服务在 30 秒内没有响应。请查看终端输出。");
      app.quit();
      return;
    }
  }
  createWindow();
}

function findServerJar() {
  const candidates = [
    process.env.YOCHAT_SERVER_JAR,
    path.join(__dirname, "..", "out", "server", "assembly.dest", "out.jar"),
    path.join(process.resourcesPath || "", "server.jar")
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate)) || null;
}

function spawnServer(jar) {
  const dataDir = path.join(app.getPath("userData"), "data");
  fs.mkdirSync(dataDir, { recursive: true });
  serverProcess = spawn(
    process.env.YOCHAT_JAVA || "java",
    ["-jar", jar, "--port", String(PORT), "--data-dir", dataDir, "--relay", RELAY],
    { stdio: "inherit" }
  );
  serverProcess.on("exit", () => {
    serverProcess = null;
    if (!quitting && mainWindow) {
      mainWindow.webContents.send("server-exited");
    }
  });
}

async function serverIsUp() {
  try {
    const response = await fetch(`${BASE_URL}/api/session`);
    return response.ok;
  } catch (_) {
    return false;
  }
}

async function waitForServer(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await serverIsUp()) return true;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return false;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 980,
    minHeight: 640,
    backgroundColor: "#f3f2ed",
    title: "YoChat",
    titleBarStyle: process.platform === "darwin" ? "hiddenInset" : "default",
    trafficLightPosition: { x: 14, y: 14 },
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith(BASE_URL)) event.preventDefault();
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
  mainWindow.loadURL(BASE_URL);

  if (process.env.YOCHAT_START_METRIC) {
    mainWindow.webContents.once("did-finish-load", () => {
      const deadline = Date.now() + 30000;
      const waitForUi = () => {
        mainWindow.webContents
          .executeJavaScript("!document.querySelector('.loading-veil')")
          .then((ready) => {
            if (!ready && Date.now() < deadline) {
              setTimeout(waitForUi, 25);
              return;
            }
            const elapsedMs = START_EPOCH > 0 ? Date.now() - START_EPOCH : Number(process.hrtime.bigint() - START_NS) / 1_000_000;
            fs.writeFileSync(process.env.YOCHAT_START_METRIC, JSON.stringify({ elapsedMs }) + "\n");
            if (process.env.YOCHAT_START_METRIC_HOLD !== "1") app.quit();
          })
          .catch(() => {
            const elapsedMs = START_EPOCH > 0 ? Date.now() - START_EPOCH : Number(process.hrtime.bigint() - START_NS) / 1_000_000;
            fs.writeFileSync(process.env.YOCHAT_START_METRIC, JSON.stringify({ elapsedMs, ready: false }) + "\n");
            if (process.env.YOCHAT_START_METRIC_HOLD !== "1") app.quit();
          });
      };
      waitForUi();
    });
  }

  if (process.env.YOCHAT_CAPTURE) {
    mainWindow.webContents.once("did-finish-load", () => {
      setTimeout(async () => {
        try {
          const image = await mainWindow.webContents.capturePage();
          fs.writeFileSync(process.env.YOCHAT_CAPTURE, image.toPNG());
        } finally {
          app.quit();
        }
      }, 1500);
    });
  }
}

// safeStorage's async API is the only supported one in Electron 45; the sync
// variants are deprecated. The probe resolves a boolean, so a caller that treats
// the Promise as a value would see "available" even when the keychain is not.
// YOCHAT_KEYCHAIN_UNAVAILABLE is a disabled-by-default gate hook: the unavailable
// branch depends on machine keychain state and cannot be reproduced on demand.
async function keychainAvailable() {
  if (process.env.YOCHAT_KEYCHAIN_UNAVAILABLE === "1") return false;
  try {
    return await safeStorage.isAsyncEncryptionAvailable();
  } catch (_) {
    return false;
  }
}

ipcMain.handle("passphrase-available", () => keychainAvailable());

ipcMain.handle("passphrase-save", async (_event, value) => {
  if (!(await keychainAvailable())) return false;
  const target = path.join(app.getPath("userData"), "passphrase.bin");
  try {
    const encrypted = await safeStorage.encryptStringAsync(String(value));
    await fs.promises.writeFile(target, encrypted, { mode: 0o600 });
    return true;
  } catch (_) {
    return false;
  }
});

ipcMain.handle("passphrase-load", async () => {
  if (!(await keychainAvailable())) return null;
  const target = path.join(app.getPath("userData"), "passphrase.bin");
  try {
    const encrypted = await fs.promises.readFile(target);
    const { result } = await safeStorage.decryptStringAsync(encrypted);
    return result;
  } catch (_) {
    return null;
  }
});

ipcMain.handle("passphrase-clear", () => {
  fs.rmSync(path.join(app.getPath("userData"), "passphrase.bin"), { force: true });
  return true;
});

ipcMain.on("notify", (_event, payload) => {
  if (!mainWindow || mainWindow.isDestroyed() || mainWindow.isFocused()) return;
  const title = String(payload && payload.title ? payload.title : "YoChat");
  const body = String(payload && payload.body ? payload.body : "有一条新消息");
  const notification = new Notification({ title, body });
  notification.on("click", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
  notification.show();
});

app.on("before-quit", () => {
  quitting = true;
  if (serverProcess) {
    serverProcess.kill("SIGTERM");
    serverProcess = null;
  }
});

app.on("window-all-closed", () => {
  app.quit();
});

function fail(error) {
  console.error(error);
  dialog.showErrorBox("YoChat 启动失败", String(error && error.stack ? error.stack : error));
  app.quit();
}
