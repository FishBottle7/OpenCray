# Markdown Selection Link Copy Plan

## Scope

This plan covers the second-stage hyperlink copy behavior for selectable markdown content in:

- chat message bubbles
- markdown file previews in the Files screen
- markdown text previews opened from chat

The goal is narrower than full rich-text export. This work only upgrades hyperlink-aware copy for user selections inside rendered markdown.

## Target Behavior

When the user copies a markdown selection:

- if the selection fully contains one or more rendered hyperlinks, preserve those hyperlinks in the HTML clipboard payload
- for plain-text destinations, replace each fully selected hyperlink with its raw `href`
- if the selection cuts through only part of a hyperlink label, copy that fragment as plain text only
- if the selection has no fully selected hyperlinks, keep the current plain-text-only behavior

Examples:

- selecting `docs` from `[docs](https://opencray.dev/docs)`:
  plain text becomes `https://opencray.dev/docs`
  HTML keeps `<a href="https://opencray.dev/docs">docs</a>`
- selecting only `doc` from the same link:
  plain text stays `doc`
  no HTML hyperlink payload is emitted
- selecting `Open docs now` where `docs` is a full link inside the selection:
  plain text becomes `Open https://opencray.dev/docs now`
  HTML preserves the anchor only for the fully selected `docs` span

## Current Constraints

- chat bubble selection currently tracks selected plain text, but not structured markdown link metadata
- markdown file previews rely on default selectable markdown behavior and do not intercept copy actions
- whole-message copy is already implemented separately and should remain unchanged

## Chosen Approach

### 1. Add a reusable selectable markdown wrapper

Introduce a shared widget in `flutter_app/lib/core/widgets/opencray_markdown.dart` that:

- renders markdown through the existing `OpenCrayMarkdownBody`
- wraps it in `SelectionArea`
- optionally exposes the latest selected plain text and local selection range
- optionally overrides the context-menu copy action

This keeps chat bubbles and file previews on the same selection pipeline instead of duplicating selection wiring in multiple screens.

### 2. Add markdown selection clipboard projection helpers

Build a markdown selection projection from the parsed AST that records:

- visible text segments in render order
- hyperlink spans with display text and `href`

Use that projection to compute clipboard payloads for arbitrary selections:

- fully selected link span -> raw `href` in plain text, anchor in HTML
- partially selected link span -> selected visible text only

The helper should accept:

- markdown source
- selected plain text
- optional local start/end offsets when available

If offsets are not available, fall back to matching the selected plain text against the projected visible text. If multiple ambiguous matches produce different hyperlink payloads, fall back to plain text only.

### 3. Wire the helper into chat bubble copy

Update chat message menu copy behavior:

- keep whole-message copy behavior unchanged when there is no text selection
- when there is a text selection, attempt hyperlink-aware selection copy first
- if the helper returns no rich payload, keep the existing plain-text selection copy

### 4. Wire the helper into markdown file previews

Replace the current direct selectable markdown usage in file previews with the reusable selectable markdown wrapper so the copy button in the native selection toolbar can:

- emit rich clipboard payloads when full links are selected
- fall back to default plain-text copy otherwise

This applies to:

- Files screen markdown previews
- chat markdown text previews

## Guardrails

- do not change tap-to-open markdown links
- do not change whole-message copy semantics from the first-stage implementation
- do not attempt to preserve non-link markdown styling for partial text selections in this phase
- if selection matching is ambiguous, prefer plain text over incorrect hyperlink preservation

## Verification

Add or update tests for:

- markdown selection payload helper
- chat selection copy for a fully selected hyperlink
- chat selection copy for a partial hyperlink fragment
- files/chat markdown preview copy action preserving hyperlinks for full-link selections

Run:

- `dart analyze flutter_app`
- targeted Flutter tests for markdown, chat menu copy, and markdown preview selection copy
- `./gradlew.bat :app:compileDebugKotlin`
