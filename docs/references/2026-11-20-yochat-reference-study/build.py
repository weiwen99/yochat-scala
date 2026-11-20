#!/usr/bin/env python3
"""Build index.html for the YoChat reference study.

Usage:
    python3 docs/references/2026-11-20-yochat-reference-study/build.py

The page embeds Markdown at build time and loads SVG files by relative path, so it
works from file:// without a server or CDN. Edit the Markdown or SVG sources, then
re-run this script.
"""

from __future__ import annotations

import html
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "index.html"
LOCAL_DIAGRAMS = ROOT / "diagrams"
FLW_ROOT = ROOT.parent / "2026-11-20-flw-yochat-diagrams" / "notes"
FLW_DIAGRAMS = FLW_ROOT / "diagrams"

DOC_ORDER = ["survey.md", "requirements.md", "terms.md", "README.md"]
SVG_NS = "{http://www.w3.org/2000/svg}"


def esc(value: str) -> str:
    return html.escape(str(value), quote=True)


def first_heading(text: str, fallback: str) -> str:
    match = re.search(r"^#\s+(.+?)\s*$", text, re.MULTILINE)
    return match.group(1).strip() if match else fallback


def first_paragraph(text: str, limit: int = 150) -> str:
    lines = text.splitlines()
    start = 0
    for index, line in enumerate(lines):
        if line.startswith("# "):
            start = index + 1
            break
    buffer: list[str] = []
    for line in lines[start:]:
        stripped = line.strip()
        if stripped.startswith(">"):
            stripped = stripped.lstrip(">").strip()
        if not stripped:
            if buffer:
                break
            continue
        if stripped.startswith(("#", "|", "```", "---")):
            if buffer:
                break
            continue
        buffer.append(stripped)
    paragraph = "".join(buffer) if buffer else ""
    paragraph = re.sub(r"\*\*([^*]+)\*\*", r"\1", paragraph)
    paragraph = re.sub(r"`([^`]+)`", r"\1", paragraph)
    paragraph = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", paragraph)
    paragraph = re.sub(r"\s+", " ", paragraph).strip()
    if len(paragraph) > limit:
        paragraph = paragraph[: limit - 1].rstrip() + "…"
    return paragraph


def svg_meta(path: Path, src: str) -> dict:
    title = path.stem
    desc = ""
    width: int | None = None
    height: int | None = None
    try:
        root = ET.parse(path).getroot()
        title = (root.findtext(SVG_NS + "title") or title).strip()
        desc = (root.findtext(SVG_NS + "desc") or "").strip()
        viewbox = root.get("viewBox")
        if viewbox:
            parts = [float(part) for part in re.split(r"[,\s]+", viewbox.strip()) if part]
            if len(parts) == 4:
                width, height = int(round(parts[2])), int(round(parts[3]))
        if width is None and root.get("width"):
            width = int(round(float(re.sub(r"[^0-9.]", "", root.get("width", "")))))
        if height is None and root.get("height"):
            height = int(round(float(re.sub(r"[^0-9.]", "", root.get("height", "")))))
    except Exception as exc:  # noqa: BLE001 - keep building, report the file
        print(f"warning: cannot parse {path}: {exc}")
    return {
        "src": src,
        "title": title,
        "desc": desc,
        "width": width,
        "height": height,
    }


def collect_docs() -> dict[str, dict]:
    paths = sorted(ROOT.glob("*.md"), key=lambda p: (DOC_ORDER.index(p.name) if p.name in DOC_ORDER else 99, p.name))
    docs: dict[str, dict] = {}
    for path in paths:
        text = path.read_text(encoding="utf-8")
        docs[path.name] = {
            "title": first_heading(text, path.stem),
            "content": text,
            "lines": len(text.splitlines()),
            "summary": first_paragraph(text),
        }
    return docs


