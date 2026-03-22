# Digital Twin Graphiti Integration Design

Last updated: 2026-03-18

## Status

Draft implementation design for using Graphiti as the relationship-graph layer behind digital-twin corpus import.

## Goal

Define how OpenCray should use Graphiti to understand people, roles, and temporal relationship changes from imported chats or fictional works, while keeping OpenCray's existing `memory` and `soul` boundaries intact.

This design is specifically about:

- relationship extraction
- anchor-person or chosen-role centric retrieval
- simplest viable integration path for an independent Python extraction service
- later upgrade paths using Graphiti's official server or MCP entrypoints

This design is not an attempt to replace:

- workspace `SOUL.md`
- `runtime/soul/*`
- `MemoryWriter` / `MemoryStore`
- OpenCray's own prompt assembly and tool-policy pipeline

## Decision Summary

OpenCray should use Graphiti as a temporal relationship-graph sidecar, not as the full digital-twin runtime.

The core decisions are:

1. Graphiti owns imported entity, event, and relationship understanding.
2. The personality / relationship extraction service remains independent from the OpenCray agent runtime.
3. OpenCray still owns `memory`, `soul`, prompt composition, and policy.
4. The extraction service emits reviewable drafts and promoted runtime inputs; it does not become the runtime itself.
5. Imported chat or fiction should always be interpreted through an explicit `persona anchor`.
6. Retrieval should be ego-centric around that anchor instead of flattening the full graph into prompt text.
7. The simplest MVP integration should be direct Python invocation of Graphiti, not MCP and not OpenCray agent tools.

## Why Graphiti Helps

Graphiti is useful here because it is not just a vector-memory tool. It is built around temporally-aware graph memory:

- episodes are added over time instead of flattening all facts into one document
- relationships can change over time
- entity and edge extraction are first-class concepts
- graph search can be scoped and reranked
- custom entity and edge schemas are supported
- `group_id` namespaces let multiple twins or works stay isolated

That maps well to the real import problem:

- a person has multiple important relationships
- those relationships evolve
- fictional works contain character-centered world models
- the twin should not constantly bring up peripheral people unless they matter to the anchored person

## Why Graphiti Is Not The Whole Solution

Graphiti does not solve `soul`.

It can help answer:

- who matters to this person
- how close or tense those relationships are
- what events changed those relationships
- what this character knows from their point of view

It does not, by itself, solve:

- how the person speaks
- their value ordering
- conflict style
- uncertainty style
- repair style
- what they would never plausibly say

OpenCray should therefore keep the split already described in `docs/digital-twin-corpus-import-design.md`:

- Graphiti for relationship graph and temporal event understanding
- a separate personality extractor for `soul`
- OpenCray runtime memory and prompt layers for actual generation

## Upstream Capabilities That Matter

The Graphiti features that materially matter for OpenCray are:

- episode ingestion for messages and text corpus
- `group_id` namespacing for separate graphs
- custom entity and edge types
- graph search around a focused node
- multiple storage backends, including a local Kuzu option and service-oriented server options

Official upstream references:

- GitHub README: `graphiti-core`, storage backends, quick-start patterns
- Graphiti docs: Adding Episodes
- Graphiti docs: Custom Entity and Edge Types
- Graphiti docs: Graph Namespacing
- Graphiti docs: Searching
- Graphiti docs: Kuzu DB Configuration
- Graphiti `server/README.md`
- Graphiti `mcp_server/README.md`

## System Boundary Alignment

Graphiti should sit inside the independent extraction service, beside the OpenCray runtime, not inside the agent loop.

### Extraction Service Owns

- imported actor/entity graph
- temporal relationship events from imported corpus
- anchor-centered graph retrieval
- work-role perspective modeling
- graph-side provenance from imported source episodes
- projection into import drafts

### OpenCray Still Owns

- base persona authority in `SOUL.md`
- `SoulProfile` and effective runtime soul resolution
- durable memory writes into `MemoryStore`
- `RelationshipEvent` and `RelationshipState` persistence used by runtime
- prompt budgeting and prompt assembly
- approval, tool gating, and execution policy

### Service Boundary

Recommended deployment boundary:

```text
source corpus
  -> independent Python extraction service
    -> Graphiti
    -> soul/personality extractor
    -> draft projector
  -> OpenCray import review / publish
  -> SOUL.md + MemoryWriter + relationship planners
```

OpenCray does not need to call Graphiti from the agent tool layer for this design to work.

## Persona Anchor Model

The anchor concept is the most important application-specific layer on top of Graphiti.

Without it, the graph will be too socially broad. The agent will keep surfacing side characters and background relationships that feel strange in a one-on-one twin conversation.

### Anchor Types

OpenCray should support two anchor modes.

#### 1. Chat Twin Anchor

Use when importing real chat history.

- `anchor_person`: the person the user wants to talk to
- `current_user`: the real user importing the corpus
- import objective: reconstruct the world as it matters to `anchor_person`

The runtime should behave as if the user is talking to `anchor_person`, not to an omniscient narrator of everyone's relationships.

#### 2. Work Role Anchor

Use when importing fiction or authored works.

- `selected_role`: the role the user chooses to occupy
- `anchor_person`: the character the twin is meant to be
- `current_user_role_binding`: the role or character the user wants the agent to treat them as

This lets the user say:

- "Treat me as the protagonist"
- "Treat me as the second male lead"
- "Treat me as the younger sister"

The runtime should then reason from that chosen role's relationship graph, not from the entire novel's omniscient graph.

### Binding Records

OpenCray should persist two related but different objects at import time.

