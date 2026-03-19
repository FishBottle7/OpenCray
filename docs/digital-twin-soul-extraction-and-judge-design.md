# Digital Twin Soul Extraction And Judge Design

Last updated: 2026-03-18

## Status

Draft design for the `soul` extraction, candidate judging, and optional future LoRA strategy used by the independent personality extraction service.

## Related Designs

- `docs/digital-twin-corpus-import-design.md`
- `docs/digital-twin-graphiti-integration-design.md`

## Current Deployment Assumption

Current planning assumes:

- the product still generates through API models
- LoRA is not part of the near-term implementation path
- the extraction service should therefore maximize quality through structure, retrieval, and judging first

LoRA remains a later roadmap option only.

## Goal

Define how the independent personality extraction service should recover the part of a person that is not captured by a relationship graph:

- how they speak
- how they judge
- how they handle tension
- how they express care
- how they refuse
- how they repair
- what they would never plausibly say

This document focuses on `soul`, not relationship graph memory.

## Scope Boundary

This design is about `soul` initialization at import time.

It is not a proposal to redesign the existing OpenCray `soul` runtime architecture.

The extractor may:

- read imported corpus
- emit `SoulSignal` evidence
- aggregate a `BaseSoulDraft`
- produce judge outputs for import review or candidate reranking
- publish approved import results into the existing `SOUL.md` / `SoulProfile.extensions` path

The extractor may not, by itself:

- redefine `runtime/soul/*` responsibilities
- replace `SOUL.md` as base persona authority
- change prompt assembly ownership
- rewrite runtime relationship or memory architecture

If later architectural improvements are identified, they should be documented separately as optional follow-up proposals rather than folded into the initialization design by default.

## Core Position

If the target is to make a simulation feel strongly like one specific person, the system needs both:

- `relationship graph`: who matters, what happened, how those ties changed
- `soul`: how the person sounds and behaves while moving through those relationships

Graphiti can help with the first part. It does not solve the second.

`Soul` should therefore be extracted through a separate evidence-driven pipeline.

## What `Soul` Means Here

In this design, `soul` is not mystical and it is not just a style preset.

It is the compact representation of a person's stable conversational behavior:

- `speech surface`
- `judgment policy`
- `relationship expression policy`
- `conflict policy`
- `repair policy`
- `uncertainty policy`
- `boundary policy`
- `anti-pattern bank`
- `self-story and value order`

This is the layer that determines whether a response merely copies vocabulary or actually feels like the same person is speaking.

## Design Principle

Do not try to derive `soul` in one jump from raw corpus to a persona paragraph.

Use four levels:

1. raw turn evidence
2. turn-level `soul signals`
3. aggregated stable traits and overlays
4. candidate response judge and rerank

If level 2 is weak, levels 3 and 4 will drift.

## Boundary With Relationship Selection

The soul extractor should not decide which relationship the runtime is currently simulating.

Its responsibility is:

- emit one anchor-scoped `BaseSoulDraft`
- emit one counterpart-scoped relationship overlay draft containing multiple candidate overlays
- give the judge enough structure to score whether a reply fits one selected relationship lens

The explicit user-chosen `selected_relationship_binding` is what decides which overlay becomes active or gets published first.

This keeps the architecture stable:

- changing the selected relationship usually reuses the same base soul draft
- counterpart-scoped overlays may be switched or reprojected without treating the change as a soul rewrite
- the import-time selector and the runtime soul system keep clean boundaries


The counterpart-scoped overlay draft should therefore expose a stable activation handle.

Recommended fields per overlay:

- `counterpart_id`
- `overlay_key`
- `relationship_label`
- `narrative_moments`
- `highlighted_scripts`
- `confidence`

This lets runtime and import review activate one exact overlay instead of fuzzy-matching by prose.

### Overlay Identity Contract

The extractor may emit multiple counterpart-scoped overlays, but it should never emit anonymous or fuzzy-match-only overlays.

Recommended v1 invariants:

- every counterpart overlay must carry a stable `counterpart_id` and `overlay_key`
- at most one active overlay should match one `(twin_id, anchor_person_id, counterpart_id, overlay_key)` tuple in one draft version
- overlay activation must be exact-match first; semantic similarity is not a valid fallback
- if a published one-on-one twin presents a `current_user_role_binding` that does not align with the selected relationship counterpart, that should be treated as import configuration error, not as a style nuance the judge tries to smooth over
- rebinding selects a different overlay bundle and judge context; it does not mutate `BaseSoulDraft`

This keeps the soul extractor in an initialization role instead of letting it silently invent runtime routing rules.

## What Actually Has To Be Recovered

If the system is meant to feel like one particular person rather than a generic style imitation, the extractor has to recover more than "tone" or "vocabulary."

The highest-value targets are:

- `response ordering`: what they answer first
- `attention priority`: what they notice first in a message
- `decision policy`: how they trade clarity, warmth, fairness, self-protection, and speed
- `relationship calibration`: how they change with trust, intimacy, and distance
- `rupture and repair rhythm`: how they react when hurt, pressed, ignored, or misunderstood
- `negative boundaries`: what they almost never say, even when pressured
- `conditional contradictions`: when they act differently and why

In practice, users often experience "this really feels like them" when these deeper policies are right, even if the wording is not copied exactly.

## On "SVO"

`SVO` is ambiguous in this context, and both meanings are useful.

### 1. SVO As Event Structure

If by `SVO` you mean `subject-verb-object`, that is useful as a bottom semantic layer.

In dialogue and narrative extraction, the important question is often: who did what to whom, under what conditions, and with what consequence.

That does not directly equal `soul`, but it is essential support for it because it helps us distinguish:

- what the person did
- what the person said they did
- what the person wanted others to do
- what emotional or relational act the utterance was performing

For dialogue, simple sentence-level SVO is not enough. A better target is conversational SRL / OpenIE style proposition extraction because dialogue contains:

- ellipsis
- anaphora
- speaker shifts
- quoted speech
- cross-turn arguments

### 2. SVO As Social Value Orientation

If by `SVO` you mean `Social Value Orientation`, that is also useful, but at a different layer.

That theory is valuable because it helps estimate how a person trades off:

- own benefit
- other people's benefit
- fairness or equality
- competitive advantage

This is very relevant to `soul`, especially in:

- advice style
- conflict behavior
- generosity vs guardedness
- collaborative vs zero-sum framing

But social SVO is only one slice of the person. It should sit inside a broader value-and-relationship model, not replace it.

### 3. How The Two SVO Meanings Work Together

The two meanings of `SVO` should not be treated as separate curiosities. They are connected in the extraction pipeline.

- event-structure `SVO` helps recover repeated situations, actions, beneficiaries, and costs
- social `SVO` is then inferred from repeated tradeoff patterns across those situations

That means social value orientation should not be inferred from slogans alone, such as one line about "I care about fairness."

It should be inferred from recurrent patterns like:

- who the person protects when resources are scarce
- whether they accept unequal outcomes to preserve harmony
- whether they insist on fairness even at social cost
- whether they prioritize closeness, autonomy, or advantage under tension

In other words: event `SVO` provides the evidence substrate, and social `SVO` provides one of the deeper value summaries.

## Theory-Backed Extraction Lattice

The extraction logic becomes much more complete if it is organized as a layered lattice instead of one free-form persona summary.

### Layer A: Event Semantics

Purpose:

- recover proposition structure from dialogue and text
- support later inference about responsibility, intention, and social moves

Recommended tools and concepts:

- SVO-style proposition extraction
- semantic role labeling
- conversational semantic role labeling
- open information extraction

Questions this layer answers:

- who acted
- who was acted upon
- who requested what
- who caused what
- who benefited or lost

This layer is a prerequisite for good relationship and `soul` inference, but it is not itself the `soul` layer.

### Layer B: Speech Act / Dialogue Act

Purpose:

- identify what each utterance is doing socially

Recommended categories:

- request
- refuse
- advise
- reassure
- accuse
- confess
- promise
- threaten
- joke
- apologize
- repair bid
- boundary set

Why it matters:

The same factual content can feel like a different person depending on whether the utterance functions as a request, warning, comfort move, or social repair act.

### Layer C: Appraisal Layer

Purpose:

- model how the person evaluates situations before emotion and action are expressed

Useful appraisal dimensions:

- goal congruence vs blockage
- certainty vs uncertainty
- agency: self / other / circumstance
- controllability
- loss vs opportunity
- threat vs challenge

Why it matters:

This layer helps explain why one person responds to the same event with calm explanation while another responds with blame or panic.

### Layer D: Emotion Regulation Layer

Purpose:

- model what the person does with emotion after appraisal

Useful dimensions:

- reappraisal tendency
- suppression tendency
- delay before responding
- escalation tendency
- cooling-off habit
- physiological/behavioral spillover proxies in text such as repetition, fragmentation, abrupt cuts

Why it matters:

Two people may appraise a situation similarly but regulate it very differently in language.

### Layer E: Interpersonal Stance Layer

Purpose:

- model the person's default social posture in interaction

Recommended frame:

- `agency`
- `communion`

These dimensions can capture many important patterns:

- warm but low-control
- warm and directive
- distant and dominant
- distant and yielding

This layer is especially useful for distinguishing between people who sound equally concise but relate to others very differently.

### Layer F: Attachment / Closeness Layer

Purpose:

- model how closeness, vulnerability, and rupture are handled

Useful dimensions:

- closeness seeking vs distance seeking
- reassurance seeking
- avoidance under tension
- protest behavior
- repair openness
- relationship-specific security vs insecurity

Why it matters:

A lot of what users call a person's "soul" is really stable behavior around intimacy, fear of loss, distance, and repair.

### Layer G: Values Layer

Purpose:

- recover what consistently matters to the person

Recommended split:

- broader personal values
- social value orientation in interpersonal tradeoffs

Useful value families:

- care / benevolence
- truth / honesty
- autonomy / self-direction
- security / stability
- conformity / harmony
- achievement / ambition
- power / control
- stimulation / novelty

This layer should answer not just what values appear, but how they are ordered when they conflict.

### Layer H: Narrative Identity Layer

Purpose:

- recover the person's self-story and recurring meaning patterns

Useful signals:

- how they describe who they are
- recurring themes of burden, duty, freedom, shame, loyalty, survival, redemption, or futility
- whether they cast themselves as protector, outsider, builder, victim, caretaker, or witness

Why it matters:

Two people can have similar styles and values but feel different because they narrate themselves differently.

### Layer I: Surface Style Layer

Purpose:

- recover the final textual texture that people notice first

Useful dimensions:

- phrase choice
- paraphrase preference
- punctuation rhythm
- sentence length
- code-switching habit
- emoji density
- signature turns of phrase

This layer matters, but it should be downstream of the deeper layers above.

### Layer J: Relational Script Layer

Purpose:

- recover repeated interaction scripts rather than isolated traits

Examples:

- clarify feeling -> set limit -> withdraw
- joke -> soften -> confess
- test safety -> self-disclose -> retreat if not reciprocated
- go silent -> return later with practical repair

Why it matters:

People are often recognized less by one line and more by the sequence of moves they tend to make across two to six turns.

This layer is an engineering synthesis built from the layers above, especially speech acts, appraisal, regulation, attachment, interpersonal stance, and narrative identity.

## From Theory To Extractable Signals

The theory lattice is useful only if every layer maps to observable evidence and to a concrete runtime use.

- `event semantics` -> proposition tuples, responsibility cues, beneficiary cues, obligation cues -> used for memory alignment and decision inference
- `speech acts` -> request/refusal/comfort/repair/boundary act labels -> used for scene fit and candidate-act checking
- `appraisal` -> loss, blame, certainty, controllability, threat/challenge cues -> used for judgment-policy and emotion-policy inference
- `emotion regulation` -> reappraisal, suppression, escalation, delay, cooldown cues -> used for regulation-fit judging
- `interpersonal stance` -> agency/communion estimates -> used for closeness calibration and anti-uncanny filtering
- `attachment and closeness` -> protest, pursuit, withdrawal, reassurance, repair openness -> used for relationship overlays and rupture/repair scripts
- `values and social SVO` -> repeated self/other/fairness tradeoff votes -> used for value-order fit
- `narrative identity` -> self-frames, duty themes, shame themes, redemption or futility motifs -> used for self-story continuity
- `surface style` -> lexical preference, cadence, punctuation, emoji, paraphrase choice -> used for voice similarity
- `relational scripts` -> trigger -> move -> outcome templates -> used for multi-turn plausibility and script continuity

## Full Extraction Pipeline

### Stage 1: Source Preparation

Normalize all source corpus first.

Sources may include:

- private chat logs
- voice transcripts
- notes and journals
- long-form essays
- public posts
- fiction or creative work

Each source should carry:

- source type
- author confidence
- fictionality
- audience type
- time range
- language
- reliability weight

### Stage 2: Turn Segmentation

The extractor should operate on semantically meaningful turns, not arbitrary message chunks.

Rules:

- merge consecutive short messages from the same speaker when they clearly form one act
- keep topic shifts separate
- preserve quoted speech boundaries
- preserve timestamps whenever possible

### Stage 3: Scene Labeling

Before extracting personality signals, label the turn context.

Recommended labels:

- `small_talk`
- `decision`
- `comfort`
- `conflict`
- `repair`
- `boundary`
- `playfulness`
- `flirtation`
- `self_disclosure`
- `public_performance`
- `fatigue`
- `uncertainty`

This matters because the same wording can mean very different things in different scenes.

### Stage 4: Event And Act Extraction

Before extracting stable `soul` traits, recover the turn's semantic and pragmatic skeleton.

Outputs should include:

- proposition structure: who did what to whom
- speech act or dialogue act
- emotional appraisal cues
- agency and responsibility cues
- value tradeoff hints

This is where SVO-as-event-structure belongs.

### Stage 5: Turn-Level Soul Signal Extraction

Each turn should emit zero or more `soul signals`.

Recommended signal families:

- `speech_surface`
- `emotion_handling`
- `response_habit`
- `conflict_move`
- `uncertainty_move`
- `affection_move`
- `boundary_move`
- `repair_move`
- `value_hint`
- `self_view_hint`
- `speech_act_profile`
- `agency_communion_hint`
- `attachment_hint`
- `social_value_orientation_hint`
- `quote_candidate`
- `anti_pattern_hint`

Each signal should contain:

- `signal_type`
- `signal_value`
- `confidence`
- `support_weight`
- `evidence_excerpt`
- `scene_labels`
- `relationship_scope`
- `condition_tags`
- `language_mode`
- `time_scope`
- `source_ref`

### Stage 5B: Sequential Script Mining

Single-turn extraction is not enough for high-fidelity `soul`.

The service should also analyze short turn windows, such as 2 to 8 adjacent turns, to mine repeated scripts.

Recommended outputs:

- `opening_move_hint`
- `follow_up_move_hint`
- `closure_move_hint`
- `rupture_repair_script_hint`
- `conditioned_policy_hint`

Recommended script shape:

```json
{
  "trigger": ["conflict", "repeated_misunderstanding"],
  "move_sequence": [
    "clarify_feeling",
    "state_limit",
    "withdraw"
  ],
  "target_scope": "close_relationship",
  "outcome_tendency": "returns_after_cooldown",
  "confidence": 0.82
}
```

This is where the design moves from adjective labels like `warm` or `direct` into repeatable human patterns.

### Stage 6: Cross-Turn Aggregation

Aggregate signals across time and across scenes.

Rules:

- repeated evidence across different days is stronger than repeated evidence in one conversation
- repeated evidence across different scene labels is stronger than repeated evidence in one scene
- private first-person evidence is stronger than public performative evidence
- explicit self-description plus matching behavior is stronger than either one alone
- repeated trigger -> move sequences are stronger than isolated tone labels
- the same move under similar appraisal conditions is stronger than lexical repetition

Outputs:

- `BaseSoulDraft`
- `RelationshipExpressionOverlayDraft`
- `SituationPolicyDraft`
- `QuoteBankDraft`
- `AntiPatternBankDraft`

### Stage 7: Stable vs Situational Split

Not every extracted trait belongs in the core soul.

Promote to `base soul` only when the trait is:

- cross-time
- cross-scene
- not dependent on one specific person
- supported by both wording and behavior

Keep language-specific surface features in `language overlays` when deep policy is shared but wording habits differ across languages.

Store contradictions as conditional policies when both sides have evidence, for example:

- usually direct, but goes quiet when overwhelmed
- warm in comfort scenes, terse in decision scenes
- prosocial with close ties, fairness-first with distant ties

Keep in `relationship overlays` when the trait appears mainly with one relationship cluster.

Keep in `situation policy` when the trait appears mainly in one state such as conflict, repair, fatigue, or uncertainty.

### Stage 8: Judge and Rerank

Generation should not trust one first-pass response.

Recommended runtime loop:

1. generate multiple candidates from the same effective memory + soul context
2. run a judge over the candidates
3. reject anti-pattern failures
4. rerank the survivors
5. optionally rewrite the best candidate once using judge hints

This is often more effective than trying to make one prompt perfectly carry the whole person.

## Soul Signal Taxonomy

### 1. Speech Surface

Covers what the text sounds like before you analyze meaning.

Recommended fields:

- sentence length distribution
- clause chaining habit
- punctuation style
- emoji density
- filler usage
- rhetorical question frequency
- contrastive sentence patterns
- directness level
- explanation density

### 2. Judgment Policy

Covers how the person processes situations.

Recommended fields:

- fact-first vs feeling-first
- decision speed
- certainty posture
- speculation style
- moral framing habit
- advice tendency
- ambiguity tolerance

### 3. Relationship Expression Policy

Covers how the same person expresses different levels of closeness.

Recommended fields:

- warmth with close people
- warmth with strangers
- teasing threshold
- intimacy threshold
- naming and address style
- praise style
- comfort style

### 4. Conflict Policy

Recommended fields:

- accuse vs explain vs question vs withdraw
- how fast the person escalates
- whether they soften before refusing
- whether they restate boundaries
- whether they punish vulnerability

### 5. Repair Policy

Recommended fields:

- whether the person re-opens contact after tension
- apology shape
- cooldown duration pattern
- whether repair is emotional, practical, or indirect

### 6. Boundary Policy

Recommended fields:

- softness of refusal
- explicitness of refusal
- redirect habit
- soothe-then-refuse habit
- whether they close the topic firmly

### 7. Uncertainty Policy

Recommended fields:

- openly admits uncertainty
- hedges with soft qualifiers
- speculates carefully
- pretends certainty
- delays commitment

### 8. Values and Social SVO Policy

Recommended fields:

- prosocial vs self-maximizing vs competitive tendency
- fairness sensitivity
- sacrifice threshold for close others
- autonomy vs harmony preference
- truth vs face-saving preference
- security vs novelty preference