def collect_diagrams() -> list[dict]:
    diagrams: list[dict] = []
    # flw's six diagrams are kept in their original package. The page references
    # them by relative path so the SVGs are not duplicated.
    if FLW_DIAGRAMS.is_dir():
        for path in sorted(FLW_DIAGRAMS.glob("*.svg")):
            src = f"../2026-11-20-flw-yochat-diagrams/notes/diagrams/{path.name}"
            item = svg_meta(path, src)
            item["group"] = "flw"
            diagrams.append(item)
    else:
        print(f"warning: flw diagram directory not found: {FLW_DIAGRAMS}")
    for path in sorted(LOCAL_DIAGRAMS.glob("*.svg")):
        src = f"diagrams/{path.name}"
        item = svg_meta(path, src)
        item["group"] = "analysis"
        diagrams.append(item)
    return diagrams


def main() -> None:
    docs = collect_docs()
    diagrams = collect_diagrams()
    built_at = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M %Z")

    doc_cards: list[str] = []
    for name, doc in docs.items():
        doc_cards.append(
            f'<article class="doc-card" tabindex="0" role="button" data-open-doc="{esc(name)}" '
            f'aria-label="阅读文档：{esc(doc["title"])}">'
            f'<span class="kicker">Markdown</span>'
            f'<h3>{esc(doc["title"])}</h3>'
            f'<p>{esc(doc["summary"])}</p>'
            f'<span class="hint">{doc["lines"]} 行 · 点击阅读</span>'
            f"</article>"
        )

    diagram_cards: list[str] = []
    for index, diagram in enumerate(diagrams):
        badge = "flw" if diagram["group"] == "flw" else "本页"
        diagram_cards.append(
            f'<article class="diagram-card" tabindex="0" role="button" data-open-diagram="{index}" '
            f'aria-label="查看大图：{esc(diagram["title"])}">'
            f'<div class="thumb"><img src="{esc(diagram["src"])}" alt="{esc(diagram["title"])}"></div>'
            f'<div class="card-body">'
            f'<span class="kicker">{badge}</span>'
            f'<h3>{esc(diagram["title"])}</h3>'
            f'<p>{esc(diagram["desc"])}</p>'
            f'<span class="hint">点击查看大图</span>'
            f"</div></article>"
        )

    docs_json = json.dumps(docs, ensure_ascii=False).replace("</", "<\\/")
    diagrams_json = json.dumps(diagrams, ensure_ascii=False).replace("</", "<\\/")

    html_text = TEMPLATE.replace("@@BUILT_AT@@", esc(built_at))
    html_text = html_text.replace("@@DIAGRAM_COUNT@@", str(len(diagrams)))
    html_text = html_text.replace("@@DOC_COUNT@@", str(len(docs)))
    html_text = html_text.replace("@@DOCS_JSON@@", docs_json)
    html_text = html_text.replace("@@DIAGRAMS_JSON@@", diagrams_json)
    html_text = html_text.replace("@@DIAGRAM_CARDS@@", "\n".join(diagram_cards))
    html_text = html_text.replace("@@DOC_CARDS@@", "\n".join(doc_cards))
    OUTPUT.write_text(html_text, encoding="utf-8")
    print(f"wrote {OUTPUT} ({len(html_text)} bytes): {len(diagrams)} diagrams, {len(docs)} docs")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-Hans">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>YoChat 旧实现阅读笔记</title>