`TwinBinding` identifies the twin, anchor, source mode, and the role the real user is mapped to.

`SelectedRelationshipBinding` identifies the exact anchor-centered relationship lens the user chose for one-on-one interaction.

This separation is important because “the user is treated as X” and “the runtime is currently simulating anchor <-> X” are related, but not identical architectural decisions.

For example:

- the user may be mapped to a fictional role
- that role may be the selected relationship counterpart for this twin
- another future twin of the same anchor may map the user to a different counterpart without changing the base soul import

OpenCray should therefore normalize the names below.

#### Canonical Terms

- `selected_role`: UI language only; normalize it into `current_user_role_binding`
- `current_user_role_binding`: who the app user is treated as in the imported world
- `selected_relationship_binding`: which anchor-centered relationship lens is active for projection, overlay activation, and initial retrieval bias

OpenCray should persist a light twin binding object at import time:

```json
{
  "twin_id": "twin_lin_01",
  "anchor_person_id": "actor_lin",
  "interaction_mode": "chat_twin",
  "source_mode": "chat_history",
  "current_user_role_binding": {
    "type": "real_user",
    "entity_id": "actor_current_user"
  }
}
```

For fiction:

```json
{
  "twin_id": "twin_novel_02",
  "anchor_person_id": "char_female_lead",
  "interaction_mode": "work_role",
  "source_mode": "fiction_work",
  "current_user_role_binding": {
    "type": "fictional_character",
    "entity_id": "char_male_second_lead"
  }
}
```

OpenCray should also persist a relationship-selector result:

```json
{
  "binding_id": "relbind_twin_lin_01_current_user",
  "twin_id": "twin_lin_01",
  "anchor_person_id": "actor_lin",
  "counterpart_entity_id": "actor_current_user",
  "counterpart_binding_type": "real_user",
  "selection_source": "user_selected",
  "relationship_label": "private_chat_counterpart",
  "overlay_key": "counterpart:actor_current_user",
  "created_at": "2026-03-18T20:12:00Z",
  "updated_at": "2026-03-18T20:12:00Z"
}
```

#### Relationship Selector Model

The anchor alone is not enough for a believable twin. The import flow also needs a manual relationship selector.

The selector is not an auto-inference engine. Its job is simpler and stricter:

- enumerate anchor-centered relationship candidates
- let the user explicitly choose which relationship they want to map into the one-on-one twin
- persist that choice as `selected_relationship_binding`
- use that choice to filter projection and activate the correct relationship overlay

#### Candidate Enumeration

Recommended v1 candidate source:

- chat import: all non-anchor participants with direct turns, explicit mentions, or repeated reply links
- fiction import: all named non-anchor characters from the declared cast, plus anchor-adjacent graph entities with repeated scene presence

Recommended v1 exclusions:

- same-name disambiguation beyond exact entity ids
- remote multi-hop-only entities with no direct anchor evidence
- narrator-only world facts that are not anchored to a character relationship

#### Candidate Card Payload

The selector should not expose raw triples. It should show a compact review card per candidate, such as:

- `entity_id`
- `display_name`
- `actor_kind`
- `relationship_labels`
- `direct_interaction_count`
- `recent_event_tags`
- `last_seen_at`
- `sample_supporting_lines`

This is enough for explicit human choice without pretending the selector is doing semantic mind-reading.

#### Selection Rules

- the user explicitly selects one candidate before publish and before runtime activation
- if only one eligible counterpart exists, the UI may preselect it, but it should still record an explicit confirmation
- selection is always anchor-centered; the chosen object means “simulate anchor <-> counterpart”, not “switch the runtime to an omniscient world mode”
- side relationships remain in Graphiti for later retrieval, but they are not foregrounded just because they were imported

#### Architectural Effects Of Selection

Once `selected_relationship_binding` exists, the system should use it in four places:

- graph retrieval: strongly upweight anchor <-> selected counterpart facts and recent events
- relationship projection: promote selected counterpart material by default; keep other relationships as reviewable background
- overlay activation: select the counterpart-scoped relationship overlay whose `overlay_key` matches the binding
- publish: seed the first relationship-state and interaction-preference candidates from the selected counterpart only

The selector is therefore part of the import architecture, not just a UI convenience.

#### Persistence Boundary

The logical model should contain both `TwinBinding` and `SelectedRelationshipBinding` even if an MVP temporarily stores them in one JSON record.

That keeps later rebind behavior straightforward.

#### Binding Compatibility Contract

The separation between `current_user_role_binding` and `selected_relationship_binding` is architectural, not permission for arbitrary mismatch.

Recommended v1 invariants for published one-on-one twins:

- at most one `SelectedRelationshipBinding` may be `active` for one `twin_id`
- every published or runtime-activatable twin must reference exactly one active `SelectedRelationshipBinding`
- `chat_twin`: `selected_relationship_binding.counterpart_entity_id` must equal `current_user_role_binding.entity_id`
- `work_role`: in the normal "treat me as this role" flow, `selected_relationship_binding.counterpart_entity_id` must also equal `current_user_role_binding.entity_id`
- if a future product wants observer mode, third-person narration, or "talk to the anchor about someone else" mode, that should become a new `interaction_mode` instead of silently allowing the two bindings to drift apart inside `chat_twin` or `work_role`
- rebinding supersedes the previously active relationship lens; it does not create two simultaneously active lenses for the same twin

This is why the two records should still stay separate even when they often point at the same entity in v1:

- `current_user_role_binding` models who the app user is in the imported world
- `selected_relationship_binding` models which exact anchor-centered counterpart lens is active right now
- their storage lifecycle, versioning, and invalidation rules are different


#### Selector Service Contract

Even if the first implementation is a local Python module, the selector should be designed as a stable service contract.

Recommended logical operations:

- `preflight_scan`
  - read raw corpus or normalized turns
  - emit anchor candidates, counterpart candidates, and quick selector cards
- `create_twin_binding`
  - persist `TwinBinding`
  - bind `twin_id`, `anchor_person_id`, `source_mode`, and `current_user_role_binding`
- `list_relationship_candidates`
  - enumerate anchor-centered candidate relationships for one `TwinBinding`
- `get_import_session`
  - return one aggregated import-time snapshot for mobile review, including binding, selected relationship, selector cards, and graph-manifest summary- `select_relationship`
  - persist one `SelectedRelationshipBinding`
  - mark it as the active lens for projection and publish
- `rebind_relationship`
  - switch the active selected relationship without rebuilding unrelated artifacts

- `withdraw_import`
  - cancel one unpublished import session, clear draft artifacts, and leave a withdrawn session tombstone for audit
Recommended request shape for selection:

```json
{
  "operation": "select_relationship",
  "params": {
    "twin_id": "twin_lin_01",
    "anchor_person_id": "actor_lin",
    "counterpart_entity_id": "actor_current_user",
    "selection_source": "user_selected"
  }
}
```

Recommended response shape:

```json
{
  "status": "ok",
  "twin_id": "twin_lin_01",
  "selected_relationship_binding": {
    "binding_id": "relbind_twin_lin_01_current_user",
    "counterpart_entity_id": "actor_current_user",
    "overlay_key": "counterpart:actor_current_user",
    "selection_source": "user_selected",
    "status": "active"
  }
}
```

Recommended failure behavior:

- if `current_user_role_binding.entity_id` is not present in the eligible counterpart set for a published one-on-one twin, keep the import in review; do not auto-fabricate a matching selected relationship
- if no eligible counterpart exists, the system may still keep base soul and graph artifacts as draft-only outputs, but it must block relationship-ready publish and runtime activation
- if `anchor_person_id`, `current_user_role_binding`, or source corpus hash changes materially, existing selected relationship bindings should be marked stale until revalidated
- if a selected relationship exists but no overlay or projection draft can be resolved for its `overlay_key`, the system may only publish explicit degraded/base-only artifacts; it must not claim a relationship-ready twin

Recommended additional request and response shapes:

Preflight scan:

```json
{
  "operation": "preflight_scan",
  "params": {
    "source_mode": "chat_history",
    "source_refs": ["import://chat/wechat/export_01.txt"]
  }
}
```

```json
{
  "status": "ok",
  "anchor_candidates": [
    {
      "entity_id": "actor_lin",
      "display_name": "Lin"
    }
  ],
  "counterpart_candidates": [
    {
      "entity_id": "actor_current_user",
      "display_name": "User",
      "direct_interaction_count": 1842
    }
  ]
}
```

Rebind:

```json
{
  "operation": "rebind_relationship",
  "params": {
    "twin_id": "twin_lin_01",
    "anchor_person_id": "actor_lin",
    "from_binding_id": "relbind_twin_lin_01_current_user",
    "to_counterpart_entity_id": "actor_mei",
    "selection_source": "user_selected"
  }
}
```

```json
{
  "status": "ok",
  "twin_id": "twin_lin_01",
  "superseded_binding_id": "relbind_twin_lin_01_current_user",
  "active_binding_id": "relbind_twin_lin_01_mei",
  "recomputed_artifacts": [
    "relationship_projection_drafts",
    "relationship_state_seed",
    "interaction_preference_seed",
    "judge_context_bundle"
  ]
}
```

The physical implementation may remain file-bridge based on mobile, but the logical contract should already be stable.

## Retrieval Principle: Ego-Centric, Not Omniscient

The twin should not surface all known relationships equally.

The default retrieval policy should be:

- prioritize the anchor person
- prioritize entities directly connected to the anchor
- prioritize events that changed the anchor's important relationships
- heavily downweight remote characters unless the current turn makes them relevant
- prefer "what the anchor would naturally think about" over "what the full graph contains"

This avoids the uncanny behavior where the agent keeps bringing up unrelated people simply because they exist in the imported corpus.

### Default Retrieval Radius

Recommended graph-radius defaults:

- radius 0: anchor person
- radius 1: direct close ties, conflicts, family, rivals, repeated conversation partners
- radius 2: supporting context only when explicitly relevant
- radius 3+: off by default

### Prompt Projection Rule

The graph should not be injected as raw triples.

Projected relationship context should be compact, such as:

- `important_people`
- `current_relationship_state`
- `recent_relationship_events`
- `shared_history_relevant_to_this_turn`

Example projected context:

```text
relationship_context:
- user is treated as: current_user
- anchor person: Lin
- closest active ties: current_user (high trust, medium safety), Mei (old friend, low current contact)
- recent relevant changes: tension after missed promise in January 2025
- do not foreground: side-cast school friends unless the user asks
```

## Import Modes

### Mode A: Chat History Import

Use when the source corpus is chat logs, private messages, DMs, email-like threads, or voice transcripts.

#### Objective

Recover:

- who matters to the anchor person
- what happened between them
- how the anchor person sees those people
- which relationship changes actually matter in current conversation

#### Import Policy

