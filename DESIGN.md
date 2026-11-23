---
name: YoChat
description: A private correspondence desk for two people, built around delivery certainty.
colors:
  paper: "#f3f2ed"
  paper-2: "#e9e7df"
  sheet: "#faf9f5"
  ink: "#18191b"
  muted: "#666a70"
  line: "#d6d3c9"
  line-strong: "#b8b4a8"
  accent: "#b94732"
  green: "#2f6b55"
  rail: "#1b1c1e"
  rail-ink: "#f2f0e8"
  bubble-own: "#18191b"
typography:
  display:
    fontFamily: "-apple-system, BlinkMacSystemFont, Avenir Next, PingFang SC, Hiragino Sans GB, Noto Sans CJK SC, Segoe UI, sans-serif"
    fontSize: "34px"
    fontWeight: 550
    lineHeight: 1.08
    letterSpacing: "-0.06em"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, Avenir Next, PingFang SC, Hiragino Sans GB, Noto Sans CJK SC, Segoe UI, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.65
    letterSpacing: "0.004em"
  label:
    fontFamily: "SF Mono, JetBrains Mono, Menlo, Consolas, monospace"
    fontSize: "9px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "0.14em"
rounded:
  square: "2px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "20px"
  xl: "28px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    rounded: "{rounded.square}"
    padding: "7px 13px"
  button-primary-hover:
    backgroundColor: "{colors.accent}"
    textColor: "#fff8f4"
  button-secondary:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
    rounded: "{rounded.square}"
    padding: "7px 13px"
  field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.square}"
    padding: "7px 9px"
---

# Design System: YoChat

## Overview

**Creative North Star: "The Correspondence Score"**

YoChat should feel like a private correspondence desk, not a generic chat shell. The working surface is calm paper and ink with a single cinnabar signal; the density stays high enough for a tool, but the type, rules, and whitespace make it feel authored. The interface is quiet at rest and precise when state changes.

The signature is the **delivery score**: a narrow rail beside each message whose square mark fills only as far as the truth goes. A dotted mark waits; a half-filled mark is a relay handoff; a solid mark is a peer acknowledgement. This is the product's honesty made visible, not decoration.

**Key Characteristics:** paper and ink; one cinnabar accent; hairline rules over shadows; square marks; tabular safety numbers; restrained 180ms state motion.

## Colors

The palette is restrained paper/ink with a single warm signal, plus one cool green for verified/online state.