### 9. Narrative Identity

Recommended fields:

- core self-frame
- recurring burden or mission themes
- shame / pride / duty motifs
- redemption vs resignation arc tendency

### 10. Relational Scripts

Recommended fields:

- trigger pattern
- opening move
- follow-up move
- exit move
- relationship scope
- scene scope
- expected repair path

These are not just style tags. They are compact interaction programs.

### 11. Language Mode Overlay

Recommended fields:

- language tag
- directness shift by language
- code-switch threshold
- slang tolerance
- punctuation and emoji shift
- confidence by language

Do not average all languages into one flattened surface style if the corpus is multilingual.

### 12. Anti-Pattern Bank

Recommended entries:

- tones the person almost never uses
- phrases that feel generic or artificial for them
- social postures that violate their usual value order
- syntactic patterns that read unlike them

In practice, anti-pattern filtering often protects identity more effectively than adding more positive style tokens.

## Recommended Soul Draft Shape

A practical `BaseSoulDraft` can be structured like this:

```json
{
  "identity_view": "how this person tends to frame selfhood",
  "language_modes": {
    "zh-CN": {
      "surface_confidence": 0.91,
      "emoji_density": "low",
      "directness_shift": "softened_before_refusal"
    },
    "en-US": {
      "surface_confidence": 0.42,
      "notes": "not enough evidence to overfit English surface style"
    }
  },
  "speech_surface": {
    "sentence_length": "short_medium",
    "punctuation_style": "light",
    "emoji_density": "low",
    "signature_patterns": [
      "不是...我是...",
      "说实话"
    ]
  },
  "judgment_policy": {
    "fact_vs_feeling": "feeling_first_then_reason",
    "certainty_style": "admits_uncertainty",
    "decision_style": "slow_but_clear"
  },
  "interpersonal_stance": {
    "agency": "medium",
    "communion": "high"
  },
  "conflict_policy": {
    "default": "explain_then_withdraw",
    "boundary_style": "clear_but_not_hostile",
    "repair_style": "returns_after_cooldown"
  },
  "conditional_policies": [
    {
      "when": [
        "conflict",
        "repeated_misunderstanding"
      ],
      "move_sequence": [
        "clarify_feeling",
        "state_limit",
        "withdraw"
      ],
      "confidence": 0.81
    }
  ],
  "relational_scripts": [
    {
      "name": "late_repair",
      "trigger": [
        "distance_after_tension"
      ],
      "moves": [
        "resume_contact",
        "practical_language",
        "light_reassurance"
      ]
    }
  ],
  "affection_policy": {
    "mode": "restrained_indirect"
  },
  "value_order": [
    "truth",
    "care",
    "autonomy"
  ],
  "social_value_orientation": "prosocial_but_bounded",
  "anti_patterns": [
    "generic_therapy_tone",
    "salesy_encouragement",
    "overly_formal_ai_style"
  ]
}
```

## Promotion Logic For Deep Soul Fields

Each candidate trait should carry at least two scores, not one:

```text
stability_score =
  recurrence_across_days
  * recurrence_across_scenes
  * source_reliability
  * wording_behavior_alignment

conditionality_score =
  relationship_concentration
  + scene_concentration
  + state_concentration
```

Interpretation:

- high `stability_score`, low `conditionality_score` -> promote to `base soul`
- medium `stability_score`, high `relationship_concentration` -> relationship overlay
- medium `stability_score`, high `scene_concentration` -> situation policy
- low `stability_score` -> evidence only

Script promotion should require repeated occurrences with temporal separation. One heated conversation is not enough to define a life-like relational script.

## Judge Design

The `judge` is not just a style checker. It is the quality-control layer for whether a candidate is plausibly this person in this context.

### Judge Responsibilities

A good judge should answer:

- does this sound like this person
- does this behave like this person
- does this fit the current relationship band
- does this fit the current scene
- does this contradict imported memory
- does this violate known anti-patterns
- does this preserve the person's inferred value order and interpersonal stance
- does it follow a plausible opening, escalation, and closure pattern for this person
- if the conversation continued, would the next move also still look like them

### Judge Input Contract

The judge should not rely on generic `relationship_scope` alone once a selector exists.

Recommended required import-time or generation-time inputs:

- `selected_relationship_binding_id`
- `counterpart_entity_id`
- `overlay_key`
- `relationship_scope`
- `scene_labels`
- `source_mode`

Recommended optional structured hints:

- `relationship_state_hints`
- `interaction_preference_hints`
- `memory_hints`
- `reference_quotes`
- `recent_script_hints`

If a selected relationship binding exists but the judge is called without counterpart context, that should be treated as degraded evaluation quality rather than silently scored as fully reliable.

### Judge Dimensions

Recommended primary scored dimensions:

- `voice_similarity`
- `decision_similarity`
- `relationship_fit`
- `scene_fit`
- `memory_consistency`
- `value_order_fit`
- `interpersonal_stance_fit`
- `speech_act_fit`
- `regulation_fit`
- `script_continuity_fit`
- `anti_pattern_risk`
- `quote_regurgitation_risk`
- `fiction_contamination_risk`

Recommended secondary diagnostics:

- `opening_move_fit`
- `closure_fit`
- `boundary_calibration_fit`
- `intimacy_calibration_fit`


Recommended selector-aware diagnostics:

- `selected_counterpart_fit`
- `overlay_activation_fit`
- `relationship_state_fit`
- `rebind_contamination_risk`

Recommended boolean gates:

- `passes_identity_gate`
- `passes_relationship_gate`
- `passes_consistency_gate`
- `passes_style_gate`

### Judge Output Shape

```json
{
  "in_character": 0.84,
  "voice_similarity": 0.79,
  "decision_similarity": 0.88,
  "relationship_fit": 0.91,
  "scene_fit": 0.82,
  "memory_consistency": 0.93,
  "value_order_fit": 0.86,
  "interpersonal_stance_fit": 0.80,
  "speech_act_fit": 0.89,
  "regulation_fit": 0.84,
  "script_continuity_fit": 0.78,
  "anti_pattern_risk": 0.08,
  "passes_identity_gate": true,
  "passes_relationship_gate": true,
  "passes_consistency_gate": true,
  "reasons": [
    "short restrained phrasing fits this person's speech surface",
    "response explains feeling before boundary, which matches their conflict policy"
  ],
  "problems": [
    "ending softens a little too much for this scene"
  ],
  "rewrite_hint": "shorten the final sentence and remove extra reassurance"
}
```

### Pending Schema Alignment

The checked-in judge and draft schemas should eventually reflect the selector-aware contract above.

Recommended target changes:

- `service/schemas/judge_candidate_batch.schema.json`
  - make `scene_labels`, `relationship_scope`, and `source_mode` required
  - add `selected_relationship_binding_id`
  - add `counterpart_entity_id`
  - add `overlay_key`
  - add optional `relationship_state_hints`
  - add optional `interaction_preference_hints`
  - add optional `recent_script_hints`
  - when a selected relationship lens is present, require the full trio of `selected_relationship_binding_id`, `counterpart_entity_id`, and `overlay_key` together instead of accepting partial context
- `service/schemas/opencray_draft_bundle.schema.json`
  - allow counterpart-scoped overlay refs and selected-binding refs so judge inputs can be reconstructed without prose parsing
- dedicated overlay-draft schema if draft bundles stay normalized
  - require `counterpart_id`, `overlay_key`, `relationship_label`, `highlighted_scripts`, `narrative_moments`, and confidence metadata
- dedicated `judge_result.schema.json` or `judge_result_batch.schema.json`
  - include selector-aware diagnostics such as `selected_counterpart_fit`, `overlay_activation_fit`, `relationship_state_fit`, `rebind_contamination_risk`, `hard_veto`, and `veto_reasons`
  - include degraded-context fields such as `context_quality` or `degraded_context_reason` so missing selector context is visible instead of silently baked into the score
- `service/schemas/request_envelope.schema.json`
  - make `judge_candidates` a discriminated operation with typed params rather than a free-form pass-through object

This schema work is still part of import-time initialization and candidate evaluation. It does not redesign runtime `soul`.

### Judge Modes

#### Import-Time Judge

Used when promoting extracted traits.

Goal:

- reject weak or noisy trait summaries
- catch contradictions early
- prevent fiction-only features from being promoted as stable soul

#### Generation-Time Judge

Used when scoring candidate replies.

Goal:

- select the most in-character response
- reject obvious anti-pattern failures
- trigger one-step repair or rewrite when needed

Generation-time judge should fail closed when the runtime claims to be in a specific selected relationship lens but cannot supply:

- the active `selected_relationship_binding_id`
- the active counterpart overlay or equivalent `overlay_key`
- the current relationship-state snapshot or a conservative fallback

In that case, the system should either:

- downgrade to a conservative generic relationship mode
- or request the missing relationship context before claiming strong in-character fidelity

#### Sequence Judge

Used for evaluation and for selected high-stakes multi-turn interactions.

Goal:

- score whether a 2 to 5 turn trajectory still looks like the same person
- verify rupture, repair, and boundary sequences
- detect drift that is invisible in single-turn judging

## Candidate Generation Strategy

A strong judge is most useful when the system generates more than one candidate.

Recommended approach:

- create 3 to 5 candidate replies with moderate variation
- keep the same effective memory and soul context across candidates
- let only the surface realization vary
- judge all candidates
- rerank by weighted score

Suggested weighted ranking:

```text
final_score =
  0.18 * voice_similarity +
  0.16 * decision_similarity +
  0.10 * relationship_fit +
  0.08 * scene_fit +
  0.10 * memory_consistency +
  0.08 * value_order_fit +
  0.07 * interpersonal_stance_fit +
  0.07 * speech_act_fit +
  0.08 * regulation_fit +
  0.08 * script_continuity_fit -
  0.18 * anti_pattern_risk -
  0.05 * quote_regurgitation_risk -
  0.05 * fiction_contamination_risk
```

Hard reject when:

- anti-pattern risk is high
- memory consistency is below threshold
- relationship fit clearly fails
- speech act fit clearly fails for the scene

## How To Make The Extraction Logic More Complete

If the goal is to recover a person as fully as possible, the service should not stop at style labels like `warm` or `direct`.

It should explicitly model:

- how the person starts replies
- what the person notices first
- what the person avoids saying
- what the person sounds like when tired
- what changes with intimacy
- what changes under conflict
- what changes during repair
- which contradictions are stable and human rather than extraction noise
- which propositions recur across very different contexts
- which social tradeoffs recur when self-interest and care conflict

Recommended completeness checklist:

- event semantics captured before trait summarization
- cross-language behavior captured separately when needed
- relationship overlays split from base soul
- fatigue or stress mode modeled separately
- repeated relational scripts captured, not just one-turn style tags
- anti-pattern bank always maintained
- quote bank used only for calibration, not as a training crutch
- fiction-only features kept as hypotheses unless corroborated
- judge used both at import time and generation time
- sequence judge used in evaluation for multi-turn fidelity
- value order inferred from repeated conflict cases, not one-off slogans
- interpersonal stance inferred from both wording and response effect

## LoRA: Current Position

### Near-Term Plan

Do not use LoRA in the current implementation.

Reasons:

- current deployment is API-first
- the highest-risk problems are still structural, not stylistic
- LoRA cannot replace memory retrieval, relationship reasoning, or judge-based quality control

### Roadmap Position

Keep LoRA in the roadmap as an optional future surface-style amplifier only.

If used later, LoRA should be placed after:

1. relationship graph extraction
2. structured soul extraction
3. judge and rerank
4. evaluation against held-out real conversations

### Best Role For Future LoRA

Use LoRA only for:

- speech surface
- local cadence
- lexical texture
- punctuation and stylistic habits

Do not rely on LoRA for:

- stable facts
- relationship state
- scene reasoning
- value order
- memory consistency
- anti-pattern filtering

### Training Locality If LoRA Is Added Later

If LoRA becomes relevant in a later phase, it should not be trained on the mobile device.

Recommended path:

- corpus cleaning and dataset packaging off-device
- training on workstation or cloud GPU
- hold-out evaluation before export
- mobile only receives a prebuilt adapter if deployment later proves worthwhile

This keeps the phone-side architecture simple and avoids pushing unstable training logic into the app.

Recommended future architecture:

```text
memory + relationship retrieval
  -> effective soul context
  -> base model or future style-adapted model generates candidates
  -> judge reranks candidates
```

## Evaluation Plan

A usable evaluation stack should include:

- `persona similarity`: does a familiar evaluator find it plausible
- `decision similarity`: does the response make the same kind of move the person usually makes
- `relationship fit`: is closeness calibrated correctly
- `memory consistency`: no contradiction with imported facts
- `value order fit`
- `interpersonal stance fit`
- `regulation fit`
- `script continuity fit`
- `anti-pattern violation rate`
- `quote-regurgitation rate`
- `fiction contamination rate`

Recommended eval setup:

- hold out real conversations from training and import
- ask blind evaluators who know the person
- score across comfort, conflict, planning, humor, repair, and refusal scenes
- add 2 to 5 turn sequence tests for rupture, repair, and boundary maintenance
- score multilingual surface fidelity separately when applicable
- compare baseline prompting against structured extraction plus judge
- only later compare structured-only against structured-plus-LoRA if LoRA becomes relevant

## Recommended Rollout

### Step 1

Add explicit event-semantics, `soul signal`, and short-window script extraction to the personality service.

### Step 2

Add aggregated outputs:

- `BaseSoulDraft`
- `RelationshipExpressionOverlayDraft`
- `SituationPolicyDraft`
- `QuoteBankDraft`
- `AntiPatternBankDraft`

### Step 3

Add an import-time judge for trait promotion quality.

### Step 4

Add generation-time candidate judge and rerank, with optional sequence judging for evaluation.

### Step 5

Keep future LoRA in the roadmap only as an optional late-stage surface-style experiment.

## Current Phase 2 Module Status

The current standalone import-time module in `service/soul_extractor.py` now implements the second-stage quality upgrade without changing runtime `soul` architecture.