- import all visible participants into Graphiti
- preserve timestamps and source conversation ids
- extract relationship edges for all participants
- promote only anchor-adjacent relationships into OpenCray runtime drafts by default
- require explicit selector confirmation for which anchor-adjacent counterpart becomes the active one-on-one lens
- keep non-anchor relationships in the graph for retrieval fallback, but do not foreground them

#### Promotion Rules

Promote eagerly:

- anchor <-> current_user
- anchor <-> repeated close contacts
- anchor <-> family or partner
- anchor <-> recurrent conflict figures

Promote cautiously:

- relationships only observed indirectly
- relationships involving two non-anchor actors
- weak one-off mentions

### Mode B: Fiction / Work Import

Use when the source corpus is a novel, screenplay, game script, fanfic, or other authored world.

#### Objective

Recover:

- the selected character's relationship graph
- the selected character's knowledge and lived perspective
- the selected character's evolving ties to the anchor person and others

#### Import Policy

- require the user to choose `anchor_person`, `current_user_role_binding`, and one explicit `selected_relationship_binding`; fiction import does not bypass the manual relationship selector just because the role binding is already known
- import all major characters into Graphiti
- store scene- or chapter-level provenance
- only retrieve subgraphs consistent with the chosen role's perspective when building runtime context

#### Perspective Guard

Fiction imports should not automatically make the twin omniscient.

If a scene gives information only to the narrator or another character, that information should be either:

- marked as not directly known by the chosen role
- or downweighted during role-centered retrieval

## Recommended Graphiti Ontology For OpenCray

OpenCray should keep the first ontology small and practical.

### Entity Types

Recommended MVP entity types:

- `Actor`
  - real person or fictional character
  - fields: `actor_kind`, `display_name`, `aliases`, `source_scope`
- `SourceArtifact`
  - imported chat export, manuscript, notebook, or transcript
- `Work`
  - novel, story, series, script, or universe
- `RelationshipMarker`
  - optional synthetic node for special recurring bonds when needed
- `Place`
  - meaningful recurring place
- `Organization`
  - team, school, family unit, company, faction

### Edge Types

Recommended MVP edge types:

- `KNOWS`
- `TRUSTS`
- `FEELS_CLOSE_TO`
- `HAS_CONFLICT_WITH`
- `FAMILY_OF`
- `ROMANTIC_WITH`
- `WORKS_WITH`
- `OWES`
- `PROTECTS`
- `RESENTS`
- `MENTORS`
- `ROLE_BINDING`
- `APPEARS_IN`
- `PARTICIPATED_IN`

Not every edge needs to be surfaced in prompt text. This schema mainly makes retrieval and post-processing sane.

### Event Types

Recommended imported event labels:

- `supportive_response`
- `missed_promise`
- `boundary_respected`
- `boundary_pressed`
- `repair_after_tension`
- `reciprocal_warmth`
- `betrayal`
- `separation`
- `reunion`
- `confession`

These should later map cleanly into OpenCray `RelationshipEvent` when promoted.

## Data Model For Integration

OpenCray should add a thin Graphiti-side import model rather than directly shoving raw messages into runtime.

### Graph Twin Binding

```json
{
  "twin_id": "twin_lin_01",
  "graph_group_id": "twin:twin_lin_01",
  "anchor_person_id": "actor_lin",
  "interaction_mode": "chat_twin",
  "source_mode": "chat_history",
  "current_user_role_binding": {
    "type": "real_user",
    "entity_id": "actor_current_user"
  },
  "selected_relationship_binding": {
    "binding_id": "relbind_twin_lin_01_current_user",
    "counterpart_entity_id": "actor_current_user",
    "relationship_label": "private_chat_counterpart",
    "overlay_key": "counterpart:actor_current_user"
  }
}
```

This `Graph Twin Binding` object should be treated as a denormalized read model for convenience.

The authoritative writable records remain:

- one `TwinBinding`
- one or more versioned `SelectedRelationshipBinding` records

If an MVP stores both in one JSON file, that should be documented as a storage shortcut, not the logical source of truth.


### Selected Relationship Binding

```json
{
  "binding_id": "relbind_twin_lin_01_current_user",
  "twin_id": "twin_lin_01",
  "anchor_person_id": "actor_lin",
  "counterpart_entity_id": "actor_current_user",
  "counterpart_binding_type": "real_user",
  "relationship_label": "private_chat_counterpart",
  "overlay_key": "counterpart:actor_current_user",
  "selection_source": "user_selected",
  "status": "active",
  "selection_version": 1,
  "created_at": "2026-03-18T20:12:00Z",
  "updated_at": "2026-03-18T20:12:00Z"
}
```

Recommended status values:

- `draft`
- `active`
- `superseded`
- `withdrawn`

### Relationship Candidate Card

```json
{
  "entity_id": "actor_current_user",
  "display_name": "User",
  "actor_kind": "real_person",
  "relationship_labels": ["private_chat_counterpart", "recurrent_repair_partner"],
  "direct_interaction_count": 1842,
  "recent_event_tags": ["missed_promise", "repair_after_tension"],
  "last_seen_at": "2025-02-28T23:11:00+08:00",
  "sample_supporting_lines": [
    "我知道，我那天答应你的事情没做到。",
    "这样我才安心，记得按时回复。"
  ]
}
```

### Import Session

