# Digital Twin Corpus Import Design

Last updated: 2026-03-21

## Status

Draft design aligned with the current OpenCray runtime split between `runtime/memory/*`, `runtime/soul/*`, and workspace `SOUL.md`.

## Related designs

- `docs/digital-twin-graphiti-integration-design.md` covers how Graphiti can provide the temporal relationship-graph layer for imported chat and fiction corpus without replacing OpenCray's own `soul` runtime.
- `docs/digital-twin-soul-extraction-and-judge-design.md` covers the dedicated `soul` extraction pipeline, candidate judge, and optional late-stage LoRA strategy.

## Goal

Define how OpenCray should import chats, personal writing, public posts, and fiction-like corpus to initialize a person-like digital twin without confusing:

- `memory`: what this person knows, experienced, prefers, promised, and how they relate to specific people
- `soul`: how this person speaks, judges, reacts, repairs, refuses, and maintains a stable sense of self

The target is not "stuff a large transcript into the prompt". The target is an evidence-driven import pipeline that can:

- extract structured facts and interaction evidence from raw corpus
- aggregate stable style and judgment patterns into a bounded `soul` layer
- preserve relationship-specific behavior without rewriting base identity on every turn
- remain inspectable, testable, and correctable

## Why Raw Transcript Stuffing Fails

Directly injecting chat logs usually produces a weak imitation:

- it learns catchphrases before it learns value ordering
- it averages different relationship modes into one flattened voice
- it confuses one-off emotional spikes with stable personality
- it treats fiction and performance as biography
- it has no explicit model of "what this person would never say"

To feel like "the other person is talking to you", the runtime must reconstruct four separate layers:

1. base identity
2. relationship mode
3. relevant memory for the current topic
4. situation-specific speaking policy

## Boundary Rules

OpenCray already has strict boundaries around memory and soul. Corpus import should preserve them.

- This import design initializes existing runtime inputs; it does not redesign the `runtime/soul/*` architecture.
- The durable base persona authority remains workspace `SOUL.md`.
- Imported stable persona traits may seed or revise `SOUL.md` only through a creator or admin import flow, not ordinary chat.
- Imported adaptive relationship signals should become structured memory-backed overlays, not direct base-soul rewrites.
- Imported fiction must never become episodic memory unless corroborated by a non-fiction source.
- Imported quotes are evidence and calibration material, not a bulk prompt layer to inject every turn.
- The import pipeline must keep provenance for every promoted trait so operators can inspect why a trait exists.

This keeps the current runtime discipline intact:

- `runtime/memory/*` remains the source of structured durable memory behavior.
- `runtime/soul/*` remains the source of effective runtime soul resolution.
- `ContextManager` stays a budget allocator, not a personality synthesizer.

## Runtime Alignment With Current OpenCray

The imported outputs should land in the existing runtime layers instead of inventing a parallel personality system.

| Imported output | Current runtime target | Notes |
| --- | --- | --- |
| Stable display name, voice label, tone, verbosity, base guidance | `SOUL.md` -> `SoulProfile` | Base persona authority |
| Stable behavior-style traits not yet modeled as typed fields | `SoulProfile.extensions` | Transitional until promoted to typed soul fields |
| Warmth, formality, initiative, naming and addressing preferences | `InteractionPreferenceState` | Adaptive interaction mode |
| Familiarity, trust, safety, intimacy permission, playfulness permission | `RelationshipEvent` + `RelationshipState` | Relationship-specific overlay |
| Durable facts, preferences, instructions, commitments | `MemoryCandidate` -> `MemoryWriter` -> `MemoryStore` | Standard memory path |
| Quote bank, anti-pattern bank, fiction hypotheses | Import-side artifacts or debug-only draft data | Not blindly prompt-injected |

Relevant current runtime structures:

- `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulProfile.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/soul/RelationshipState.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/SoulMemoryIntentInterpreter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulMemoryCandidateFactory.kt`

## Source Types And Trust Weights

Not every corpus source deserves the same authority.

