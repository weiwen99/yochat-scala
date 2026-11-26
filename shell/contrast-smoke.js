"use strict";

// Checks the design tokens that carry text and control boundaries. This is a
// token-level gate, not a full WCAG audit: it catches the two regressions that
// made the S1 UI fail AA contrast on paper-2 and on input/button borders.

const fs = require("node:fs");
const path = require("node:path");

const css = fs.readFileSync(path.join(__dirname, "..", "server", "resources", "public", "app.css"), "utf8");
const lightBlock = matchBlock(css, /:root\s*\{([\s\S]*?)\}/);
const darkBlock = matchBlock(css, /@media\s*\(prefers-color-scheme:\s*dark\)\s*\{\s*:root\s*\{([\s\S]*?)\}/);

function matchBlock(source, pattern) {
  const match = source.match(pattern);
  if (!match) throw new Error(`missing CSS block ${pattern}`);
  return match[1];
}

function token(block, name) {
  const pattern = new RegExp(`--${name.replaceAll("-", "\\-")}:\\s*(#[0-9a-fA-F]{6})`);
  const match = block.match(pattern);
  if (!match) throw new Error(`missing token --${name}`);
  return match[1];
}

function hex(value) {
  return value.replace("#", "").match(/.{2}/g).map((pair) => Number.parseInt(pair, 16) / 255);
}

function luminance(value) {
  const [r, g, b] = hex(value).map((channel) => (channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(foreground, background) {
  const values = [luminance(foreground), luminance(background)].sort((a, b) => a - b);
  return (values[1] + 0.05) / (values[0] + 0.05);
}

function usesControlLine(source, selector) {
  const pattern = new RegExp(`\\${selector}\\s*\\{([\\s\\S]*?)\\}`, "g");
  for (let match = pattern.exec(source); match; match = pattern.exec(source)) {
    if (match[1].includes("var(--control-line)")) return true;
  }
  return false;
}

function tokenSet(block, label) {
  return {
    label,
    paper: token(block, "paper"),
    paper2: token(block, "paper-2"),
    sheet: token(block, "sheet"),
    muted: token(block, "muted"),
    controlLine: token(block, "control-line"),
    accent: token(block, "accent"),
    rail: token(block, "rail")
  };
}

const themes = [tokenSet(lightBlock, "light"), tokenSet(darkBlock, "dark")];
const checks = [];

for (const theme of themes) {
  checks.push([`${theme.label} muted/paper-2`, theme.muted, theme.paper2, 4.5]);
  checks.push([`${theme.label} muted/paper`, theme.muted, theme.paper, 4.5]);
  checks.push([`${theme.label} muted/sheet`, theme.muted, theme.sheet, 4.5]);
  checks.push([`${theme.label} control/paper`, theme.controlLine, theme.paper, 3.0]);
  checks.push([`${theme.label} control/sheet`, theme.controlLine, theme.sheet, 3.0]);
  checks.push([`${theme.label} control/paper-2`, theme.controlLine, theme.paper2, 3.0]);
  checks.push([`${theme.label} focus/paper`, theme.accent, theme.paper, 3.0]);
  checks.push([`${theme.label} focus/rail`, theme.accent, theme.rail, 3.0]);
}

const failures = checks.filter(([, foreground, background, minimum]) => ratio(foreground, background) < minimum);

for (const [label, foreground, background, minimum] of checks) {
  const actual = ratio(foreground, background);
  console.log(`${label}: ${actual.toFixed(2)}:1 (minimum ${minimum.toFixed(1)}:1)`);
}

const controlSelectors = [".icon-button", ".back-button"];
const selectorFailures = controlSelectors.filter((selector) => !usesControlLine(css, selector));
for (const selector of controlSelectors) {
  console.log(`${selector} boundary: ${selectorFailures.includes(selector) ? "FAIL" : "PASS"}`);
}

if (failures.length > 0 || selectorFailures.length > 0) {
  console.error(`CONTRAST FAIL checks=${checks.length} contrastFailures=${failures.length} selectorFailures=${selectorFailures.length}`);
  process.exit(1);
}

console.log(`CONTRAST PASS checks=${checks.length} selectors=${controlSelectors.length}`);
