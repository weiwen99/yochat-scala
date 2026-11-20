#!/usr/bin/env python3
"""Build notes/index.html from notes/*.md and notes/diagrams/*.svg.

Usage:
    python3 notes/build.py

The generated page is fully self-contained (except the SVG image files), so it
works when opened directly from disk (file://): no CDN, no fetch, no server.
Edit the Markdown or SVG sources, then re-run this script.
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DIAGRAM_DIR = ROOT / "diagrams"
OUTPUT = ROOT / "index.html"

SVG_NS = "{http://www.w3.org/2000/svg}"


def esc(value: str) -> str:
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def first_heading(text: str, fallback: str) -> str:
    match = re.search(r"^#\s+(.+?)\s*$", text, re.MULTILINE)
    return match.group(1).strip() if match else fallback


def first_paragraph(text: str, limit: int = 130) -> str:
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


def svg_meta(path: Path) -> dict:
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
    except Exception as exc:  # noqa: BLE001 - build should keep going
        print(f"warning: cannot parse {path.name}: {exc}")
    return {"src": f"diagrams/{path.name}", "title": title, "desc": desc, "width": width, "height": height}


def main() -> None:
    diagram_paths = sorted(DIAGRAM_DIR.glob("*.svg"))
    diagrams = [svg_meta(path) for path in diagram_paths]

    doc_paths = sorted(ROOT.glob("*.md"), key=lambda p: (p.name != "glossary.md", p.name != "README.md", p.name))
    docs: dict[str, dict] = {}
    doc_cards: list[str] = []
    for path in doc_paths:
        text = path.read_text(encoding="utf-8")
        title = first_heading(text, path.stem)
        summary = first_paragraph(text)
        lines = len(text.splitlines())
        docs[path.name] = {"title": title, "content": text, "lines": lines}
        doc_cards.append(
            f'<article class="doc-card" tabindex="0" role="button" data-open-doc="{esc(path.name)}" '
            f'aria-label="阅读文档：{esc(title)}">'
            f'<span class="kicker">Markdown</span>'
            f'<h3>{esc(title)}</h3>'
            f'<p>{esc(summary)}</p>'
            f'<span class="hint">{lines} 行 · 点击阅读</span>'
            f"</article>"
        )

    diagram_cards: list[str] = []
    for index, diagram in enumerate(diagrams):
        diagram_cards.append(
            f'<article class="card" tabindex="0" role="button" data-open-diagram="{index}" '
            f'aria-label="查看大图：{esc(diagram["title"])}">'
            f'<div class="thumb"><img src="{esc(diagram["src"])}" alt="{esc(diagram["title"])}" loading="lazy"></div>'
            f'<div class="card-body">'
            f'<span class="kicker">图 {index + 1:02d}</span>'
            f'<h3>{esc(diagram["title"])}</h3>'
            f'<p>{esc(diagram["desc"])}</p>'
            f'<span class="hint">点击查看大图</span>'
            f"</div></article>"
        )

    docs_json = json.dumps(docs, ensure_ascii=False).replace("</", "<\\/")
    diagrams_json = json.dumps(diagrams, ensure_ascii=False).replace("</", "<\\/")
    built_at = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M")

    template = r"""<!doctype html>
