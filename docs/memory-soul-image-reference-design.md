# OpenCray Memory / Soul Image Reference Design

Last updated: 2026-03-30

## Status

Approved design draft

## Related designs

- `docs/context-management-design.md`
- `docs/memory-design.md`
- `docs/agent-media-message-plan.md`
- `docs/multi-agent-runtime-design.md`
- `docs/digital-twin-corpus-import-design.md`

## Goal

Define how OpenCray should let `memory` and `soul` carry image references without turning either system into a binary blob store or a per-turn multimodal dump.

The target behavior is:

- `memory` can remember images as durable evidence or reference material
- `soul` can carry a bounded visual identity, such as the agent's portrait
- runtime remains text-first by default
- the agent can explicitly inspect the referenced image when visual detail is actually needed
- the design reuses the existing OpenCray image-attachment pipeline instead of inventing a new model transport

## Why this is needed

OpenCray already has the runtime capability to feed images into the model:

- user chat images can already enter the runtime as attachments
- `view_workspace_image` already attaches a workspace image into the next model turn
- final responses can already send image attachments back to the user

But `memory` and `soul` are still text-centric.

That creates a gap:

- a memory may need durable visual evidence, such as "this is the whiteboard photo from the earlier planning session"
- an agent may need a stable visual identity, such as "this is my canonical portrait"
- today those images do not have a first-class place in the context architecture

The design goal is not "always give the model more images".
The design goal is "store image references cleanly, summarize them into text, and only attach raw images when the agent truly needs direct visual inspection".

## Current architecture constraints

### 1. Memory is still text-first

`MemoryRecord` is currently centered on:

- `content`
- `tags`
- `extensions`

This is a good fit for image references, but not for storing image bytes.

### 2. Soul is structured and text-first

`SoulProfile` already holds stable behavioral and relational fields.
That should remain the runtime truth.

Visual identity belongs beside that structure, not as random prose in `customGuidance` and not as a raw image pushed into every turn.

### 3. Attachment transport already exists

OpenCray already supports the important transport behavior:

- image attachments can be carried into the model turn
- `view_workspace_image` can attach an image into the next turn for direct visual inspection

So this design should reuse the current attachment path.
It should not invent a second multimodal transport format for `memory` or `soul`.

### 4. Multi-agent boundaries matter

Per `docs/multi-agent-runtime-design.md`:

- each agent has its own `memory`
- each agent has its own private `SOUL.md`
- base soul should live in an agent-private root, not the tool-visible workspace

That means soul reference images must also stay inside an agent-private area unless the user explicitly chooses otherwise.

### 5. Session-private chat media is not durable truth

OpenCray chat attachments and run artifacts are useful sources, but they are not the correct long-term source of truth for `memory` or `soul`.

If a memory or soul update wants to retain an image beyond the current turn or session, the system must promote that image into a durable agent-owned location first.

## Design principles

### 1. Text remains the canonical runtime truth

For both `memory` and `soul`:

- text summary stays the default injected context
- image references remain supporting evidence or identity assets
- the runtime should not depend on raw image attachment every turn

### 2. Store references, never binaries

Do not store:

- base64 image payloads in `MemoryRecord`
- base64 image payloads in `SoulProfile`
- raw image bytes inside transcript-derived context

Store only durable references plus small metadata.

### 3. Explicit visual inspection beats implicit over-injection

If the model needs visual detail, it should explicitly inspect the image reference.

That keeps the normal context path:

- cheaper
- more bounded
- easier to trace
- less likely to pollute prompt budget

### 4. Soul image support must stay bounded

`soul` is not a general media library.

The steady-state design should support:

- one primary portrait
- a small set of auxiliary reference images

Not:

- unlimited galleries
- arbitrary emotional moodboards
- a large prompt-time visual corpus

### 5. Private assets stay private

Soul images should not be normal workspace files that ordinary file tools can browse or rewrite.

The agent may inspect a soul image through an explicit host-owned tool that reuses the existing attachment pipeline, but the underlying private file path should stay hidden from normal workspace tools.

### 6. No heuristic keyword fallback

Image understanding, caption extraction, and maintenance decisions should come from the model path, not keyword collision logic.

If the model path is unavailable, the system should defer or skip image-aware enrichment rather than fabricate a weak heuristic summary.

## Core data model

### Shared image reference shape