Implemented in the module now:

- explicit `event_semantics` signals for turn-window situation reading
- explicit `appraisal_hint` signals for self-state, other-appraisal, and core need
- explicit `value_tradeoff_hint` signals so value order is not reduced to keyword counts only
- short-window script mining with `opening_move_hint`, `follow_up_move_hint`, and `closure_move_hint`
- aggregated `appraisal_tendencies` and `value_tradeoffs` in `BaseSoulDraft`
- richer `language_modes` with sentence length, punctuation style, and signature patterns
- judge-side language-aware `voice_similarity`
- judge-side warmth / pressure / distance relationship calibration
- judge-side structural anti-pattern detection beyond lexicon matches
- order-sensitive `script_continuity_fit` plus `hard_veto` and `veto_reasons`

This remains an initialization module only. It does not rewrite `runtime/soul/*`, does not add new runtime tools, and does not wire itself into the create-agent page yet.

Known remaining gaps for a later pass:

- explicit reply-link resolution when `reply_to_turn_id` is missing but timestamps allow safe inference
- stronger recurrence promotion across multiple windows separated by time, not just adjacent evidence
- deeper event role recovery for fiction scenes with more than two salient actors

## References

- Social Value Orientation: Murphy, Ackermann, and Handgraaf, “Measuring Social Value Orientation”
  https://www.cambridge.org/core/journals/judgment-and-decision-making/article/measuring-social-value-orientation/78981D731BFB89AFCFC789D40FD8C11F
- Basic values: Schwartz, “An Overview of the Schwartz Theory of Basic Values”
  https://scholarworks.gvsu.edu/orpc/vol2/iss1/11/
- Adult attachment: Fraley, “Attachment in Adulthood: Recent Developments, Emerging Debates, and Future Directions”
  https://www.annualreviews.org/content/journals/10.1146/annurev-psych-010418-102813
- Emotion regulation: Gross, “Emotion regulation: Affective, cognitive, and social consequences”
  https://www.cambridge.org/core/journals/psychophysiology/article/emotion-regulation-affective-cognitive-and-social-consequences/552536BD5988D0D2079A7E0CC82E1ED8
- Interpersonal circumplex: Widiger, “Personality, interpersonal circumplex, and DSM-5”
  https://pubmed.ncbi.nlm.nih.gov/20954054/
- Dynamic interpersonal rupture patterns: Luo et al., “Using Interpersonal Dimensions of Personality and Personality Pathology to Examine Momentary and Idiographic Patterns of Alliance Rupture”
  https://pubmed.ncbi.nlm.nih.gov/34484067/
- Speech act theory in agent evaluation: Hanna and Richards, “Speech Act Theory as an Evaluation Tool for Human-Agent Communication”
  https://www.mdpi.com/1999-4893/12/4/79
- Narrative identity: McAdams, “The Psychology of Life Stories”
  https://www.scholars.northwestern.edu/en/publications/the-psychology-of-life-stories/
- Phrase choice and style: Preoţiuc-Pietro, Carpenter, and Ungar, “Personality Driven Differences in Paraphrase Preference”
  https://aclanthology.org/W17-2903/
- Conversational SRL: Fei et al., “Conversational Semantic Role Labeling with Predicate-Oriented Latent Graph”
  https://www.ijcai.org/proceedings/2022/571
- SRL in dialogue rewriting: Xu et al., “Semantic Role Labeling Guided Multi-turn Dialogue ReWriter”
  https://aclanthology.org/2020.emnlp-main.537/
- Open IE survey: Niklaus et al., “A Survey on Open Information Extraction”
  https://aclanthology.org/C18-1326/
- Appraisal theory overview: Roseman and Smith, “Appraisal Theory Overview, Assumptions, Varieties, Controversies”
  https://academic.oup.com/book/53557/chapter/422115670
- Appraisal determinants: Roseman, “Appraisal Determinants of Emotions”
  https://www.tandfonline.com/doi/abs/10.1080/026999396380240
- Appraisal and language: Ellsworth, “Appraisal Theory: Old and New Questions”
  https://philpapers.org/rec/ELLATO-4
- Appraisal in NLP: Troiano, Oberländer, and Klinger, “Dimensional Modeling of Emotions in Text with Appraisal Theories”
  https://ouci.dntb.gov.ua/en/works/lD3AeYL4/

## Summary

To recover a person as completely as possible, the extraction service should not rely on raw transcript prompting, relationship graphs alone, or future LoRA alone.

The strongest path is:

- event semantics
- structured relationship graph
- structured soul signal extraction
- value and self-story inference
- stable trait and overlay aggregation
- anti-pattern control
- candidate judge and rerank
- optional late-stage future LoRA for surface style only

That order gives the best chance of making the simulation feel strongly like one specific person without collapsing into shallow catchphrase imitation.
