# Chat Streaming Performance Plan

## Problem

Runtime streaming currently arrives incrementally, but each visible delta still
maps the latest runtime snapshot back into full chat state. That remaps
`messages` and `runTraces`, rebuilds the full chat scroll body, and may schedule
a bottom-scroll animation. Fast deltas therefore compete with expensive layout
work, which can make streaming feel slower than the API and can cause visible
scroll jitter when process, status, and final bubbles are inserted.

## First Patch

- Keep frame-level runtime projection coalescing at 16 ms, but skip visible
  state updates when the projected messages and traces are equivalent.
- Preserve open inspector updates: if a run inspector is open, keep publishing
  fresh trace objects even when the compact chat projection is visually
  equivalent.
- Capture whether the user was already near the bottom before applying a host or
  runtime update. Only follow new streaming content when the viewport was pinned
  to the bottom, or when the caller explicitly forces the initial jump.
- Coalesce repeated scroll-to-bottom requests into one post-frame callback and
  use non-animated follow for streaming projection updates to avoid stacked
  220 ms animations.
- Show a small breathing tail indicator on streaming message bubbles, including
  live assistant drafts, assistant phase bubbles, and running process output,
  without adding the indicator to selectable or copied markdown text.

## Follow-up Work

- Split high-frequency text streams, such as assistant drafts and process
  output, into keyed local notifiers so only the active bubble/status line
  rebuilds.
- Move long chat threads from `SingleChildScrollView` plus `Column` toward a
  keyed `ListView` or sliver list after the low-risk scroll behavior is stable.
- Keep runtime and inspector state on a single projection path. Avoid adding a
  second UI-owned copy of agent context; local UI state should only hold
  transient viewport, selection, and overlay information.