Both `memory` and `soul` should normalize onto the same conceptual image reference record.

Recommended shape:

```text
ImageReference
- refId
- role
- storageScope
- relativePath
- mimeType
- sha256
- width
- height
- caption
- summary
- sourceLabel
- sourceSessionId
- sourceMessageId
- createdAtEpochMs
```

Field intent:

- `refId`: stable identity inside the owning record
- `role`: why this image exists, such as `evidence`, `portrait`, or `reference`
- `storageScope`: where the durable file lives, such as `workspace` or `agent_private`
- `relativePath`: durable path within that scope
- `caption`: short human-readable label
- `summary`: compact text description suitable for retrieval and prompt injection
- `sha256`: dedupe and merge anchor

Important rule:

- `summary` is not optional in the steady-state design
- if an image is durable enough to enter `memory` or `soul`, it should also have a durable text summary

### Source handle shape before promotion

Before an image becomes a durable `ImageReference`, the runtime should treat it as a source handle instead of a trusted final path.

Recommended source handle shape:

```text
ImageReferenceSource
- sourceKind
- chatAttachmentId
- artifactId
- settingsAssetId
- relativePath
- displayName
- mimeType
- sourceSessionId
- sourceMessageId
```

Supported `sourceKind` values:

- `chat_attachment`
- `run_artifact`
- `settings_asset`
- `workspace_path`
- `durable_asset`

Why this matters:

- chat attachments and artifacts are not durable truth
- workspace paths may be durable, but only if they are already stable and intended to remain public
- the host must decide whether to copy, reference, or reject the source before persistence

The durable `ImageReference` record should therefore always be downstream of a promotion step.

### Product-visible source families

At the product level, the first rollout should explicitly support these source families:

- `agent_generated`
- `user_sent`
- `agent_settings`
- `workspace_existing`

They should map into source handles like this:

```text
agent_generated
- current-run generated image exposed as artifact_id
- maps to sourceKind = run_artifact
- if the generated image already has a stable workspace path, it may instead map to workspace_path

user_sent
- image uploaded by the user in chat
- maps to sourceKind = chat_attachment

agent_settings
- image chosen from the agent page or agent settings UI
- maps to sourceKind = settings_asset during ingestion

workspace_existing
- image that already exists under the agent's public workspace
- maps to sourceKind = workspace_path
```

This split is important:

- product and UI flows should be described in the four source families above
- runtime promotion logic should still converge onto one unified `ImageReferenceSource` model

### Source-family intent and defaults

Recommended defaults by source family:

- `agent_generated`: valid for `memory` and `soul`; if it only exists as a current-run artifact, it must be promoted before persistence
- `user_sent`: valid for `memory` and `soul`; never treat the session chat-media path as durable truth
- `agent_settings`: primarily for `soul`, especially portrait and reference-image authoring; should bypass session transcript storage as the source of truth
- `workspace_existing`: valid for `memory` and `soul`; `memory` may reference or copy, `soul` should still copy into private storage

### Agent-settings source handling

`agent_settings` deserves its own path because it is not conceptually the same as a chat upload.

Recommended behavior:

- the settings UI imports the chosen local image into a host-owned staging or durable area
- the runtime records it as `sourceKind = settings_asset`
- promotion into `soul` should then proceed without forcing the asset through session chat media

This keeps settings-driven portrait authoring:

- independent from session lifecycle
- independent from chat transcript replay
- aligned with the multi-agent rule that soul assets are private and host-owned

## Memory design

### What memory images are for

Memory image references are for cases where visual material should remain durable beyond the current session, for example:

- a user's handwritten whiteboard or sketch that was referenced in planning
- a photo of a device, desk setup, or component that matters later
- a screenshot that anchors a durable project fact
- an image that should back a persistent user preference or known reference object

Memory images are not meant to archive every chat picture automatically.

### Memory source of truth

`MemoryRecord.content` remains the primary semantic memory text.

Image references sit beside it.
They do not replace it.

Recommended steady-state storage shape:

```text
MemoryRecord
- content
- tags
- imageRefs: List<ImageReference>
- extensions
```

If implementation needs a short transition period, a serialized `image_refs_json` extension is acceptable only as an intermediate scaffold.
It should not be the desired end-state data model.

### Memory ingestion rules

When the runtime decides a memory should keep an image:

1. resolve the source image
2. promote it into a durable agent-owned location if the current source is session-private or run-private
3. generate a durable caption and summary through the model path
4. write the memory row with text content plus image references

Durable memory image sources may come from:

- a user-uploaded chat image
- an existing workspace image
- a generated artifact image
- a previously stored durable image already owned by the agent

Non-durable sources that must not remain as the final reference:

- transient run artifact aliases
- session-private chat media paths
- temporary cache paths

### Recommended memory promotion modes

Memory image promotion should support two modes:

- `copy_promote`
- `reference_promote`

`copy_promote` is the default.
Use it when the source is:

- a chat upload
- a run artifact
- a temporary file
- a file whose lifetime should not be coupled to the public workspace

`reference_promote` is only for the narrower case where:

- the source is already a stable workspace image
- the memory should intentionally keep pointing at that public workspace file
- future edits or deletion of that workspace file are acceptable product semantics

If those conditions are not clearly true, prefer `copy_promote`.

### Recommended durable location for memory images

For agent-scoped durable memory evidence, prefer an agent-owned path such as:

```text
agents/<agentId>/private/memory-media/<memoryId>/<filename>
```

Reasons:

- durable across sessions
- isolated per agent
- not mixed into the public workspace by default
- easier to treat as host-owned evidence storage

If the image already lives in the public workspace and should intentionally stay there, the reference may point at workspace storage instead of copying it.

### Memory retrieval behavior

Automatic memory recall should stay text-first.

By default, prompt injection should include:

- `MemoryRecord.content`
- a compact indication that image evidence exists
- selected image captions or summaries only when they add meaningful context

Example prompt shape:

```text
Memory:
- The user named the prototype "Northlight".
  Image evidence available: 1 reference.
  Visual summary: hand-drawn landing page wireframe with a top hero, two-column feature block, and dark footer.
```

The runtime should not automatically attach the raw image in normal recall.

### On-demand inspection for memory images

If the model needs direct visual detail, it should explicitly inspect the stored reference.

Recommended tool surface:

- `view_memory_image_reference(memory_id, ref_id)`

This tool should:

- resolve the durable image reference
- enforce policy on the host side
- reuse the existing image-attachment pipeline
- attach the image into the next model turn

The underlying transport should be the same class of transport already used by `view_workspace_image`.
The difference is only reference resolution and access control.

### Memory merge and update rules

Memory maintenance should treat image references as evidence, not as a free overwrite channel.

Recommended behavior:

- dedupe by `sha256` when possible
- merge additional images into the same memory when they support the same durable fact
- avoid replacing the only existing image reference unless maintenance explicitly decides the new image is the better canonical reference
- keep the text content authoritative even when image evidence changes

This matters because durable memory should evolve carefully, not swing wildly because one new image arrived.

## Soul design

### What soul images are for

Soul images exist to support stable visual identity, not to turn the soul system into a gallery.

Typical use cases:

- the agent's canonical portrait
- a small number of auxiliary references for consistent appearance
- a stable visual reference used when the user asks the agent to depict itself

### Soul source of truth

`SoulProfile` remains the behavioral source of truth.

Visual identity should be an explicit bounded sub-structure, for example:

```text
SoulVisualIdentity
- portraitSummary
- primaryPortrait: ImageReference?
- referenceImages: List<ImageReference>
```

Recommended steady-state soul shape:

```text
SoulProfile
- existing behavioral / relational fields
- visualIdentity: SoulVisualIdentity?
```

Important boundary:

- `portraitSummary` is what the runtime normally consumes
- `primaryPortrait` and `referenceImages` are backing assets, not default prompt payload

### Soul image storage

Soul images should live in the agent-private root, for example:

```text
agents/<agentId>/private/soul-assets/
  portrait/
  references/
```

These files are:

- host-owned
- not ordinary workspace files
- not directly exposed through general file tools

This matches the multi-agent design direction where `SOUL.md` is also private.

### Soul image update rules

Soul images should not change as casually as relational memory.

Recommended rule set:

- changing the primary portrait is a soul-authoring action, not a casual conversational side effect
- ordinary chat drift should not rewrite the portrait
- auxiliary references may be added carefully, but the set should stay small and curated
- if a new portrait conflicts with the current portrait summary, the system should reconcile explicitly instead of silently replacing the old truth
- soul image ingestion should always use `copy_promote`, not `reference_promote`, unless the product later adds an explicit host-controlled import flow for a public workspace file