<html lang="zh-Hans">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>YoChat 阅读材料</title>
<style>
  :root {
    --bg: #f5f4ef;
    --card: #fffefb;
    --ink: #18181b;
    --muted: #5b5b63;
    --line: #e4e1d8;
    --accent: #1f5f8b;
    --accent-soft: #e7f0f7;
    --warn: #8a6d0b;
    --warn-soft: #fff7d6;
  }
  * { box-sizing: border-box; }
  [hidden] { display: none !important; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--ink);
    font-family: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
    line-height: 1.7;
  }
  body.modal-open { overflow: hidden; }
  header, main, footer { max-width: 1280px; margin: 0 auto; }
  header { padding: 44px 24px 10px; }
  main { padding: 0 24px 60px; }
  h1 { margin: 0 0 8px; font-size: 34px; letter-spacing: .01em; }
  .sub { margin: 0; color: var(--muted); max-width: 900px; }
  .meta { margin-top: 14px; font-size: 13px; color: var(--muted); }
  .meta code, footer code, .md code { background: #ece9df; padding: 1px 6px; border-radius: 4px; }
  .toolbar { margin-top: 18px; display: flex; gap: 10px; flex-wrap: wrap; }
  .pill {
    display: inline-flex; align-items: center; gap: 6px;
    padding: 8px 14px; border: 1px solid var(--line); border-radius: 999px;
    background: var(--card); color: var(--accent); font: inherit; font-size: 14px;
    cursor: pointer; text-decoration: none;
  }
  .pill:hover { border-color: var(--accent); }
  .pill:focus-visible { outline: 3px solid rgba(31,95,139,.35); outline-offset: 2px; }
  .note {
    margin: 26px 0 8px; padding: 16px 18px; border-radius: 12px;
    background: var(--warn-soft); border: 1px solid #e8d48a; font-size: 14px;
  }
  .note strong { color: #7a5d06; }
  .section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin: 38px 0 16px; }
  .section-head h2 { margin: 0; font-size: 22px; }
  .section-head p { margin: 0; color: var(--muted); font-size: 13.5px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(520px, 1fr)); gap: 20px; }
  @media (max-width: 720px) { .grid { grid-template-columns: 1fr; } }
  .card, .doc-card {
    background: var(--card); border: 1px solid var(--line); border-radius: 16px;
    overflow: hidden; cursor: zoom-in; transition: border-color .15s, box-shadow .15s, transform .15s;
  }
  .card:hover, .doc-card:hover { border-color: #c9d6df; box-shadow: 0 8px 24px rgba(20,30,40,.08); transform: translateY(-1px); }
  .card:focus-visible, .doc-card:focus-visible { outline: 3px solid rgba(31,95,139,.45); outline-offset: 2px; }
  .thumb { background: #fcfcfa; border-bottom: 1px solid var(--line); aspect-ratio: 1400 / 900; overflow: hidden; }
  .thumb img { display: block; width: 100%; height: 100%; object-fit: contain; }
  .card-body { padding: 14px 18px 18px; }
  .doc-card { padding: 18px 20px 20px; cursor: pointer; }
  .doc-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
  .kicker { font-size: 11.5px; letter-spacing: .12em; text-transform: uppercase; color: var(--muted); }
  .card h3, .doc-card h3 { margin: 4px 0 6px; font-size: 16.5px; }
  .card p, .doc-card p { margin: 0; color: var(--muted); font-size: 13.5px; }
  .hint { display: inline-block; margin-top: 10px; font-size: 12.5px; color: var(--accent); }
  .modal[hidden] { display: none; }
  .modal { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; padding: 22px; }
  .modal-backdrop { position: absolute; inset: 0; background: rgba(20,20,22,.58); backdrop-filter: blur(3px); }
  .modal-panel {
    position: relative; display: flex; flex-direction: column;
    width: min(1400px, 96vw); height: min(92vh, 1100px);
    background: var(--card); border-radius: 16px; box-shadow: 0 24px 80px rgba(0,0,0,.35);
    overflow: hidden;
  }
  .modal-head {
    display: flex; align-items: center; gap: 10px;
    padding: 10px 12px 10px 20px; border-bottom: 1px solid var(--line);
    background: rgba(255,254,251,.97);
  }
  .modal-head h2 {
    flex: 1; min-width: 0; margin: 0; font-size: 16px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .modal-actions { display: flex; align-items: center; gap: 6px; }
  .icon-btn, .text-btn {
    border: 1px solid var(--line); background: #fff; color: var(--ink);
    border-radius: 9px; font: inherit; cursor: pointer; text-decoration: none;
  }
  .icon-btn { width: 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center; font-size: 20px; line-height: 1; }
  .text-btn { padding: 7px 10px; font-size: 13px; color: var(--accent); }
  .icon-btn:hover, .text-btn:hover { border-color: var(--accent); }
  .icon-btn:focus-visible, .text-btn:focus-visible { outline: 3px solid rgba(31,95,139,.35); outline-offset: 2px; }
  .icon-btn:disabled { opacity: .35; cursor: default; }
  .modal-body { flex: 1; min-height: 0; overflow: auto; }
  .modal-body.image-mode { padding: 14px; background: #f4f3ee; }
  .diagram-stage { min-height: 100%; display: flex; align-items: center; justify-content: center; }
  .diagram-stage.zoomed { align-items: flex-start; justify-content: flex-start; }
  .modal-image { display: block; max-width: 100%; max-height: calc(92vh - 110px); width: auto; height: auto; }
  .modal-image.actual { max-width: none; max-height: none; }
  .modal-body.doc-mode { padding: 22px 26px 40px; }
  .md { max-width: 980px; margin: 0 auto; font-size: 15px; }
  .md h1 { font-size: 28px; margin: 0 0 18px; }
  .md h2 { font-size: 22px; margin: 1.6em 0 .6em; padding-bottom: .3em; border-bottom: 1px solid var(--line); }
  .md h3 { font-size: 18px; margin: 1.4em 0 .5em; }
  .md h4 { font-size: 16px; margin: 1.2em 0 .4em; }
  .md p { margin: .8em 0; }
  .md a { color: var(--accent); }
  .md ul, .md ol { padding-left: 1.5em; margin: .7em 0; }
  .md li { margin: .3em 0; }
  .md blockquote {
    margin: 14px 0; padding: 10px 14px; border-left: 3px solid #c9b46a;
    background: #fff9e6; color: #5c4b12; border-radius: 0 8px 8px 0;
  }
  .md blockquote p { margin: .3em 0; }
  .md pre { background: #1f2430; color: #e8e6df; padding: 14px 16px; border-radius: 10px; overflow: auto; }
  .md pre code { background: none; color: inherit; padding: 0; }
  .md hr { border: none; border-top: 1px solid var(--line); margin: 26px 0; }
  .md .table-wrap { overflow-x: auto; margin: 16px 0; }
  .md table { border-collapse: collapse; width: 100%; font-size: 14px; }
  .md th, .md td { border: 1px solid var(--line); padding: 7px 10px; vertical-align: top; }
  .md th { background: #f3f1ea; text-align: left; white-space: nowrap; }
  .md tr:nth-child(even) td { background: #fbfaf6; }
  footer { padding: 0 24px 64px; color: var(--muted); font-size: 13.5px; }
  footer h2 { color: var(--ink); font-size: 18px; margin: 0 0 10px; }
  footer ol { margin: 8px 0; padding-left: 1.4em; }
  footer li { margin: 4px 0; }
  footer kbd {
    display: inline-block; min-width: 1.6em; padding: 1px 5px; text-align: center;
    border: 1px solid var(--line); border-bottom-width: 2px; border-radius: 5px;
    background: #fff; color: var(--ink); font-size: 12px;
  }
  noscript { display: block; margin: 16px 0; padding: 12px 14px; border: 1px solid #e8d48a; background: var(--warn-soft); }
</style>
</head>
<body>
<header>
  <h1>YoChat（有聊）阅读材料</h1>
  <p class="sub">
    一个服务端不持有用户数据的端到端加密好友间信使：纯状态机做领域逻辑，执行器负责崩溃安全地落盘与执行效果，iroh 负责点对点传输，SQLCipher 保存本地数据。
  </p>
  <p class="meta">
    基于 <code>YoChat-20261120</code> 快照整理，不属于项目源码；生成于 @@BUILT_AT@@；
    共 @@DIAGRAM_COUNT@@ 张图、@@DOC_COUNT@@ 份文档。项目文档当前多为 <code>draft</code>，图中数值与上限以项目文档和代码为准。
  </p>
  <div class="toolbar">
    <button class="pill" type="button" data-open-doc="glossary.md">术语表</button>
    <button class="pill" type="button" data-open-doc="README.md">阅读说明</button>
    <a class="pill" href="#diagrams">设计图</a>
    <a class="pill" href="#docs">文档</a>
  </div>
</header>

<main>
  <div class="note">
    <strong>阅读提示：</strong>
    首个交付增量只包含“用户与设备、好友与通讯录、一对一/自身会话、界面与通知、隐私与防护”；
    文件发送与群聊在后续阶段。平台验收先限 macOS，Windows/Linux 按计划留在后续 CI 阶段。
  </div>

  <noscript>
    本页的弹窗与 Markdown 渲染需要 JavaScript。也可以直接打开
    <a href="glossary.md">glossary.md</a> 和
    <a href="README.md">README.md</a>，或从 <a href="diagrams/">diagrams/</a> 打开原始 SVG。
  </noscript>

  <section id="diagrams">
    <div class="section-head">
      <h2>设计图</h2>
      <p>点击任意一张在弹窗中查看；← / → 切换，Esc 关闭，双击图片切换原始尺寸。</p>
    </div>
    <div class="grid">
@@DIAGRAM_CARDS@@
    </div>
  </section>

  <section id="docs">
    <div class="section-head">
      <h2>文档</h2>
      <p>Markdown 在弹窗中渲染；点击正文里的 .md / .svg 链接也会在弹窗中打开。</p>
    </div>
    <div class="doc-grid">
@@DOC_CARDS@@
    </div>
  </section>
</main>

<footer>
  <h2>维护方式</h2>
  <p>本页由 <code>notes/build.py</code> 生成；内容在构建时嵌入，因此离线可用。不要直接改 <code>index.html</code>。</p>
  <ol>
    <li>修改 <code>notes/*.md</code> 或 <code>notes/diagrams/*.svg</code>。</li>
    <li>运行 <code>python3 notes/build.py</code>。</li>
    <li>刷新本页。</li>
  </ol>
  <p>
    新增 SVG 时，把标题与说明写进 SVG 的 <code>&lt;title&gt;</code> 与 <code>&lt;desc&gt;</code>；
    新增 Markdown 时，用第一个 <code>#</code> 标题作为卡片标题，第一段作为摘要。
  </p>
  <p>快捷键：<kbd>Esc</kbd> 关闭弹窗，<kbd>←</kbd> / <kbd>→</kbd> 切换图片，双击图片切换原始尺寸。</p>
</footer>

<div class="modal" id="modal" hidden aria-hidden="true">
  <div class="modal-backdrop" id="modal-backdrop"></div>
  <div class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-head">
      <h2 id="modal-title">预览</h2>
      <div class="modal-actions">
        <button class="icon-btn" type="button" id="prev-btn" title="上一张（←）" aria-label="上一张">‹</button>
        <button class="icon-btn" type="button" id="next-btn" title="下一张（→）" aria-label="下一张">›</button>
        <button class="text-btn" type="button" id="zoom-btn" hidden aria-pressed="false">原始尺寸</button>
        <a class="text-btn" id="raw-link" href="#" target="_blank" rel="noopener">原始文件</a>
        <button class="icon-btn" type="button" id="close-btn" title="关闭（Esc）" aria-label="关闭">×</button>
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
        while (index < lines.length && (/^\s*[-*]\s+/.test(lines[index]) || /^\s*\d+\.\s+/.test(lines[index]))) {
          items.push(lines[index].replace(/^\s*(?:[-*]|\d+\.)\s+/, ''));
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

    html = (
        template.replace("@@BUILT_AT@@", built_at)
        .replace("@@DIAGRAM_COUNT@@", str(len(diagrams)))
        .replace("@@DOC_COUNT@@", str(len(docs)))
        .replace("@@DOCS_JSON@@", docs_json)
        .replace("@@DIAGRAMS_JSON@@", diagrams_json)
        .replace("@@DIAGRAM_CARDS@@", "\n".join(diagram_cards))
        .replace("@@DOC_CARDS@@", "\n".join(doc_cards))
    )
    OUTPUT.write_text(html, encoding="utf-8")
    print(f"wrote {OUTPUT} ({len(html)} bytes): {len(diagrams)} diagrams, {len(docs)} docs")


if __name__ == "__main__":
    main()