| Source type | Primary use | Base weight | Rules |
| --- | --- | --- | --- |
| Private 1:1 chat | memory, relationship, style, values | 1.00 | Highest evidence for relationship-specific behavior |
| Voice transcript from real conversation | style, emotion, pacing | 0.95 | Useful for rhythm and emotional recovery patterns |
| Personal notes or diaries | values, self-view, long-term concerns | 0.90 | High authority when clearly first-person |
| Long-form essays or posts | values, reasoning style, public voice | 0.80 | Good for argument structure, weaker for private intimacy |
| Group chats | humor, social mode, boundaries | 0.75 | Relationship inference is noisier |
| Public social media | surface style, recurring topics | 0.65 | Often performative; use carefully |
| Novel or fiction written by the person | metaphor habits, emotional palette, worldview hypotheses | 0.35 | Never direct episodic memory |
| Third-person biography or comments by others | external facts only | 0.30 | Use only as weak corroboration |

Additional per-item modifiers should adjust the base weight:

- `first_person_authored = true` raises confidence
- `explicit self-disclosure = true` raises confidence
- `performative/public staging = true` lowers confidence
- `emotionally extreme outlier = true` lowers confidence
- `cross-source corroboration = true` raises confidence

## Import Data Model

The import pipeline should normalize raw corpus into a few stable intermediate objects.

### 1. Source Artifact

Represents one imported file, conversation dump, notebook, or text corpus unit.

```json
{
  "source_id": "src_2025_private_chat_01",
  "source_type": "private_chat",
  "title": "WeChat export with target person",
  "participants": ["target_person", "current_user"],
  "is_first_person_authored": true,
  "fictionality": "non_fiction",
  "language": "zh-CN",
  "time_range": {
    "start": "2024-11-01T00:00:00+08:00",
    "end": "2025-02-28T23:59:59+08:00"
  },
  "base_weight": 1.0,
  "raw_ref": "import://chat/wechat/export_01.txt"
}
```

### 2. Normalized Turn

The import pipeline should convert raw transcript fragments into turn-level records. Consecutive short messages from the same speaker may be merged when they form one semantic utterance.

```json
{
  "turn_id": "turn_001024",
  "source_id": "src_2025_private_chat_01",
  "speaker_id": "target_person",
  "speaker_role": "target_person",
  "addressed_to": "current_user",
  "timestamp": "2025-01-03T21:12:14+08:00",
  "channel_type": "private_chat",
  "reply_to_turn_id": "turn_001023",
  "text": "我不是在生气，我是觉得这样很累。",
  "attachments": [],
  "context_labels": [
    "conflict",
    "boundary",
    "self_disclosure"
  ]
}
```

Optional fields that improve the current import-time module even when wiring is still pending:

- `reply_to_turn_id`
- `conversation_id`
- `addressed_to`
- `quoted_speech`

These fields are not required for import, but they make turn-window event and script extraction materially more reliable.

### 3. Extracted Signal

Each turn may emit multiple signals. Signals are the real atomic unit of import. Do not aggregate directly from raw text.

```json
{
  "signal_id": "sig_001024_03",
  "turn_id": "turn_001024",
  "signal_type": "soul_trait",
  "trait_key": "conflict_style",
  "trait_value": "explain_then_withdraw",
  "scope_hint": "base_soul",
  "confidence": 0.78,
  "support_weight": 1.12,
  "evidence_excerpt": "我不是在生气，我是觉得这样很累。",
  "evidence_tags": [
    "conflict",
    "soft_directness",
    "self_disclosure"
  ]
}
```

### 4. Aggregate Drafts

After signal aggregation, the pipeline should produce a small number of reviewable drafts.

- `MemoryDraft`: promoted facts, preferences, commitments, and relationship evidence
- `BaseSoulDraft`: stable identity and behavior traits suitable for `SOUL.md` or `SoulProfile.extensions`
- `RelationshipOverlayDraft`: behavior shifts keyed by relationship cluster
- `QuoteBankDraft`: short, high-recognition phrases for calibration only
- `AntiPatternDraft`: phrases, postures, or styles the target person would almost never use