This keeps `soul` aligned with the product direction that soul is stable and not lightly rewritten.

### Soul runtime consumption

In ordinary runs, the runtime should inject only compact text derived from `visualIdentity`, for example:

```text
Visual identity:
- Canonical portrait summary: calm young adult with short dark hair, neat coat, restrained expression, and practical style.
```

The runtime should not automatically attach the portrait image every turn.

### On-demand inspection for soul images

When the model actually needs direct visual inspection, such as:

- the user asks what the agent looks like
- the agent needs consistency for self-depiction or image generation
- a maintenance step needs to compare portrait evidence

the runtime should expose a dedicated tool such as:

- `view_soul_reference_image(ref_id)`

This tool should:

- resolve the private soul image on the host side
- not reveal the private file path
- reuse the same attachment transport used by existing image-view tooling

## Runtime and prompting rules

### Default rule: summary first

The default context pipeline should consume:

- memory text
- memory image summaries
- soul behavioral fields
- soul portrait summary

Not raw image attachments.

### Explicit inspection rule

Raw image attachment should happen only when:

- the current turn genuinely needs visual detail
- the model chooses an explicit image-reference view tool
- or the user directly uploads an image in the current message and the runtime already exposes it as a chat attachment

### Mode gating

If runtime modes disable `memory` or `soul`, their image-derived prompt layers must also disable.

Recommended behavior:

- `memory disabled`: do not inject memory image summaries and do not expose memory-image reference tools
- `soul disabled`: do not inject portrait summary and do not expose soul-image reference tools
- normal chat attachments remain independent of these toggles

This keeps the image-reference policy aligned with the existing context gating rules instead of leaking behavior through a side door.

### Attachment transport reuse

This design deliberately reuses the existing OpenCray attachment path.

That means:

- image references do not need a new model protocol
- the dedicated memory/soul tools only need to resolve references and then invoke the existing "attach image into next turn" behavior
- `view_workspace_image` remains valid for public workspace images
- new memory/soul tools are wrappers around the same attachment mechanism, not a second transport layer

## Summarization and enrichment

### Image summary generation

When a new durable image reference enters `memory` or `soul`, the system should run a bounded model step that produces:

- a short caption
- a compact visual summary suitable for retrieval and prompt injection

For `soul`, the model may additionally produce:

- a portrait summary
- optional appearance anchors that help keep future self-depiction consistent

### Why summaries matter

They let OpenCray stay text-first while still being genuinely multimodal.

The agent can often act correctly from:

- a compact caption
- a short visual summary

without having to re-open the image every time.

### No automatic raw-image dependency

The system should not require the same image to be reattached on every run just because it once entered `memory` or `soul`.

That would:

- waste prompt budget
- raise latency and cost
- make behavior less deterministic
- increase privacy exposure

## Storage and lifecycle

### Promotion rule

If an image originates from:

- a chat upload
- a current run artifact
- a temporary path

and the runtime decides that image belongs in durable `memory` or `soul`, it must first promote the file into a durable agent-owned location.

The memory or soul record should then point at that durable location, not at the original transient source.

### Promotion flow

The promotion flow should be host-owned and explicit.

Recommended flow:

```text
image source handle
  -> resolve source bytes and metadata
  -> validate type, size, and ownership
  -> choose promotion mode
  -> materialize durable asset if needed
  -> derive fingerprint and dimensions
  -> run model-based caption/summary extraction
  -> write ImageReference
  -> attach ImageReference to memory or soul record
```

The important part is that promotion finishes before the owning `memory` or `soul` record is committed.

### Recommended host-owned services

To keep the implementation clean, promotion should be split into a few bounded services.

Recommended shape:

```text
ImageReferencePromotionService
- promoteForMemory(agentId, memoryId, source)
- promoteForSoul(agentId, slot, source)

ImageAssetResolver
- resolve chat_attachment / artifact / workspace path into a readable file source

MemoryImageAssetStore
- copy or register durable memory assets

SoulImageAssetStore
- copy durable soul portrait/reference assets into agent-private storage

ImageSummaryExtractor
- generate caption and summary through the model path
```

Responsibilities:

- `ImageAssetResolver` understands where the source currently lives
- `ImageReferencePromotionService` owns the workflow and policy
- `MemoryImageAssetStore` and `SoulImageAssetStore` own destination-path conventions
- `ImageSummaryExtractor` turns the image into durable text context

### Source resolution rules

The source resolver should behave differently by source kind:

- `chat_attachment`: resolve from the current session attachment registry without exposing `.opencray/chat-media/...` back to the model
- `run_artifact`: resolve through the current run artifact mapping, not by trusting an arbitrary file path
- `settings_asset`: resolve through host-owned settings import metadata, not through the chat layer
- `workspace_path`: resolve through normal workspace path validation
- `durable_asset`: resolve only when the asset already belongs to the same agent and store family

This prevents the final durable reference from depending on unstable implementation paths.

### Promotion mode selection

The host should choose promotion mode from source type and target type.

Recommended default matrix:

```text
target = memory
- chat_attachment  -> copy_promote
- run_artifact     -> copy_promote
- settings_asset   -> copy_promote only when product flow intentionally creates memory from a settings-owned asset
- workspace_path   -> copy_promote by default, reference_promote only when explicitly allowed
- durable_asset    -> reuse existing reference when ownership matches

target = soul
- chat_attachment  -> copy_promote
- run_artifact     -> copy_promote
- settings_asset   -> copy_promote
- workspace_path   -> copy_promote
- durable_asset    -> reuse existing reference when ownership matches
```

That keeps `soul` private and keeps `memory` conservative by default.

### Source-family examples

Concrete examples for the four supported source families:

```text
1. agent_generated
   - the agent uses GenerateImage
   - the current run exposes artifact_id = img-123
   - soul updater decides this should become a portrait reference
   - promote sourceKind = run_artifact into agents/<agentId>/private/soul-assets/...

2. user_sent
   - the user uploads selfie.png in chat
   - the runtime sees chat_attachment_id = chat-image-1
   - memory updater decides this image supports a durable preference or fact
   - promote sourceKind = chat_attachment into agents/<agentId>/private/memory-media/...

3. agent_settings
   - the user chooses an avatar or portrait image in the agent settings page
   - the host stores a settings-owned imported asset
   - the runtime sees sourceKind = settings_asset
   - soul updater promotes it into the canonical private soul portrait location

4. workspace_existing
   - docs/mockups/agent-look.png already exists in workspace
   - the user or runtime selects that image as a reference
   - the runtime sees sourceKind = workspace_path
   - memory may reference or copy it; soul should copy it into private storage
```

### Durable asset materialization

For `copy_promote`, the host should:

1. open the source file from the resolved handle
2. validate supported image mime type and extension
3. compute `sha256`
4. read lightweight dimensions if available
5. choose a durable destination path
6. copy into the target store
7. verify the copied file before returning success

Recommended file naming policy:

- preserve a readable sanitized filename when practical
- include or group by strong content identity so duplicates can collapse cleanly
- do not expose raw private absolute paths to the model

One acceptable pattern is:

```text
agents/<agentId>/private/memory-media/<memoryId>/<sha12>-<safeName>.png
agents/<agentId>/private/soul-assets/portrait/<sha12>-<safeName>.png
agents/<agentId>/private/soul-assets/references/<refId>/<sha12>-<safeName>.png
```

The exact template may vary, but the path family should clearly encode:

- owner agent
- store family
- optional owning record
- stable content identity

### Summary extraction after materialization

After the asset is durable, the host should run a bounded model step to produce:

- `caption`
- `summary`

For `soul`, the step may additionally produce:

- `portraitSummary`
- optional appearance anchors used only in the soul layer

The model should inspect the newly materialized durable file, not the old transient source.
That ensures the summary matches what was actually persisted.

### Recommended strictness and failure semantics

Promotion should be stricter than ordinary chat attachment handling.

Recommended rules:

- if source resolution fails, do not write any reference
- if durable copy fails, do not write any reference
- if fingerprint or mime validation fails, do not write any reference
- if summary extraction fails, prefer `pending_enrichment` only for internal maintenance queues; do not pretend the reference is fully ready
- for `soul` primary portrait, require summary extraction before commit

This keeps `memory` and `soul` from accumulating half-broken visual references.

### Transaction and commit semantics

Promotion should behave as a small transaction:

1. stage durable asset
2. derive metadata
3. extract summary
4. commit owning `memory` or `soul` record
5. finalize the asset as referenced