```json
{
  "session_id": "import_twin_lin_01_20260318T201200Z",
  "twin_id": "twin_lin_01",
  "anchor_person_id": "actor_lin",
  "state": "relationship_selected",
  "source_hash": "sha256:...",
  "current_user_role_binding": {
    "type": "real_user",
    "entity_id": "actor_current_user"
  },
  "selected_relationship_binding_id": "relbind_twin_lin_01_current_user",
  "artifact_refs": {
    "graph_path": ".opencray/personality_service/graphs/twin_lin_01.kuzu",
    "base_soul_draft_path": ".opencray/personality_service/soul/twin_lin_01/sample.base_soul_draft.json",
    "relationship_overlay_path": ".opencray/personality_service/soul/twin_lin_01/sample.relationship_expression_overlay_draft.json"
  },
  "created_at": "2026-03-18T20:12:00Z",
  "updated_at": "2026-03-18T20:15:00Z"
}
```

### Graph Import Episode Metadata

```json
{
  "source_id": "src_wechat_01",
  "conversation_id": "conv_2025_01",
  "chapter_id": null,
  "speaker_id": "actor_lin",
  "anchor_person_id": "actor_lin",
  "current_user_role_binding": {
    "type": "real_user",
    "entity_id": "actor_current_user"
  },
  "selected_relationship_binding_id": "relbind_twin_lin_01_current_user",
  "fictionality": "non_fiction",
  "scene_pov_character_id": "actor_lin",
  "known_by": ["actor_lin", "actor_current_user"],
  "observed_by": ["actor_lin"],
  "narrator_only": false,
  "perspective_confidence": 0.97,
  "labels": ["conflict", "repair"]
}
```

Recommended perspective metadata fields:

- `scene_pov_character_id`
- `known_by`
- `observed_by`
- `narrator_only`
- `perspective_confidence`

### Relationship Projection Draft

```json
{
  "anchor_person_id": "actor_lin",
  "counterpart_entity_id": "actor_current_user",
  "selected_relationship_binding_id": "relbind_twin_lin_01_current_user",
  "relationship_summary": "high trust with unresolved tension after missed promise",
  "supporting_events": [
    "missed_promise@2025-01-03",
    "repair_after_tension@2025-01-09"
  ],
  "graph_distance": 1,
  "promotion_score": 0.92,
  "apply_scope": "selected_counterpart_default",
  "promotion_reason": ["direct_anchor_tie", "selected_counterpart", "recent_repair_signal"],
  "invalidation_scope": "reproject_on_rebind"
}
```

Projection drafts should be rich enough for review and rebind logic. At minimum they should carry:

- `counterpart_entity_id`
- `selected_relationship_binding_id`
- `apply_scope`
- `graph_distance`
- `promotion_reason`
- `invalidation_scope`

### Target Schema Materialization

The design above is now more specific than the checked-in schema files. Before wiring this module into create-agent flows, the following schema alignment should be treated as required contract work:

- `service/schemas/twin_binding.schema.json`
  - make `current_user_role_binding` required
  - add `import_session_id` or equivalent lifecycle linkage
  - add an explicit read-model field such as `active_selected_relationship_binding_id` only if the consumer really needs a single-file binding payload
  - allow the active selected-binding field to be unset only before Stage 2; require it for `relationship_selected`, `under_review`, `published`, `rebound`, and any runtime activation request
- new `service/schemas/selected_relationship_binding.schema.json`
  - include `binding_id`, `twin_id`, `anchor_person_id`, `counterpart_entity_id`, `counterpart_binding_type`, `relationship_label`, `overlay_key`, `selection_source`, `status`, `selection_version`, `created_at`, and `updated_at`
- new `service/schemas/relationship_candidate_card.schema.json`
  - include the review-card payload used by the manual selector
  - at minimum: `entity_id`, `display_name`, `actor_kind`, `relationship_labels`, `direct_interaction_count`, `recent_event_tags`, `last_seen_at`, and `sample_supporting_lines`
- new `service/schemas/import_session.schema.json`
  - include `session_id`, session state, source hash, artifact refs, `current_user_role_binding`, and selected-binding references
- `service/schemas/request_envelope.schema.json`
  - add selector-facing operations such as `preflight_scan`, `create_twin_binding`, `list_relationship_candidates`, `get_import_session`, `select_relationship`, and `rebind_relationship`
  - replace the loose `params` object with a discriminated `oneOf` keyed by `operation`
  - make per-operation required fields explicit instead of relying on prose
- `service/schemas/opencray_draft_bundle.schema.json`
  - add `selected_relationship_binding_id`, `counterpart_entity_id`, `overlay_key`, `apply_scope`, `promotion_reason`, and `invalidation_scope` so rebind-safe projection bundles can be serialized without guesswork

These are import-time contract changes only. They do not change runtime `soul` ownership or prompt architecture.

## Simplest Viable Integration Path

The simplest path is a standalone Python extraction service or batch job that imports Graphiti directly.

This service is independent of the OpenCray agent runtime.

Why this is the simplest path:

- no OpenCray tool wiring is required
- no MCP bridge is required
- no Kotlin-side Graphiti SDK is needed
- anchor-aware projection logic can live in the same Python codebase as Graphiti
- a local file-backed Kuzu graph can avoid standing up Neo4j for the first MVP

### Recommended Backend For MVP

Use Graphiti's Kuzu backend for the MVP.

Why:

- local file-backed database
- fewer moving parts than Neo4j
- good fit for workspace-scoped twin graphs
- no separate graph server required

Recommended service-local paths:

- `.opencray/graphiti/graph.kuzu`
- `.opencray/graphiti/imports/`
- `.opencray/graphiti/bindings/`
- `.opencray/graphiti/cache/`

### Service Entry Contract

Add one Python entrypoint such as:

- `service/graphiti_adapter.py`

Recommended subcommands:

- `init`
- `ingest_chat`
- `ingest_work`
- `search_anchor`
- `project_relationships`
- `export_opencray_drafts`

This keeps OpenCray's invocation surface small even if Graphiti usage grows internally.

### Minimal Install Flow

Recommended install example:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install "graphiti-core[kuzu]"
```

If provider-specific credentials are required, the import module should default to the app's existing `llm_config`, then allow an optional `graphiti_config` override for `llm`, `embedder`, or `cross_encoder`.

Recommended import-time contract:

- `llm_config`: reuse the app's configured provider, base URL, API key, model, and reasoning effort by default
- `graphiti_config.llm`: only override when Graphiti should talk to a different chat/reasoning endpoint than the app default
- `graphiti_config.embedder`: use when embeddings must come from a different model or provider
- `graphiti_config.cross_encoder`: use when reranking must come from a different model or provider

Security rule:

- request payloads may carry secrets such as `apiKey`
- persisted service artifacts only store a non-secret `graphiti_runtime_preferences` summary
- the import service should not write Graphiti API keys into `*.binding.json` or `*.import_session.json`

### Minimal Exec Flow

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py init --twin-id twin_lin_01 --anchor-person-id actor_lin --interaction-mode chat_twin --source-mode chat_history --binding-entity-id actor_current_user
```

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py ingest_chat --twin-id twin_lin_01 --source service/examples/chat_corpus.sample.json
```

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py export_opencray_drafts --twin-id twin_lin_01 --query "他和我最近是不是有隔阂"
```

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py run_request --request service/examples/request_init.sample.json --response .opencray/personality_service/cache/request_init.response.json
```

If the mobile import page wants Graphiti to inherit the app's current model configuration by default, use a request shaped like `service/examples/request_init_with_llm_config.sample.json`. If Graphiti needs a separate embedding provider, add `graphiti_config.embedder` as shown in `service/examples/request_ingest_chat_with_graphiti_override.sample.json`.

The service can be run either:

- as one-shot CLI jobs during import
- through a local request/response JSON file bridge on mobile
- or later behind a long-running process wrapper if needed

### Minimal Direct Python Skeleton

The thinnest useful call path is a direct Python wrapper around Graphiti:

```python
from datetime import datetime

from graphiti_core import Graphiti
from graphiti_core.driver.kuzu_driver import KuzuDriver
from graphiti_core.nodes import EpisodeType


async def ingest_chat_episode() -> None:
    driver = KuzuDriver(db=".opencray/graphiti/graph.kuzu")
    graphiti = Graphiti(graph_driver=driver)
    await graphiti.build_indices_and_constraints()

    group_id = "twin:twin_lin_01"

    await graphiti.add_episode(
        name="wechat_2025_01_03",
        episode_body=(
            "Lin: 我不是在生气，我是觉得这样很累。\n"
            "User: 我知道，我那天答应你的事情没做到。"
        ),
        source=EpisodeType.message,
        source_description="Imported private chat",
        reference_time=datetime(2025, 1, 3, 21, 12),
        group_id=group_id,
    )

    results = await graphiti.search(
        query="Lin 和当前用户最近的关系紧张点是什么",
        group_id=group_id,
    )

    for edge in results:
        print(edge.fact)
```

For anchor-centric retrieval, the adapter now tries to resolve the anchor node UUID from the persisted anchor terms when Graphiti is available, then switches to Graphiti's node-distance reranking path around that node instead of using only broad hybrid search. If Graphiti is unavailable, selector ranking falls back to the persisted anchor neighborhood manifest.

## Why Not MCP First

Graphiti's official MCP server is useful, but it is not the simplest first path for an independent extraction service.

Reasons:

- the twin import flow needs anchor-specific post-processing, not raw generic MCP calls
- import and projection still need OpenCray-specific draft generation
- a direct Python import is simpler than wrapping generic MCP calls and then post-processing them again

MCP should therefore be treated as a later operational deployment option, not the first implementation path.

## Secondary Integration Paths

### Path B: Official Graphiti FastAPI Server

Use the official Graphiti server when you want:

- a long-running graph service
- centralized graph storage
- cross-device or cross-runtime access
- easier operator tooling outside the app

Recommended shape:

```text
personality extraction service
  -> GraphitiAdapterClient (HTTP)
  -> official Graphiti server
  -> graph backend
  -> OpenCray draft output