## Signal Taxonomy

The extractor should emit typed signals instead of one generic "persona summary".

### Memory-Oriented Signals

- `fact`
- `preference`
- `durable_instruction`
- `task_commitment`
- `relationship_event`
- `interaction_preference_signal`
- `identity_anchor`

These are primarily about durable knowledge or durable interaction evidence.

### Soul-Oriented Signals

- `self_view`
- `value_order`
- `speech_surface`
- `response_habit`
- `conflict_style`
- `humor_style`
- `affection_style`
- `uncertainty_style`
- `boundary_style`
- `repair_style`
- `taboo`
- `signature_phrase`

These are primarily about stable manner and judgment.

### Calibration Signals

- `quote_sample`
- `anti_pattern`
- `fiction_hypothesis`

These help evaluation and style control, but they should not automatically become durable runtime truth.

## What To Extract From Chat Logs

The minimal useful extraction set for a person-like twin is:

- factual anchors: name, occupation, city, routines, recurring life facts, important others
- durable preferences: what they like, dislike, tolerate, reject, and repeatedly ask for
- relationship signals: trust, familiarity, safety, reciprocity, intimacy permission, playful permission
- speech surface: sentence length, punctuation, emoji habits, fillers, rhetorical questions, explanation density
- response habit: whether they answer feelings first, facts first, judgment first, or humor first
- conflict style: confront, explain, withdraw, deflect, ask questions, cut off, repair
- affection style: explicit, indirect, teasing, acts-of-service, restrained
- uncertainty style: admit not knowing, speculate, stay vague, over-assert
- boundary style: soft refusal, hard refusal, redirection, soothe-then-refuse
- repair style: whether they re-open tension, how they apologize, how they return after distance
- value order: what wins when values conflict, such as truth vs harmony, speed vs care, autonomy vs closeness
- anti-patterns: expressions or stances that instantly feel unlike the person

If these are missing, the system may sound superficially similar, but it will not feel like the same person in conversation.

## Fiction Handling Rules

Fiction and creative writing can be useful, but they require a separate policy.

- Fiction can contribute style hypotheses, metaphor preferences, emotional palette, recurring obsessions, and worldview hints.
- Fiction cannot directly create episodic memory.
- Fiction cannot by itself create a high-confidence relationship rule.
- A first-person narrator is not automatically the author.
- Character dialogue is not automatically the target person's own speech style.
- If a fiction-derived trait is later corroborated by private chat or non-fiction writing, its confidence may be upgraded.

Recommended labels for fiction-derived signals:

- `fiction_hypothesis`
- `authorial_style_evidence`
- `narrator_only`
- `character_only`

## Import Pipeline

The pipeline should be staged and reviewable.

### Stage 1: Source Normalization

Input:

- exported chat logs
- note files
- post archives
- novels or manuscripts

Output:

- `SourceArtifact` rows with source type, participant metadata, time range, and trust weight

Rules:

- preserve source provenance
- record whether the text is non-fiction, fiction, or mixed
- preserve exact timestamps when available

#### Stage 1A: Import Source Probe

Before full normalization, the creator flow should run a lightweight file probe so the app can prefill the correct import mode instead of forcing the user to guess.

Current implemented host/runtime contract:

- Flutter host bridge method: `probeTwinImportSource(filePath)`
- Local runtime HTTP route: `POST /v1/probe_twin_import_source`
- Kotlin detector: `TwinImportSourceProbe`

Current probe outputs:

- `sourceMode`: `chat_history`, `fiction_work`, or `null`
- `formatKey`: machine-readable format id such as `chatlab_json`, `chatlab_jsonl`, `normalized_chat_history`, `normalized_fiction_work`
- `formatLabel`: user-facing label for the detected format
- `confidence`: `high`, `medium`, or `low`
- `usesExistingImporter`: whether the detected source can go straight into an existing importer
- `needsManualSelection`: whether the create flow should force a manual mode choice
- `notes`: operator-facing explanation of the detection result

