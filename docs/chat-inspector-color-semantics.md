# Chat Inspector Color Semantics

This file records the current UI-facing color semantics for the chat run inspector only, so later UI work does not drift.

## Scope

Applies to:

- run inspector
- inspector-only expanded tool detail views inside the inspector

Does not yet include:

- approval state colors
- risk state colors
- success / failure / cancelled status colors
- outer chat bubbles
- normal run cards outside the inspector
- chat-list previews or other surrounding surfaces

Those states are intentionally kept outside the tool-call sentence semantics for now.
Outside the inspector, tool-related text should remain visually neutral instead of using multi-semantic token colors.

## Display Semantics

We do not map every runtime capability or tool type to its own color.
Instead, tool-call text is compressed into a small set of display semantics.

### 1. Action / Tool Verb

Examples:

- `Read`
- `Search`
- `Capture`
- `Edit`
- `Write`

Color:

- `#007AFF`

Rule:

- Use for the main action word at the beginning of the call sentence.

### 2. Target Object

Examples:

- file names
- query subjects
- inspected entities
- main object being acted on

Color:

- `#7C3AED`

Rule:

- Use for the direct object of the tool action.

### 3. Source / Scope / Range / Path Context

Examples:

- `from OpenClaw`
- `in runtime/src/...`
- `lines 2290-2470`
- source file or source dataset references

Color:

- `#16A34A`

Rule:

- Use for where the action is scoped, sourced from, or narrowed to.

### 4. Neutral Connectors

Examples:

- `from`
- `at`
- `in`
- `for`
- `to`

Color:

- `#1F2937`

Rule:

- Keep grammar words dark and quiet so they do not compete with semantic tokens.

### 5. Result Body

Examples:

- tool observations
- returned snippets
- follow-up explanation under the call

Color:

- `#64748B`

Rule:

- Use for the indented result text under a tool call.

### 6. Ownership Connector

Examples:

- the `└` connector that visually attaches a result to the call above it

Color:

- `#CDD6F4`

Rule:

- Only for structural attachment, not semantic content.

## Why This Compression Exists

Runtime currently has more tool names and more policy capability kinds than the UI should expose as separate colors.
The inspector should read like structured activity, not like a heatmap.

Current implementation reference:

- canonical tool names: `41`
- policy capability semantics: `19`

UI should continue to use the compressed display semantics above unless there is a strong product reason to expand them.
That compression is only for inspector readability, not for the broader chat UI.

## Anti-Patterns

Do not:

- assign one unique color per tool name
- assign one unique color per policy capability kind
- put status colors into the tool-call sentence by default
- color whole sentences with one flat color when they contain multiple semantic parts
- use muddy brown / bronze accents for scope tokens in this inspector
- spread this semantic token coloring into normal chat bubbles, run cards, or outer chat surfaces

## Current Palette Summary

- action: `#007AFF`
- target: `#7C3AED`
- source/scope/range: `#16A34A`
- connector: `#1F2937`
- result: `#64748B`
- ownership connector: `#CDD6F4`