```

Use this when the MVP Python-wrapper path is already validated and you want better operational separation.

### Path C: Official Graphiti MCP Server

Use the official MCP server only if you specifically want MCP as an infrastructure boundary.

At that point, an MCP bridge can be attractive for:

- tool discoverability
- standard MCP wiring
- easier reuse of upstream capabilities

But even then, the extraction service still needs an anchor-aware wrapper layer on top of generic Graphiti operations.

## Proposed Python Adapter Responsibilities

The adapter script should hide upstream Graphiti details and present OpenCray-ready operations.

### 1. Graph Initialization

- create or open the Graphiti graph for one `twin_id`
- bind it to `group_id = twin:<twin_id>`
- initialize the chosen storage backend
- ensure indexes and constraints if needed

### 2. Corpus Normalization

- parse imported chat or work files
- normalize speaker aliases
- preserve timestamps and source provenance
- emit Graphiti episodes with OpenCray-specific metadata

### 3. Anchor-Aware Search

- accept `anchor_person_id`
- accept `current_user_role_binding`
- accept `selected_relationship_binding`
- search around the anchor first
- strongly upweight the selected counterpart before considering side-cast context
- downweight or drop far-off nodes unless explicitly requested

### 4. Projection To OpenCray Drafts

- graph results -> `RelationshipDraft`
- graph results -> `MemoryDraft`
- graph facts -> debug provenance bundle

### 5. Optional Pre-Projection Filters

- suppress side-cast chatter
- suppress remote non-anchor relationships
- perspective-clamp fictional omniscience

## Import Pipeline Using Graphiti


### Import Session State Machine

Recommended import session states:

- `created`
- `preflight_scanned`
- `binding_created`
- `relationship_selected`
- `graph_ingested`
- `soul_extracted`
- `drafts_projected`
- `under_review`
- `published`
- `rebound`
- `withdrawn`
- `stale`

Recommended transition rules:

- `created -> preflight_scanned`
- `preflight_scanned -> binding_created`
- `binding_created -> relationship_selected`
- `relationship_selected -> graph_ingested`
- `graph_ingested -> soul_extracted`
- `soul_extracted -> drafts_projected`
- `drafts_projected -> under_review`
- `under_review -> published`
- `published -> rebound` when only the selected relationship lens changes
- any state -> `stale` when the source corpus hash changes
- any unpublished state -> `withdrawn` when the user cancels import

This state machine matters because mobile flows will otherwise end up mixing artifacts from incompatible stages.

### Stage 0: Preflight Entity Scan

Before creating a final twin binding, the importer should do a lightweight pass over the corpus to enumerate:

- anchor candidates
- non-anchor counterpart candidates
- direct interaction counts
- rough relationship hints for the selector cards

This is not full graph reasoning. It is a preflight pass so the user can choose a relationship lens explicitly.

### Stage 1: Create Twin Binding

The user chooses:

- the twin identity
- source mode: chat or fiction
- anchor person
- current-user role binding

The system writes a `TwinBinding` record for later calls.

### Stage 2: Choose Relationship Lens

The user chooses one `selected_relationship_binding` from the anchor-centered candidate list.

Recommended rule:

- require this choice before publish and before runtime activation
- if only one eligible candidate exists, the system may prefill it, but should still store an explicit confirmation event
- apply the same rule to fiction imports; choosing `current_user_role_binding` does not remove the need to explicitly confirm the active relationship lens

### Stage 3: Normalize Corpus

Normalize source files into episodes.

Chat input becomes message-like episodes.  
Fiction input becomes chapter-, scene-, or dialogue-derived episodes with perspective labels.

### Stage 4: Ingest Episodes Into Graphiti

Each normalized unit is written into the graph under the correct `group_id`.

Recommended rule:

- one twin = one `group_id`
- one work universe may also use one `group_id`, with separate bindings per anchor if needed

### Stage 5: Run Anchor-Centric Retrieval

Retrieve:

- direct ties of the anchor
- recent relationship changes
- repeated mentions around the anchor
- anchor-relevant people and places

Bias rules:

- selected counterpart gets the strongest upweight
- other direct ties remain available as background
- remote side-cast material stays suppressed unless the query explicitly asks for it

Do not retrieve:

- broad global graph facts by default
- deep multi-hop social context unless the query requires it

### Stage 6: Project Into OpenCray Drafts

Project retrieved graph material into three buckets:

- `MemoryDraft`
- `RelationshipDraft`
- `BackgroundContextDraft`

Promotion rules:

- selected counterpart material is promoted by default
- non-selected anchor-adjacent relationships remain reviewable but backgrounded
- two non-anchor relationships should not become active runtime material unless explicitly reviewed and approved

### Stage 7: Merge With Soul Import

Graphiti does not provide `soul`.  
The same corpus should still run through the separate soul extractor described in `docs/digital-twin-corpus-import-design.md`.

Final publish is:

```text
graph import
  -> relationship drafts + fact drafts
soul import
  -> base soul draft + anti-patterns + quote bank + counterpart-scoped overlays
human review
  -> SOUL.md + MemoryWriter + relationship planners + selected relationship binding