Current auto-detected formats:

- ChatLab `.json` export -> `chat_history`
- ChatLab `.jsonl` export -> `chat_history`
- normalized JSON with `participants + turns` -> `chat_history`
- normalized JSON with `characters + scenes` -> `fiction_work`

Current non-goal of the probe:

- It does not pretend that arbitrary `.txt`, `.md`, or unsupported JSON can already be imported.
- When confidence is low or the format is unknown, the UI must surface manual mode selection instead of silently coercing the file into a wrong pipeline.

### Stage 2: Speaker Resolution

Resolve who is speaking and who they are speaking to.

Output:

- canonical `speaker_id`
- `speaker_role`
- `addressed_to`
- participant cluster labels

Rules:

- merge platform aliases for the same person
- keep uncertain speaker resolution explicit instead of guessing
- mark forwarded or quoted speech as quoted, not authored

### Stage 3: Turn Segmentation

Convert raw messages into meaningful turns.

Rules:

- merge consecutive short messages from the same speaker when the semantic intent is continuous
- keep topic changes as separate turns
- preserve edits, deletes, and quoted replies if the source supports them

### Stage 4: Context Labeling

Each turn should receive lightweight situation labels before trait extraction.

Suggested labels:

- `small_talk`
- `planning`
- `comfort`
- `conflict`
- `repair`
- `joking`
- `self_disclosure`
- `decision`
- `boundary`
- `flirtation`
- `public_performance`

These labels matter because the same wording can mean different things in different situations.

### Stage 5: Turn-Level Signal Extraction

This stage extracts typed evidence from each turn.

Outputs may include:

- `fact`
- `preference`
- `relationship_event`
- `interaction_preference_signal`
- `soul_trait`
- `quote_sample`
- `anti_pattern`

The extractor should store:

- normalized key and value
- confidence
- support weight
- evidence excerpt
- source metadata
- relationship cluster
- situation labels

### Stage 6: Cross-Turn Aggregation

Aggregation decides what is stable, what is situational, and what stays as raw evidence only.

Key rules:

- repeated evidence across multiple dates matters more than a single strong line
- the same trait across different situations is stronger than repetition inside one argument
- private and unguarded speech matters more than performative public speech
- behavior evidence plus explicit self-description is stronger than either one alone

### Stage 7: Contradiction Resolution

People are not perfectly consistent. The importer should model that instead of forcing every contradiction into one average.

Use these buckets:

- `stable_trait`
- `situational_trait`
- `outlier`
- `unresolved_conflict`

Examples:

- "usually direct, but withdraws when overwhelmed"
- "normally humorous with friends, formal with strangers"
- "values honesty, but softens truth during repair"

This is closer to how real people behave than a flat persona sentence.

### Stage 8: Human Review And Publish

Imported personality should not go straight from raw text to production runtime without inspection.

Recommended review outputs:

- base-soul draft
- relationship overlays
- selected relationship binding / chosen counterpart lens
- promoted memory records
- low-confidence review queue
- anti-pattern list
- provenance links

Publish rules:

- approved base traits update draft `SOUL.md` content
- approved adaptive interaction signals write through the existing memory pipeline
- approved selected relationship binding determines which relationship overlay and relationship-state projection become active for this twin
- approved relationship events produce `RelationshipEvent` and `RelationshipState` candidates
- rejected or uncertain traits remain draft-only


#### Review Action Model

Review should operate on small explicit actions rather than one global approve/reject button.

Recommended review actions:

- `accept_field`
- `edit_field`
- `reject_field`
- `accept_overlay`
- `reject_overlay`
- `bind_relationship`
- `rebind_relationship`
- `publish_selected_only`
- `defer_for_later_review`
- `withdraw_import`

Recommended review order:

1. confirm `anchor_person_id`
2. confirm `current_user_role_binding`
3. confirm `selected_relationship_binding`
4. review base soul draft
5. review counterpart-scoped overlays
6. review promoted relationship and memory candidates