<style>
  :root {
    --paper: #f7f4ed;
    --ink: #20201d;
    --muted: #6d675c;
    --line: #d8d1c2;
    --panel: #efe9dc;
    --accent: #8a3f2b;
    --code: #f2ede3;
    --shadow: 0 18px 50px rgba(40, 35, 28, .16);
  }
  * { box-sizing: border-box; }
  html { color-scheme: light; }
  body {
    margin: 0;
    color: var(--ink);
    background:
      radial-gradient(1200px 600px at 10% -10%, #fffdf7 0%, transparent 60%),
      radial-gradient(900px 500px at 100% 0%, #f3ecdd 0%, transparent 55%),
      var(--paper);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", sans-serif;
    line-height: 1.7;
  }
  body.modal-open { overflow: hidden; }
  a { color: var(--accent); text-decoration-thickness: .08em; text-underline-offset: .18em; }
  a:hover { text-decoration-thickness: .14em; }
  .wrap { width: min(1180px, calc(100% - 44px)); margin: 0 auto; }
  header { padding: 64px 0 28px; }
  .eyebrow {
    display: inline-flex; align-items: center; gap: 8px;
    margin: 0 0 18px; padding: 6px 10px;
    border: 1px solid var(--line); background: rgba(255,255,255,.45);
    color: var(--muted); font-size: 12px; letter-spacing: .14em; text-transform: uppercase;
  }
  h1 {
    margin: 0; max-width: 900px;
    font-family: "Songti SC", "Noto Serif CJK SC", Georgia, serif;
    font-size: clamp(34px, 5.4vw, 68px); line-height: 1.12; letter-spacing: -.02em; font-weight: 400;
  }
  .lede { max-width: 820px; margin: 20px 0 0; color: var(--muted); font-size: 18px; }
  .meta { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 26px; }
  .pill {
    display: inline-flex; align-items: center; gap: 8px;
    padding: 7px 11px; border: 1px solid var(--line); background: rgba(255,255,255,.42);
    color: var(--muted); font-size: 13px;
  }
  .pill b { color: var(--ink); font-weight: 600; }
  main { padding-bottom: 80px; }
  section { margin-top: 54px; }
  .section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 18px; border-bottom: 1px solid var(--line); padding-bottom: 12px; }
  .section-head h2 {
    margin: 0; font-family: "Songti SC", "Noto Serif CJK SC", Georgia, serif;
    font-size: 30px; line-height: 1.2; font-weight: 400;
  }
  .section-head p { margin: 0; color: var(--muted); font-size: 13px; }
  .grid { display: grid; gap: 18px; margin-top: 22px; }
  .diagram-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .doc-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .diagram-card, .doc-card {
    display: flex; flex-direction: column; min-width: 0;
    border: 1px solid var(--line); background: rgba(255,255,255,.5);
    cursor: pointer; text-align: left; color: inherit;
    transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
  }
  .diagram-card:hover, .doc-card:hover, .diagram-card:focus-visible, .doc-card:focus-visible {
    transform: translateY(-2px); box-shadow: var(--shadow); border-color: #b9af9b; outline: none;
  }
  .thumb {
    aspect-ratio: 16 / 10; display: flex; align-items: center; justify-content: center;
    padding: 14px; background: #fffdf8; border-bottom: 1px solid var(--line); overflow: hidden;
  }
  .thumb img { display: block; width: 100%; height: 100%; object-fit: contain; }
  .card-body { display: flex; flex-direction: column; gap: 8px; padding: 16px 18px 18px; min-height: 150px; }
  .doc-card { padding: 20px 22px 22px; min-height: 170px; }
  .kicker { color: var(--accent); font-size: 11px; letter-spacing: .16em; text-transform: uppercase; }
  h3 { margin: 0; font-family: "Songti SC", "Noto Serif CJK SC", Georgia, serif; font-size: 23px; line-height: 1.35; font-weight: 400; }
  .card-body p, .doc-card p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
  .hint { margin-top: auto; color: var(--muted); font-size: 12px; }
  .note { margin-top: 22px; color: var(--muted); font-size: 13px; }
  .note code { background: var(--code); border: 1px solid var(--line); padding: 1px 5px; }
  footer { border-top: 1px solid var(--line); padding: 22px 0 40px; color: var(--muted); font-size: 12px; }
  footer p { margin: 4px 0; }

  .modal[hidden] { display: none; }
  .modal { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; padding: 22px; }
  .modal-backdrop { position: absolute; inset: 0; background: rgba(22, 20, 17, .58); backdrop-filter: blur(3px); }
  .modal-panel {
    position: relative; display: flex; flex-direction: column;
    width: min(1220px, 96vw); height: min(92vh, 1000px);
    background: #fffdf8; border: 1px solid #cfc5b2; box-shadow: var(--shadow);
  }
  .modal-head {
    display: flex; align-items: center; gap: 14px; flex: 0 0 auto;
    padding: 12px 14px 12px 20px; border-bottom: 1px solid var(--line); background: #fbf7ef;
  }
  .modal-head h2 {
    flex: 1; min-width: 0; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    font-family: "Songti SC", "Noto Serif CJK SC", Georgia, serif; font-size: 18px; font-weight: 400;
  }
  .modal-actions { display: flex; align-items: center; gap: 6px; flex: 0 0 auto; }
  .modal-actions button, .modal-actions a {
    display: inline-flex; align-items: center; justify-content: center;
    min-height: 34px; padding: 0 10px; border: 1px solid var(--line); background: #fff;
    color: var(--ink); font: inherit; font-size: 12px; text-decoration: none; cursor: pointer;
  }
  .modal-actions button:hover, .modal-actions a:hover { background: var(--panel); }
  .modal-actions button[hidden], .modal-actions a[hidden] { display: none; }
  .modal-body { flex: 1; min-height: 0; overflow: auto; }
  .modal-body.image-mode { padding: 18px; background: #f4f1e8; }
  .diagram-stage { min-height: 100%; display: flex; align-items: center; justify-content: center; }
  .modal-image { display: block; max-width: 100%; max-height: calc(92vh - 130px); width: auto; height: auto; background: #fff; }
  .modal-image.actual { max-width: none; max-height: none; }
  .modal-body.doc-mode { padding: 26px 30px 46px; }
  .md { max-width: 980px; margin: 0 auto; font-size: 15.5px; line-height: 1.75; }
  .md h1, .md h2, .md h3, .md h4 {
    font-family: "Songti SC", "Noto Serif CJK SC", Georgia, serif; font-weight: 400; line-height: 1.28;
  }
  .md h1 { margin: 0 0 18px; font-size: 34px; }
  .md h2 { margin: 34px 0 12px; font-size: 25px; border-bottom: 1px solid var(--line); padding-bottom: 6px; }
  .md h3 { margin: 26px 0 10px; font-size: 20px; }
  .md h4 { margin: 20px 0 8px; font-size: 17px; }
  .md p { margin: 10px 0; }
  .md ul, .md ol { margin: 10px 0; padding-left: 1.7em; }
  .md li { margin: 5px 0; }
  .md blockquote { margin: 14px 0; padding: 2px 0 2px 16px; border-left: 3px solid var(--line); color: var(--muted); }
  .md blockquote p { margin: 5px 0; }
  .md code { background: var(--code); border: 1px solid #e4dccb; padding: 1px 5px; font-size: .9em; overflow-wrap: anywhere; }
  .md pre { margin: 14px 0; padding: 14px 16px; overflow: auto; background: #f4efe5; border: 1px solid var(--line); }
  .md pre code { display: block; padding: 0; border: 0; background: none; font-size: 13px; line-height: 1.55; }
  .md hr { margin: 26px 0; border: 0; border-top: 1px solid var(--line); }
  .md .table-wrap { margin: 16px 0; overflow-x: auto; border: 1px solid var(--line); }
  .md table { width: 100%; border-collapse: collapse; font-size: 13.5px; }
  .md th, .md td { padding: 8px 10px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }
  .md th { background: #f3eee3; font-weight: 600; white-space: nowrap; }
  .md tr:last-child td { border-bottom: 0; }
  .md td code { white-space: normal; }
  .md a[data-doc], .md a[data-diagram] { text-decoration-style: dotted; }

  @media (max-width: 980px) {
    .diagram-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .doc-grid { grid-template-columns: 1fr; }
  }
  @media (max-width: 640px) {
    .wrap { width: min(100% - 28px, 1180px); }
    header { padding-top: 40px; }
    .diagram-grid { grid-template-columns: 1fr; }
    .modal { padding: 8px; }
    .modal-panel { width: 100%; height: 94vh; }
    .modal-head { padding: 10px; }
    .modal-head h2 { font-size: 15px; }
    .modal-actions button, .modal-actions a { padding: 0 8px; }
    .modal-body.doc-mode { padding: 18px 16px 36px; }
    .md h1 { font-size: 28px; }
    .md h2 { font-size: 22px; }
  }
  @media print {
    .modal { display: none !important; }
    body { background: #fff; }
    .diagram-card, .doc-card { break-inside: avoid; box-shadow: none; }
  }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <p class="eyebrow">YoChat · reference study</p>
    <h1>旧实现阅读笔记</h1>
    <p class="lede">先弄清旧项目做到了哪，再判断 118 条需求里哪些真的要在 MVP 做。阅读页把设计图与 Markdown 放在同一个离线入口里；点击看大图，<code>Esc</code> 退出。</p>
    <div class="meta">
      <span class="pill"><b>118</b> 条需求</span>
      <span class="pill"><b>95</b> 条旧首增量</span>
      <span class="pill"><b>23</b> 条已延后</span>
      <span class="pill"><b>@@DIAGRAM_COUNT@@</b> 张图</span>
      <span class="pill"><b>@@DOC_COUNT@@</b> 篇文档</span>
    </div>
  </header>

  <main>
    <section id="diagrams">
      <div class="section-head"><h2>设计图</h2><p>前六张来自 flw 的阅读包；最后一张是本页的分析图。</p></div>
      <div class="grid diagram-grid">
@@DIAGRAM_CARDS@@
      </div>
      <p class="note">图是阅读辅助，不是规格或验收证据。flw 的六张图原样引用；要改它们，去 <code>../2026-11-20-flw-yochat-diagrams/notes/</code>。</p>
    </section>

    <section id="docs">
      <div class="section-head"><h2>文档</h2><p>建议顺序：现状 → 需求分期 → 术语 → 说明。</p></div>
      <div class="grid doc-grid">
@@DOC_CARDS@@
      </div>
      <p class="note">本页由 <code>build.py</code> 生成；Markdown 与 SVG 在构建时嵌入或按相对路径引用，不依赖 CDN 或本地服务器。</p>
    </section>
  </main>

  <footer>
    <p>生成时间：@@BUILT_AT@@ · 源：<code>~/projects/yochat-reference</code>（2026-11-20 只读副本）与 <code>docs/spec/</code></p>
    <p>这组文档持续维护：改 Markdown / SVG 后重新运行 <code>python3 build.py</code>。</p>
  </footer>
</div>

<div class="modal" id="modal" hidden aria-hidden="true">
  <div class="modal-backdrop" id="modal-backdrop"></div>
  <div class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-head">
      <h2 id="modal-title">预览</h2>
      <div class="modal-actions">
        <button type="button" id="prev-btn" title="上一张（←）" aria-label="上一张">←</button>
        <button type="button" id="next-btn" title="下一张（→）" aria-label="下一张">→</button>
        <button type="button" id="zoom-btn" title="切换原始尺寸" aria-pressed="false">原始尺寸</button>
        <a id="raw-link" href="#" target="_blank" rel="noopener">原始文件</a>
        <button type="button" id="close-btn" title="关闭（Esc）" aria-label="关闭">关闭</button>
      </div>
    </div>
    <div class="modal-body doc-mode" id="modal-body"></div>
  </div>
</div>

<script type="application/json" id="docs-data">@@DOCS_JSON@@</script>
<script type="application/json" id="diagrams-data">@@DIAGRAMS_JSON@@</script>
<script>
(function () {
  'use strict';

  var DOCS = JSON.parse(document.getElementById('docs-data').textContent);
  var DIAGRAMS = JSON.parse(document.getElementById('diagrams-data').textContent);
  var DIAGRAM_BY_SRC = {};
  DIAGRAMS.forEach(function (diagram) { DIAGRAM_BY_SRC[diagram.src] = diagram; });

  var BT = String.fromCharCode(96);
  var FENCE = BT + BT + BT;
  var INLINE_CODE = new RegExp(BT + '([^' + BT + ']+)' + BT, 'g');
  var CJK = /[\u3400-\u9fff\uf900-\ufaff]/;

  var modal = document.getElementById('modal');
  var modalTitle = document.getElementById('modal-title');
  var modalBody = document.getElementById('modal-body');
  var prevBtn = document.getElementById('prev-btn');
  var nextBtn = document.getElementById('next-btn');
  var zoomBtn = document.getElementById('zoom-btn');
  var rawLink = document.getElementById('raw-link');
  var closeBtn = document.getElementById('close-btn');
  var backdrop = document.getElementById('modal-backdrop');

  var lastFocus = null;
  var mode = 'doc';
  var diagramIndex = -1;

  function escHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function escAttr(value) {
    return escHtml(value).replace(/'/g, '&#39;');
  }

  function renderInline(text, ctx) {
    var codes = [];
    var source = String(text).replace(INLINE_CODE, function (_, code) {
      codes.push(code);
      return '\u0000' + (codes.length - 1) + '\u0000';
    });
    source = escHtml(source);
    source = source.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (_, label, hrefRaw) {
      var href = hrefRaw.replace(/&amp;/g, '&');
      var key = href.replace(/^\.\//, '');
      var attrs;
      if (/^(https?:|mailto:)/.test(href)) {
        attrs = ' href="' + escAttr(href) + '" target="_blank" rel="noopener"';
      } else if (href.charAt(0) === '#') {
        attrs = ' href="' + escAttr(href) + '"';
      } else if (ctx.docs[key]) {
        attrs = ' href="#" data-doc="' + escAttr(key) + '"';
      } else if (ctx.diagramsBySrc[key]) {
        attrs = ' href="#" data-diagram="' + escAttr(key) + '"';
      } else {
        attrs = ' href="' + escAttr(href) + '" target="_blank" rel="noopener"';
      }
      return '<a' + attrs + '>' + label + '</a>';
    });
    source = source.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    source = source.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    source = source.replace(/\u0000(\d+)\u0000/g, function (_, index) {
      return '<code>' + escHtml(codes[+index]) + '</code>';
    });
    return source;
  }

  function joinLines(lines) {
    return lines.reduce(function (acc, line) {
      if (!acc) return line.trim();
      var left = acc.charAt(acc.length - 1);
      var right = line.trim().charAt(0);
      return acc + ((CJK.test(left) && CJK.test(right)) ? '' : ' ') + line.trim();
    }, '');
  }

  function isTableSeparator(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
  }

  function splitRow(line) {
    var text = line.trim();
    if (text.charAt(0) === '|') text = text.slice(1);
    if (text.charAt(text.length - 1) === '|') text = text.slice(0, -1);
    return text.split('|').map(function (cell) { return cell.trim(); });
  }

  function isBlockStart(line, next) {
    if (!line) return true;
    if (/^#{1,6}\s/.test(line)) return true;
    if (line.indexOf(FENCE) === 0) return true;
    if (/^---+\s*$/.test(line)) return true;
    if (/^\s*>\s?/.test(line)) return true;
    if (/^\s*[-*]\s+/.test(line)) return true;
    if (/^\s*\d+\.\s+/.test(line)) return true;
    if (line.indexOf('|') >= 0 && next !== undefined && isTableSeparator(next)) return true;
    return false;
  }

  function renderMarkdown(markdown, docName, ctx) {
    var lines = String(markdown).replace(/\r\n?/g, '\n').split('\n');
    var out = [];
    var index = 0;
    while (index < lines.length) {
      var line = lines[index];
      if (!line.trim()) { index += 1; continue; }

      if (line.indexOf(FENCE) === 0) {
        var lang = line.slice(3).trim();
        var code = [];
        index += 1;
        while (index < lines.length && lines[index].indexOf(FENCE) !== 0) {
          code.push(lines[index]);
          index += 1;
        }
        if (index < lines.length) index += 1;
        out.push('<pre><code' + (lang ? ' class="lang-' + escAttr(lang) + '"' : '') + '>' + escHtml(code.join('\n')) + '</code></pre>');
        continue;
      }

      var heading = line.match(/^(#{1,6})\s+(.*)$/);
      if (heading) {
        var level = heading[1].length;
        out.push('<h' + level + '>' + renderInline(heading[2], ctx) + '</h' + level + '>');
        index += 1;
        continue;
      }

      if (/^---+\s*$/.test(line)) { out.push('<hr>'); index += 1; continue; }

      if (/^\s*>\s?/.test(line)) {
        var quote = [];
        while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
          quote.push(lines[index].replace(/^\s*>\s?/, ''));
          index += 1;
        }
        out.push('<blockquote>' + quote.filter(function (item) { return item.trim(); }).map(function (item) {
          return '<p>' + renderInline(item.trim(), ctx) + '</p>';
        }).join('') + '</blockquote>');
        continue;
      }

      if (line.indexOf('|') >= 0 && index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
        var header = splitRow(line);
        index += 2;
        var rows = [];
        while (index < lines.length && lines[index].trim() && lines[index].indexOf('|') >= 0) {
          rows.push(splitRow(lines[index]));
          index += 1;
        }
        var headHtml = header.map(function (cell) { return '<th>' + renderInline(cell, ctx) + '</th>'; }).join('');
        var bodyHtml = rows.map(function (row) {
          return '<tr>' + row.map(function (cell) { return '<td>' + renderInline(cell, ctx) + '</td>'; }).join('') + '</tr>';
        }).join('');
        out.push('<div class="table-wrap"><table><thead><tr>' + headHtml + '</tr></thead><tbody>' + bodyHtml + '</tbody></table></div>');
        continue;
      }

      if (/^\s*[-*]\s+/.test(line) || /^\s*\d+\.\s+/.test(line)) {
        var ordered = /^\s*\d+\.\s+/.test(line);
        var items = [];
        while (index < lines.length && (/^\s*[-*]\s+/.test(lines[index]) || /^\s*\d+\.\s+/.test(lines[index]) || /^\s{2,}\S/.test(lines[index]))) {
          if (/^\s*[-*]\s+/.test(lines[index]) || /^\s*\d+\.\s+/.test(lines[index])) {
            items.push(lines[index].replace(/^\s*(?:[-*]|\d+\.)\s+/, ''));
          } else if (items.length) {
            items[items.length - 1] += ' ' + lines[index].trim();
          }
          index += 1;
        }
        var tag = ordered ? 'ol' : 'ul';
        out.push('<' + tag + '>' + items.map(function (item) { return '<li>' + renderInline(item, ctx) + '</li>'; }).join('') + '</' + tag + '>');
        continue;
      }

      var paragraph = [];
      while (index < lines.length && lines[index].trim() && !isBlockStart(lines[index], lines[index + 1])) {
        paragraph.push(lines[index]);
        index += 1;
      }
      if (!paragraph.length) {
        out.push('<p>' + renderInline(line, ctx) + '</p>');
        index += 1;
      } else {
        out.push('<p>' + renderInline(joinLines(paragraph), ctx) + '</p>');
      }
    }
    return out.join('\n');
  }

  function openShell() {
    if (!modal.hidden) return;
    lastFocus = document.activeElement;
    modal.hidden = false;
    modal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('modal-open');
  }

  function closeModal() {
    if (modal.hidden) return;
    modal.hidden = true;
    modal.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('modal-open');
    modalBody.innerHTML = '';
    mode = 'doc';
    diagramIndex = -1;
    if (lastFocus && typeof lastFocus.focus === 'function') lastFocus.focus();
    lastFocus = null;
  }

  function configureHeader(title, showPager, showZoom, rawHref, rawLabel) {
    modalTitle.textContent = title;
    prevBtn.hidden = !showPager;
    nextBtn.hidden = !showPager;
    zoomBtn.hidden = !showZoom;
    rawLink.hidden = !rawHref;
    if (rawHref) {
      rawLink.href = rawHref;
      rawLink.textContent = rawLabel;
    }
    closeBtn.focus();
  }

  function showDiagram(requested) {
    if (!DIAGRAMS.length) return;
    openShell();
    mode = 'image';
    diagramIndex = ((requested % DIAGRAMS.length) + DIAGRAMS.length) % DIAGRAMS.length;
    var diagram = DIAGRAMS[diagramIndex];
    var number = String(diagramIndex + 1).padStart(2, '0');
    var total = String(DIAGRAMS.length).padStart(2, '0');
    configureHeader('图 ' + number + '/' + total + ' · ' + diagram.title, true, true, diagram.src, '原始 SVG');
    modalBody.className = 'modal-body image-mode';
    modalBody.innerHTML = '<div class="diagram-stage" id="diagram-stage">' +
      '<img id="modal-image" class="modal-image" src="' + escAttr(diagram.src) + '" alt="' + escAttr(diagram.title) + '"' +
      (diagram.width ? ' width="' + diagram.width + '"' : '') +
      (diagram.height ? ' height="' + diagram.height + '"' : '') + '>' +
      '</div>';
    modalBody.scrollTop = 0;
    zoomBtn.textContent = '原始尺寸';
    zoomBtn.setAttribute('aria-pressed', 'false');
    var image = document.getElementById('modal-image');
    if (image) image.addEventListener('dblclick', toggleZoom);
  }

  function showDoc(name) {
    var doc = DOCS[name];
    if (!doc) return;
    openShell();
    mode = 'doc';
    configureHeader(doc.title, false, false, name, '原始 Markdown');
    modalBody.className = 'modal-body doc-mode';
    modalBody.innerHTML = '<article class="md">' + renderMarkdown(doc.content, name, {
      docs: DOCS,
      diagramsBySrc: DIAGRAM_BY_SRC
    }) + '</article>';
    modalBody.scrollTop = 0;
  }

  function toggleZoom() {
    var image = document.getElementById('modal-image');
    var stage = document.getElementById('diagram-stage');
    if (!image || !stage) return;
    var actual = image.classList.toggle('actual');
    stage.classList.toggle('zoomed', actual);
    zoomBtn.textContent = actual ? '适应窗口' : '原始尺寸';
    zoomBtn.setAttribute('aria-pressed', actual ? 'true' : 'false');
  }

  function trapFocus(event) {
    var focusables = Array.prototype.slice.call(
      modal.querySelectorAll('button:not([hidden]), a[href]:not([hidden])')
    ).filter(function (element) { return element.offsetParent !== null; });
    if (!focusables.length) return;
    var first = focusables[0];
    var last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  document.addEventListener('click', function (event) {
    var card = event.target.closest('[data-open-diagram]');
    if (card) {
      event.preventDefault();
      showDiagram(parseInt(card.getAttribute('data-open-diagram'), 10));
      return;
    }
    var docCard = event.target.closest('[data-open-doc]');
    if (docCard) {
      event.preventDefault();
      showDoc(docCard.getAttribute('data-open-doc'));
      return;
    }
    var link = event.target.closest('a[data-doc], a[data-diagram]');
    if (link) {
      event.preventDefault();
      if (link.hasAttribute('data-doc')) {
        showDoc(link.getAttribute('data-doc'));
      } else {
        var wanted = link.getAttribute('data-diagram');
        var found = -1;
        DIAGRAMS.forEach(function (diagram, position) { if (diagram.src === wanted) found = position; });
        if (found >= 0) showDiagram(found);
      }
    }
  });

  document.addEventListener('keydown', function (event) {
    if (!modal.hidden) {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeModal();
        return;
      }
      if (event.key === 'Tab') {
        trapFocus(event);
        return;
      }
      if (mode === 'image') {
        if (event.key === 'ArrowLeft') {
          event.preventDefault();
          showDiagram(diagramIndex - 1);
          return;
        }
        if (event.key === 'ArrowRight') {
          event.preventDefault();
          showDiagram(diagramIndex + 1);
          return;
        }
      }
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') {
      var card = event.target.closest('[data-open-diagram], [data-open-doc]');
      if (card) {
        event.preventDefault();
        card.click();
      }
    }
  });

  closeBtn.addEventListener('click', closeModal);
  backdrop.addEventListener('click', closeModal);
  prevBtn.addEventListener('click', function () { showDiagram(diagramIndex - 1); });
  nextBtn.addEventListener('click', function () { showDiagram(diagramIndex + 1); });
  zoomBtn.addEventListener('click', toggleZoom);
})();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