### Primary
- **Cinnabar** (#b94732): primary action hover, focus ring, errors, failure marks, and the only decorative rule.
- **Ink** (#18191b): primary text, primary buttons, own message blocks, selected marks.

### Neutral
- **Score Paper** (#f3f2ed): the app ground.
- **Paper Shadow** (#e9e7df): the conversation rail and composer ground.
- **Sheet** (#faf9f5): raised panels, peer messages, and the communication ledger.
- **Graphite** (#666a70): secondary text, labels, timestamps, and metadata.
- **Hairline** (#d6d3c9): all separators; **Hairline Strong** (#b8b4a8): field and route edges.
- **Rail Ink** (#1b1c1e): the left navigation rail.

### Named Rules
**The One Signal Rule.** Cinnabar never fills a passive surface. It appears on actions, focus, connection failure, and the single rule that starts a section.

**The Honest Fill Rule.** A delivery mark is never fully filled for a relay handoff. Solid means the peer acknowledged; half means the relay accepted; dotted means local waiting.

## Typography

**Display Font:** system sans stack with Avenir Next and PingFang SC
**Body Font:** the same stack
**Label/Mono Font:** SF Mono / JetBrains Mono stack

**Character:** A working humanist sans paired with monospaced data. The sans carries the conversation; the mono carries safety codes, timestamps, route labels, and delivery states so they read as measurements.

### Hierarchy
- **Display** (550, 34–54px, 1.08): only the unlock story headline.
- **Headline** (600, 22–28px, 1.15): screen titles and empty-state titles.
- **Title** (650, 15–17px, 1.3): contact names and dialog titles.
- **Body** (400, 14px, 1.65): messages and prose; message bubbles cap at 560px.
- **Label** (400, 8–9px, 0.14em, uppercase): route labels, section labels, delivery legends, and timestamps.

### Named Rules
**The Tabular Truth Rule.** Safety numbers, timestamps, and delivery states use tabular figures and never wrap mid-code.

## Layout

Desktop is a three-column working grid: 72px ink rail, 292px conversation list, flexible workspace. The workspace itself becomes a chat column plus a 318px communication ledger. The ledger collapses below 1180px; the conversation list becomes a mobile switcher below 860px; the unlock story becomes a single column below 700px.

The unlock screen uses a split composition: a dark correspondence story at 1.08fr and a 410px form panel at 0.92fr. The panel is centered, not stretched.

Spacing rhythm: 8 / 12 / 20 / 28px. Headings get more space above than below. Hairline separators do the structural work; there is no card grid.

## Elevation & Depth

The system is flat by default. Borders and tonal steps make the structure. The only broad shadow is on the unlock card and the add-contact modal; toasts use the same shadow because they float above the working plane.

### Shadow Vocabulary
- **float** (`box-shadow: 0 18px 50px rgba(24, 25, 27, 0.14)` light / `0 18px 50px rgba(0, 0, 0, 0.38)` dark): modal, unlock card, toast.

### Named Rules
**The Flat Rows Rule.** Conversation rows, messages, route cards, and ledger sections never carry a shadow. Use a hairline and one tonal step.

## Shapes

Corners are square-to-2px. The system uses 1px hairlines, 9px delivery marks, 7px route nodes, and a rotated square for the relay stop. Pills are reserved for status chips and the waiting line. No large rounded cards, no hard offset shadows, no colored side bars.

## Components

### Buttons
- **Shape:** 2px radius; 34px minimum height; 12px semibold label.
- **Primary:** ink ground, paper text; hover shifts to cinnabar.
- **Secondary:** sheet ground, 1px hairline-strong border; hover raises the border to ink.
- **Text:** no border or background; muted until hover, then cinnabar. Used for copy, edit, add, and channel rebuild.

### Fields
- **Style:** 1px hairline-strong, 2px radius, paper ground inside sheet.
- **Focus:** cinnabar border plus 1px cinnabar ring; no glow.
- **Error:** inline text in cinnabar on a 10% cinnabar tint, never a red bordered card.

### Navigation
- **Rail:** 42px square icon buttons on ink; active state is a lighter square with a 1px inner line.
- **Conversation row:** avatar, name, truncated preview, mono time; selected state is sheet ground plus hairline.
- **Mobile:** the rail stays; the list and chat swap as full-width columns; the back button appears in the chat header.

### Delivery Score
- **Gutter:** 24px rail with a 1px line.
- **Peers:** hollow 9px circle.
- **Own states:** solid (delivered), half-filled (relay accepted), dotted (local wait), cinnabar (failed).
- **Motion:** fill and border transitions only, 180–200ms; no bouncing or looping.

### Communication Ledger
- **Route:** 本机 → 中继 → 对方 as a dotted line with a rotated relay diamond.
- **Safety:** 20 digits in mono, grouped by five, with a verified state in green.
- **Legend:** four delivery states with the same marks as the message gutter.
- **Footer:** local device short key and the sentence “消息按序保存在本机”.

### Conversation Composer
- Sheet ground inside the composer band; 1px field; square send button.
- Enter sends, Shift+Enter inserts a newline; the hint states both.
- When the safety code is unverified or the relay is offline, the hint explains the condition; the send button remains disabled only for the safety gate.

## Do's and Don'ts

### Do:
- **Do** use the delivery score beside every message that has a meaningful state.
- **Do** keep message text selectable with mouse and keyboard.
- **Do** use mono for safety codes, timestamps, route labels, and labels that behave like measurements.
- **Do** show “中继离线” and “已交给中继” distinctly; never collapse them into “已发送”.
- **Do** keep the light and dark token sets semantically identical.

### Don't:
- **Don't** use a fully filled mark for a local write or relay handoff.
- **Don't** add cards inside cards, colored left borders, gradients, glass panels, or hard offset shadows.
- **Don't** turn the route into a second navigation model; it is a status line, not a map to explore.
- **Don't** use emoji as icons or display fonts in controls and labels.
- **Don't** remove the system light/dark preference in favor of a manual theme switch.