This prevents operators from approving overlays or relationship-state projections against the wrong active counterpart.

## Stable Vs Situational Trait Promotion

The core import problem is deciding what belongs in base soul and what belongs in an overlay.

### Promote To Base Soul When

- the trait appears across multiple dates
- the trait appears across multiple situations
- the trait is supported by both language and behavior
- the trait does not depend on one specific relationship
- the trait still holds after recency and outlier adjustments

Examples:

- habitually concise but emotionally precise
- often explains before refusing
- prefers indirect affection over explicit praise
- admits uncertainty instead of bluffing

### Keep As Relationship Overlay When

- the trait only appears with one person or one relationship cluster
- the trait mostly reflects trust, safety, or intimacy level
- the trait becomes active only after sufficient familiarity

Examples:

- teases only after familiarity is high
- uses intimate nicknames only with a bonded partner
- becomes more verbose when comforting a close person

### Keep As Situational Policy When

- the behavior depends mostly on the immediate scene
- it is not stable enough to be a durable base trait

Examples:

- becomes dry and short under acute stress
- uses humor to defuse public embarrassment
- avoids hard confrontation late at night when exhausted

## Confidence And Support Scoring

Each promoted trait should carry a transparent score.

Suggested formula:

```text
promotion_score =
  source_weight
  * recurrence_factor
  * cross_context_factor
  * self_disclosure_factor
  * corroboration_factor
  * recency_factor
  * anti_performance_factor
```

Practical interpretation:

- `>= 0.80`: high-confidence promotion candidate
- `0.55 - 0.79`: review candidate
- `< 0.55`: keep as evidence only unless manually promoted

Recommended adjustments:

- multiple independent dates raise `recurrence_factor`
- multiple relationship contexts raise `cross_context_factor`
- explicit first-person statements raise `self_disclosure_factor`
- fiction or public performance lowers `anti_performance_factor`
- old but still corroborated traits decay slowly, not instantly

## Proposed Soul Dimensions

The current typed `SoulProfile` is a good runtime scaffold, but imported human-style reconstruction needs richer behavior-level dimensions. Until some of these become first-class fields, they should live in `SoulProfile.extensions`.

Recommended extension keys:

- `value_order`
- `speech_rhythm`
- `explanation_style`
- `humor_style`
- `conflict_style`
- `affection_style`
- `uncertainty_style`
- `boundary_style`
- `repair_style`
- `signature_phrases`
- `anti_patterns`
- `source_style_mode`

Example draft shape:

```json
{
  "display_name": "target_person",
  "tone": "steady",
  "verbosity": "balanced",
  "extensions": {
    "value_order": "truth > tenderness > efficiency",
    "speech_rhythm": "short-to-medium sentences; low emoji density; uses soft qualifying phrases before refusal",
    "humor_style": "dry, restrained, occasionally self-mocking",
    "conflict_style": "explains first, then withdraws if misunderstood repeatedly",
    "uncertainty_style": "admits uncertainty directly; avoids fake certainty",
    "boundary_style": "clear but non-hostile refusal",
    "repair_style": "returns after cooling down with practical language",
    "signature_phrases": "不是...我是... | 说实话 | 我有点累",
    "anti_patterns": "never hyper-salesy; never generic therapeutic jargon"
  }
}
```

## Relationship Overlay Model

One person does not talk the same way to everyone. The importer should explicitly model relationship-conditioned behavior.

Recommended overlay axes:

- familiarity
- trust
- safety
- intimacy permission
- playfulness permission
- affection tendency
- reciprocity
- warmth offset
- formality offset
- initiative offset
- preferred naming
- preferred address style

These align naturally with the current `RelationshipState` and `InteractionPreferenceState` structures.

### Relationship Selector Boundary

The importer should explicitly separate two decisions:

- `current_user_role_binding`: who the user is treated as in the imported world
- `selected_relationship_binding`: which anchor-centered relationship lens is active for projection and overlay activation

