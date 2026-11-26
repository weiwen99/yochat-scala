const $ = (id) => document.getElementById(id);

async function loadStatus() {
  const response = await fetch("/api/pair/status", { cache: "no-store" });
  if (!response.ok) throw new Error("status");
  return response.json();
}

function showError(message) {
  const target = $("pairing-error");
  target.textContent = message;
  target.hidden = false;
}

function showPaired() {
  $("pairing-content").hidden = true;
  $("paired").hidden = false;
}

async function init() {
  try {
    const status = await loadStatus();
    if (status.paired) return showPaired();
    $("confirmation").textContent = status.confirmation || "—";
    $("fingerprint").textContent = status.fingerprint || "";
    $("request-code").value = status.requestCode || "";
  } catch (_) {
    showError("无法读取配对状态，请确认配对服务仍在运行。");
  }
}

$("copy-request").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("request-code").value);
    $("copy-request").textContent = "已复制";
  } catch (_) {
    showError("复制失败，请手动选择请求码。");
  }
});

$("accept").addEventListener("click", async () => {
  const authCode = $("auth-code").value.trim();
  if (!authCode) return showError("请先粘贴授权码。");
  $("accept").disabled = true;
  $("pairing-error").hidden = true;
  try {
    const response = await fetch("/api/pair/accept", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ authCode })
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "accept");
    showPaired();
  } catch (error) {
    $("accept").disabled = false;
    showError(`配对失败：${error.message}`);
  }
});

init();