```

### Stage 8: Rebind Behavior

Changing `selected_relationship_binding` with the same twin, anchor, and corpus should not require a full rebuild.


#### Rebind Invalidation Matrix

Reuse without recomputation:

- Graphiti graph storage
- normalized turns and preflight candidate cache
- anchor-scoped `BaseSoulDraft`
- quote bank and anti-pattern bank
- previously extracted multi-counterpart overlay draft

Soft-invalidate and regenerate:

- selected relationship projection drafts
- active relationship overlay resolution
- initial `RelationshipState` seed candidates for the newly selected counterpart
- initial `InteractionPreferenceState` seed candidates for the newly selected counterpart
- judge context bundle for import review or generation-time evaluation

Mark as superseded, not deleted by default:

- prior `SelectedRelationshipBinding`
- prior selected-counterpart projection drafts
- prior review decisions tied only to the superseded relationship lens

Require explicit withdrawal or deletion to remove them entirely.

Reuse:

- the ingested Graphiti graph
- the anchor-scoped base soul draft
- quote bank and anti-pattern bank
- previously extracted counterpart-scoped overlay draft

Rerun or reslice:

- anchor-centric retrieval export
- relationship draft projection
- selected overlay activation
- initial relationship-state and interaction-preference seeds for the newly selected counterpart

Require a full rebuild only when:

- `anchor_person_id` changes
- source corpus changes materially
- fiction perspective assumptions change in a way that invalidates the prior role lens

## Mapping Graphiti Output To OpenCray

### Relationship Events

Graphiti-detected relationship changes should map into OpenCray `RelationshipEvent` when they meet promotion thresholds.

Examples:

- `boundary respected` -> `RESPECTED_BOUNDARY`
- `missed promise` -> `APOLOGY_WITHOUT_REPAIR` or custom import-side label before final mapping
- `supportive comfort` -> `SUPPORTIVE_RESPONSE`
- `mutual joking warmth` -> `RECIPROCAL_WARMTH`

### Relationship State

Projected graph relationship summaries should update:


### Runtime Mapping Contract

The runtime-facing mapping should stay deterministic.

Required inputs:

- `twin_id`
- `anchor_person_id`
- `current_user_role_binding`
- `selected_relationship_binding`
- counterpart-scoped relationship overlay draft
- approved relationship and interaction-preference candidates

Required outputs:

- one active counterpart overlay key
- one active relationship-state projection seed
- one active interaction-preference projection seed
- a retrieval bias that prefers anchor <-> selected counterpart material

If no approved `selected_relationship_binding` exists, runtime activation should fail closed to a conservative generic relationship mode instead of guessing.


- familiarity
- trust
- safety
- intimacy permission
- playfulness permission
- affection tendency
- reciprocity

### Memory

Graphiti facts that are stable and explicit should become ordinary OpenCray memory candidates:

- durable facts
- user preferences
- important recurring commitments
- relationship-linked facts

### Soul

Graphiti should not directly write core soul.

At most, Graphiti may emit weak hints into the import review stage such as:

- recurring social role
- recurring conflict setting
- repeated relationship posture

But final `soul` still comes from the dedicated soul extractor and human review flow.

## Query Policy

OpenCray should not call Graphiti for every single turn.

Recommended call sites:

- import-time batch processing
- explicit twin setup or reindex flows
- relationship-heavy turns
- prior-work or prior-relationship questions
- optional verification pass when the model seems likely to hallucinate social context

Avoid calling Graphiti on:

- every trivial chat turn
- turns with no social or memory dependency
- style-only generation where `soul` is the real constraint

## Ranking And Attenuation Rules

These rules keep the twin from sounding socially noisy.

### Upweight

- anchor person
- current user role binding
- selected relationship binding counterpart
- direct relationship events with anchor
- repeated relationship references
- recent unresolved tension
- recent repair after tension

### Downweight

- two non-anchor people talking about each other
- one-off side characters
- world-building-only entities in fiction
- social facts irrelevant to the current query

### Hide By Default

- distant social graph clusters
- old side-cast drama with no current relevance
- narrator-only facts that the chosen fictional role should not know

## Operational Modes

### Development / Desktop

Recommended first:

- standalone Python CLI
- workspace-local Kuzu backend
- local adapter script
- request/response JSON file bridge

This has the smallest operational footprint.

### Hosted / Multi-User

Recommended later:

- official Graphiti server
- shared graph backend
- dedicated service adapter

### MCP-Based

Recommended only after OpenCray's remote MCP tooling is actually proxied:

- official Graphiti MCP server
- anchor-aware wrapper skill or adapter

## Risks And Tradeoffs

### Benefits

- better relationship memory than flat embeddings
- temporal relationship changes become explicit
- fiction imports can be perspective-aware
- anchor-centric retrieval reduces uncanny irrelevant name-dropping

### Costs

- Python dependency stack
- Graph storage lifecycle and migration concerns
- LLM-backed extraction cost during ingestion
- still requires a separate `soul` pipeline

### Main Failure Mode

If OpenCray retrieves the full graph too eagerly, the twin will sound omniscient and socially strange.

The anchor policy is therefore not optional. It is the application-specific rule that makes Graphiti useful here.

## Recommended Rollout

### Step 1

Document Graphiti as a relationship-graph sidecar only.

### Step 2

Build a standalone Python adapter using Graphiti with the Kuzu backend.

### Step 3

Implement import-time commands:

- `init`
- `ingest_chat`
- `ingest_work`
- `search_anchor`
- `export_opencray_drafts`
- `run_request`

### Step 4

Project graph results into reviewable OpenCray drafts instead of direct runtime writes.

### Step 5

After import quality is stable, add optional runtime retrieval for relationship-heavy turns.

### Step 6

Only later evaluate whether to move the adapter behind the official Graphiti server or MCP server.

## Implementation Notes For OpenCray

This design aligns with current repository realities:

- base soul authority already lives in workspace `SOUL.md`
- adaptive relationship and interaction state already live in `runtime/soul/*`
- the repository already treats imported soul and relationship material as separate from ordinary chat-time rewriting
- remote MCP tooling is not relevant to the independent extraction-service MVP

That means the shortest path is:

```text
source corpus
  -> standalone Python extraction service
  -> Graphiti + soul extractor
  -> draft JSON
  -> OpenCray draft review / publish
```

## References

- Graphiti GitHub: https://github.com/getzep/graphiti
- Graphiti Quick Start: https://help.getzep.com/graphiti/getting-started/quick-start
- Graphiti Adding Episodes: https://help.getzep.com/graphiti/core-concepts/adding-episodes
- Graphiti Custom Entity and Edge Types: https://help.getzep.com/graphiti/core-concepts/custom-entity-and-edge-types
- Graphiti Graph Namespacing: https://help.getzep.com/graphiti/core-concepts/graph-namespacing
- Graphiti Searching: https://help.getzep.com/graphiti/working-with-data/searching
- Graphiti Kuzu DB Configuration: https://help.getzep.com/graphiti/graph-database-clients/kuzu
- Graphiti server README: https://github.com/getzep/graphiti/blob/main/server/README.md
- Graphiti MCP server README: https://github.com/getzep/graphiti/blob/main/mcp_server/README.md