These objects are related, but they are not interchangeable.

#### Binding Compatibility Contract

Recommended v1 rules for published one-on-one twins:

- at most one `selected_relationship_binding` may be active for one `twin_id`
- chat import: `selected_relationship_binding.counterpart_entity_id` must equal `current_user_role_binding.entity_id`
- fiction role import: in the normal "treat me as this role" flow, `selected_relationship_binding.counterpart_entity_id` must also equal `current_user_role_binding.entity_id`
- if a future product wants observer mode or third-person relationship lenses, define a new `interaction_mode` instead of silently letting the two bindings diverge
- rebinding supersedes the previous relationship lens, but it does not rewrite base soul

The records still stay separate because:

- `current_user_role_binding` is the world-model mapping
- `selected_relationship_binding` is the active one-on-one interaction lens
- versioning, review state, and invalidation are different


### Overlay Activation Contract

A relationship overlay should only become active when all of the following match:

- `twin_id`
- `anchor_person_id`
- `selected_relationship_binding.counterpart_entity_id`
- overlay `counterpart_id` or equivalent `overlay_key`

Recommended activation precedence:

1. exact `overlay_key` match
2. exact counterpart entity match
3. no activation; fall back to conservative relationship defaults

Do not fall back from one close relationship overlay to another “similar” one automatically.

The extractor may emit multiple counterpart-scoped relationship overlays in one import run.

It should not auto-pick one just because it appears most often in the graph.

Activation rule:

- import review or runtime setup selects one `selected_relationship_binding`
- the active relationship overlay is the overlay whose counterpart key matches that binding
- non-selected overlays remain draft or background material until the user explicitly rebinds

Rebind rule:

- changing the selected relationship with the same anchor and corpus should reuse graph import and base soul import
- rebinding should switch the active overlay key and rerun relationship projection for the new counterpart
- rebinding should not rewrite base soul just because the user chose a different relationship lens

### Publish Readiness Contract

A twin should be considered relationship-ready only when all of the following are true:

- `anchor_person_id` has been confirmed
- `current_user_role_binding` has been confirmed
- `selected_relationship_binding` has been explicitly confirmed
- a counterpart-scoped overlay with the matching `overlay_key` exists
- the initial relationship-state and interaction-preference projections were derived for that same selected counterpart

If any of these are missing:

- base soul and memory drafts may still remain in review
- relationship-specific publish should stay blocked, or be marked as explicit degraded/base-only mode
- runtime activation must not pretend to know which counterpart-specific overlay should be active

This rule applies to both chat import and fiction import.

If imported corpus has no direct target-person-to-current-user conversation, the initial relationship state should be conservative:

- familiarity: low
- trust: low-to-medium
- safety: medium
- intimacy permission: low
- playfulness permission: low

The system should earn higher intimacy through later interaction instead of pretending it already exists.

## Quote Bank And Anti-Pattern Bank

Quote handling should stay disciplined.

### Quote Bank

Purpose:

- calibrate style
- help judge similarity
- provide small hidden style exemplars when needed

Rules:

- store short excerpts, not large passages
- prefer high-recognition phrasing over generic lines
- do not inject many quotes on every turn
- do not let the model simply regurgitate copyrighted or private text

### Anti-Pattern Bank

Purpose:

- prevent uncanny failures
- block styles the target person would not plausibly use

Examples:

- never overuses generic therapy jargon
- never becomes cheerleader-like and motivational by default
- never uses corporate PR phrasing in intimate chat
- never claims certainty when unsure

In practice, anti-patterns often preserve identity better than extra positive style prompts.

## Output Mapping To Existing Runtime Paths

The import flow should publish through the same durable paths used elsewhere in OpenCray.

### Base Soul Publish Path

```text
approved BaseSoulDraft
  -> creator/admin review
  -> workspace SOUL.md draft or update
  -> SoulProfileResolver
  -> RuntimeSoulPromptComposer
```

### Memory Publish Path