If the owning record write fails after the asset copy succeeds, the host should either:

- delete the staged asset immediately
- or mark it as orphaned for later garbage collection

The system should never leave a committed `memory` or `soul` record pointing at an asset that was not durably written.

### Reuse and deduplication during promotion

If the asset store already holds the same content for the same agent and same store family, promotion may reuse the existing durable file instead of copying another identical file.

Recommended reuse anchor:

- `sha256`

Optional secondary checks:

- normalized mime type
- dimensions

This is especially useful when:

- the same portrait is submitted again in a later session
- the same memory evidence image is referenced by maintenance twice

### Relationship to existing OpenCray mechanisms

OpenCray already has two nearby patterns that this design should reuse conceptually:

- assistant and chat attachments are archived into session media under `.opencray/chat-media/...`
- `view_workspace_image` already resolves one readable image and attaches it into the next model turn

The new promotion path should mirror those patterns while changing the ownership boundary:

- session media archive is message storage
- memory/soul promotion is durable context storage

So the code path should likely share low-level file and metadata helpers, but not reuse session-media storage as the durable truth.

### Post-promotion inspection

Once promotion succeeds, later visual inspection should no longer go back to the original source handle.

Instead:

- memory references should resolve through `view_memory_image_reference(memory_id, ref_id)`
- soul references should resolve through `view_soul_reference_image(ref_id)`

Those tools should load the durable asset and then reuse the same "attach image into next turn" mechanism already used by workspace-image inspection.

### Deletion and cleanup

Cleanup rules should preserve referential integrity:

- deleting a memory row may release memory-private image assets that are no longer referenced elsewhere
- deleting or replacing a soul portrait should clean up old soul-private assets only after the new state is committed
- session-media cleanup must never break durable memory or soul references

### Deduplication

At the asset layer, dedupe by strong content identity where practical:

- `sha256`
- normalized mime type
- optional dimensions

This helps when the same user photo or portrait is reused across maintenance cycles.

## Debug and inspector requirements

The runtime and inspector should make image-reference behavior traceable without leaking it into the normal chat UI.

Recommended inspector-only visibility:

- which memory rows carried image references
- which summaries were injected
- whether the model explicitly inspected a memory or soul image
- which durable asset path or asset id was resolved internally
- whether a transient source had to be promoted before persistence

Important UX rule:

- explanation details and "why this memory/soul image was used" belong in debug surfaces, not in the ordinary chat transcript

## Non-goals

This design does not attempt to do the following in the first rollout:

- image embedding search
- automatic raw-image injection every turn
- unlimited soul image collections
- general-purpose human memory editing UI
- storing image bytes in memory or soul records
- broad workspace exposure of soul-private images

## Rollout plan

### Phase 1: data model and durable references

- add typed image-reference support to `memory`
- add bounded visual-identity support to `soul`
- define durable storage locations for memory-private and soul-private images
- ensure transient sources are promoted before persistence

### Phase 2: summary-first runtime integration

- inject memory image summaries into bounded memory prompt layers
- inject soul portrait summary into the soul prompt layer
- keep raw image attachments out of the default path

### Phase 3: explicit inspection tools

- add `view_memory_image_reference`
- add `view_soul_reference_image`
- implement both as host-owned reference resolvers that reuse the current attachment pipeline

### Phase 4: maintenance hardening

- add dedupe and merge rules for repeated memory evidence
- harden portrait replacement rules for soul updates
- surface promotion and image-summary lineage in debug/inspector tooling

## Recommended decisions

The recommended steady-state decisions are:

- `memory` stores text plus durable image references
- `soul` stores bounded visual identity plus private image references
- text summaries remain the default injected context
- raw images are attached only on explicit inspection
- durable references must never point at transient session or run media
- soul image access should go through host-owned tools, not normal workspace file access
- the implementation should reuse the current image-attachment runtime path end-to-end

## Short conclusion

OpenCray already has the hard part of multimodal transport: it can attach images into model turns.

What is missing is not another transport system.
What is missing is a clean context-layer design for:

- where `memory` stores image evidence
- where `soul` stores visual identity
- how both are summarized into text by default
- how the model can explicitly inspect them when necessary

The correct direction is therefore:

- references, not binaries
- summaries first, images on demand
- bounded soul visuals
- durable agent-owned storage
- reuse of the existing attachment pipeline