```text
approved MemoryDraft
  -> MemoryCandidate
  -> MemoryWriter
  -> MemoryStore
  -> MemoryRetriever / memory_search / memory_get
```

### Relationship Selector Publish Path

```text
approved selected relationship binding
  -> twin binding / selected relationship record
  -> counterpart overlay resolver
  -> graph retrieval filter
  -> runtime current relationship overlay selection
```

### Relationship Publish Path

```text
approved relationship signals
  -> RelationshipEvent
  -> RelationshipMemoryWritePlanner
  -> RelationshipState snapshot candidate
  -> MemoryWriter
  -> runtime relationship projection
```

### Interaction Preference Publish Path

```text
approved warmth/formality/initiative/addressing signals
  -> interaction preference candidates
  -> InteractionPreferenceState snapshot
  -> runtime soul overlay
```


## Rebind And Invalidation Policy

Rebinding changes which relationship lens is active. It does not, by itself, prove that base identity changed.

Keep valid across rebind:

- imported source artifacts
- normalized turns and signal evidence
- base soul draft
- quote bank
- anti-pattern bank
- non-selected relationship overlays as draft artifacts

Recompute or reseat across rebind:

- active relationship overlay selection
- selected relationship projection bundle
- initial relationship-state seed
- initial interaction-preference seed
- review queue items whose rationale depended on the old selected counterpart

Withdraw or supersede, rather than silently overwrite:

- previously active selected relationship binding
- previously published selected-counterpart projection package that has not yet been reapproved

## Artifact Retention, Deletion, And Rollback

Importing private chats and relationship graphs is high-sensitivity work. The design should therefore define deletion behavior explicitly.

Recommended artifact classes:

- raw corpus
- normalized turns
- graph store
- draft outputs
- approved runtime-bound artifacts

Recommended rollback rules:

- withdrawing an unpublished import should remove its draft artifacts and selector state
- deleting an import session should also remove its graph namespace if no other active session depends on it
- deleting a selected relationship binding should deactivate its overlay and invalidate related selected-counterpart projections
- deleting one relationship binding should not delete anchor-scoped base soul artifacts unless the whole import session is removed

Recommended retention rule for MVP:

- keep only reviewable drafts and approved artifacts by default
- treat raw source retention as opt-in or operator-controlled

## Pending Contract Materialization

The design is now ahead of the checked-in schema files. Before wiring the import module into UI or runtime setup flows, at least these contracts should be materialized explicitly:

- `service/schemas/request_envelope.schema.json`
  - add selector operations such as `preflight_scan`, `create_twin_binding`, `list_relationship_candidates`, `select_relationship`, and `rebind_relationship`
  - replace the loose `params` object with a discriminated `oneOf` keyed by `operation`
  - make per-operation required fields explicit instead of relying on prose
- `service/schemas/opencray_draft_bundle.schema.json`
  - add `binding_context` with `current_user_role_binding`, `selected_relationship_binding_id`, `counterpart_entity_id`, and `overlay_key`
  - add `import_session` with `session_id`, `state`, and `source_hash`
  - add `relationship_projection_drafts` carrying `selected_relationship_binding_id`, `counterpart_entity_id`, `apply_scope`, `graph_distance`, `promotion_reason`, `supporting_events`, `promotion_score`, and `invalidation_scope`
  - add `background_context_drafts` so non-selected but relevant relationships stay reviewable without becoming active by accident
- `service/schemas/twin_binding.schema.json` plus a dedicated selected-relationship schema
  - make the split between world-model binding and active relationship lens explicit instead of implicit
  - keep `current_user_role_binding` required
  - allow the active selected relationship to be unset only before selector confirmation; require it for review-complete publish or runtime activation
- dedicated import-session and relationship-candidate-card schemas
  - capture mobile review state, selector cards, and artifact refs as first-class import artifacts
- checked-in `service/schemas/review_action.schema.json`
  - record `accept_field`, `edit_field`, `reject_field`, `bind_relationship`, `rebind_relationship`, `publish_selected_only`, and `withdraw_import` as typed review events instead of UI-only actions

These are import-time and review-time contract changes only. They do not alter runtime `soul` ownership or add a second personality system.

## Runtime Response Assembly

At generation time, a convincing person-like response should combine several bounded inputs rather than one huge persona prompt.

Recommended assembly order:

1. base soul
2. current relationship overlay resolved from `selected_relationship_binding` + live relationship state
3. current interaction-preference overlay
4. topic-relevant memory recall
5. optional small quote calibration hints
6. anti-pattern guardrails

Recommended generation loop:

```text
user message
  -> retrieve relevant memory
  -> resolve relationship and interaction state
  -> compose effective soul
  -> generate draft reply
  -> judge: similarity, consistency, anti-pattern, relationship fit
  -> return best candidate
```

This matters because "sounding like the person" is not just word choice. It is mostly:

- what they notice first
- what they answer first
- what they refuse to say
- how they calibrate closeness
- how they behave under tension

## Evaluation

The import design should include explicit evaluation, not just subjective vibes.

Recommended metrics:

- `in_character_score`: does a familiar human judge the response as plausible for this person
- `memory_consistency_score`: does the reply stay consistent with imported facts and preferences
- `relationship_fit_score`: is the tone appropriate for the current relationship band
- `anti_pattern_violation_rate`: how often the model slips into obviously wrong style
- `quote_regurgitation_rate`: how often it copies source text too directly
- `fiction_contamination_rate`: how often fiction-only material is asserted as real memory

Recommended test setup:

- hold out real conversations from the import set
- ask blind evaluators who know the target person
- compare against baseline prompt-only imitation
- score separately for comfort, conflict, planning, humor, and repair scenes


Add explicit test scenarios for lifecycle behavior:

- initial publish with one selected relationship binding
- rebind to another counterpart without recomputing base soul
- withdrawing a not-yet-published import session
- deleting one selected relationship binding while keeping the twin and base soul intact
- fiction import where narrator-only facts should not activate under the chosen role lens

## Recommended Rollout For OpenCray

### Step 1

Add import-side normalized objects:

- `SourceArtifact`
- `NormalizedTurn`
- `ExtractedSignal`
- reviewable draft outputs

### Step 2

Implement a first import extractor that targets:

- factual anchors
- explicit preferences
- interaction preference signals
- relationship events
- initial quote bank
- initial anti-pattern bank

### Step 3

Keep richer human-style traits in `SoulProfile.extensions` first instead of immediately expanding the typed `SoulProfile` surface.

### Step 4

Publish approved relationship and interaction signals through the existing memory-backed planners instead of writing custom side stores.

### Step 5

Add a post-generation judge for:

- similarity to imported soul
- memory consistency
- relationship appropriateness
- anti-pattern violations

### Step 6

Expose provenance in debug tools so operators can inspect:

- which imported evidence created each trait
- which traits were promoted to base soul
- which traits are relationship-specific overlays
- which traits remain hypotheses only

## Non-Goals

This design does not attempt to:

- guarantee a perfect one-to-one clone of a real person
- let normal chat directly rewrite core soul identity
- treat fiction as biography
- replace bounded memory retrieval with giant transcript injection
- remove the need for human review on sensitive identity imports

## Open Questions

- Which import sources should be allowed to revise `SOUL.md` automatically, if any?
- How should multi-lingual style traits be stored when the same person sounds different in Chinese and English?
- How aggressive should quote-bank usage be under copyright and privacy constraints?
- When no direct user-target conversation exists, how much relationship state should be inferred from third-party conversations?

## Summary

To clone the feel of a person from corpus, OpenCray should not ask one model to "act like them" from raw chat history. It should:

- normalize corpus into turn-level evidence
- extract typed memory and soul signals
- aggregate stable traits separately from relationship overlays
- publish durable outputs through the current memory and soul runtime layers
- judge outputs for similarity, consistency, and anti-pattern violations

That is the difference between a transcript-shaped prompt and a usable digital-twin initialization pipeline.



