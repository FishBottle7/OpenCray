# Personality Extraction Service Skeleton

This directory contains standalone Python modules for the digital-twin import pipeline described in:

- `docs/digital-twin-corpus-import-design.md`
- `docs/digital-twin-graphiti-integration-design.md`
- `docs/digital-twin-soul-extraction-and-judge-design.md`

The service is intentionally independent from the OpenCray agent runtime. It is meant to run as:

- a local CLI during import and batch processing
- a local file-bridge target for mobile-style request/response JSON invocation

Its job is import-time initialization and draft generation only. It does not modify the existing OpenCray runtime soul architecture.

## What Is Here

- `graphiti_adapter.py`
  - relationship-graph sidecar
  - Graphiti bootstrap
  - chat/work corpus ingestion skeleton
  - anchor-aware search skeleton
  - OpenCray relationship and memory draft export skeleton
- `soul_extractor.py`
  - standalone soul initialization module
  - chat/work corpus -> `SoulSignal` extraction
  - `BaseSoulDraft` aggregation
  - candidate `judge` / rerank support
  - `run_request` file-bridge entrypoint for mobile integration
- `schemas/`
  - request envelopes, graph drafts, soul signals, base soul drafts, relationship overlay drafts, judge outputs, and candidate batch contracts
- `examples/`
  - sample corpora, request envelopes, and judge input/output examples

## Setup

Graphiti requires Python 3.10+.

Typical local setup:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\python -m pip install "graphiti-core[kuzu]"
```

On the current Windows smoke-test machine, `graphiti-core` itself installed successfully, but `kuzu` had no prebuilt `win_amd64` wheel for Python 3.14 and fell back to a native build that requires an additional local C/C++ toolchain. The pragmatic options are to use WSL/Linux with Python 3.12+ or native Windows Python 3.12/3.13.

`soul_extractor.py` itself only uses the Python standard library.

## Graphiti Provider Bridge

For import-time Graphiti calls, the service now supports two config layers:

- `llm_config`: default source of truth, typically copied from the app's current LLM settings
- `graphiti_config`: optional per-import override for `llm`, `embedder`, and `cross_encoder`

This means the mobile import flow can default to the user's existing app model configuration, while still letting Graphiti use a separate embedding or reranking provider when needed.

Security boundary:

- request-time secrets such as `apiKey` stay in the request payload
- persisted `binding` / `import_session` files only store a non-secret `graphiti_runtime_preferences` summary
- no Graphiti provider secret is written into the workspace binding JSON by default

Examples:

- `service/examples/request_init_with_llm_config.sample.json`
- `service/examples/request_ingest_chat_with_graphiti_override.sample.json`

## Quick Start

Run a selector preflight scan over chat corpus:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py preflight_scan --source-mode chat_history --source-refs service/examples/chat_corpus.sample.json
```

The selector-facing `counterpart_candidates` are now ranked by an explicit anchor-centered relationship graph neighborhood score, with fields such as `graph_neighbor_rank`, `graph_distance`, `graph_proximity_score`, and `ranking_reasons` explaining why one counterpart is surfaced ahead of another.
A `create_twin_binding` call with `source_refs` will also persist that neighborhood into `graphs/<twin_id>.relationship_graph_manifest.json`, so later selector calls can reuse the ranked relationship neighborhood without rescanning the original corpus files. When Graphiti is available, `list_relationship_candidates` will additionally try to resolve the anchor node UUID and refresh the selector ordering from the live Graphiti neighborhood.

Create a twin binding and import session:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py init --twin-id twin_lin_01 --anchor-person-id actor_lin --interaction-mode chat_twin --source-mode chat_history --binding-entity-id actor_current_user --source-refs service/examples/chat_corpus.sample.json
```

Persist the selected relationship lens:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py select_relationship --twin-id twin_lin_01 --anchor-person-id actor_lin --counterpart-entity-id actor_current_user
```

Inspect the aggregated import-session snapshot that a mobile review page can consume directly:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py get_import_session --twin-id twin_lin_01
```
Withdraw an unpublished import session and clear its draft artifacts:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py withdraw_import --twin-id twin_lin_01
```
Ingest chat corpus into Graphiti:

```powershell
.\.venv\Scripts\python service/graphiti_adapter.py ingest_chat --twin-id twin_lin_01 --source service/examples/chat_corpus.sample.json
```

Extract soul signals and a base soul draft from chat corpus:

```powershell
python service/soul_extractor.py extract_chat_soul --twin-id twin_lin_01 --source service/examples/chat_corpus.sample.json --anchor-person-id actor_lin --output-name sample_chat_soul
```

`relationship_overlay_path` points to a companion overlay draft that summarizes counterpart-specific expression, following `service/schemas/relationship_expression_overlay_draft.schema.json` and illustrated by `service/examples/relationship_expression_overlay_draft.sample.json`.

Judge candidate replies against the extracted soul draft:

```powershell
python service/soul_extractor.py judge_candidates --draft .opencray/personality_service/soul/twin_lin_01/sample_chat_soul.base_soul_draft.json --candidates service/examples/judge_candidates.sample.json --output-name sample_judge
```

`judge_candidates` can optionally consume selector-aware relationship context from the candidate batch, including `selected_relationship_binding_id`, `counterpart_entity_id`, `overlay_key`, `relationship_state_hints`, `interaction_preference_hints`, and `recent_script_hints`. When present, judge output includes additional diagnostics such as `selected_counterpart_fit`, `overlay_activation_fit`, `relationship_state_fit`, `rebind_contamination_risk`, and `context_quality`.

## Mobile-Style File Bridge

Both `graphiti_adapter.py` and `soul_extractor.py` support `run_request`.

Examples:

```powershell
python service/soul_extractor.py run_request --request service/examples/request_extract_chat_soul.sample.json --response .opencray/personality_service/cache/last_soul_response.json
```

```powershell
python service/soul_extractor.py run_request --request service/examples/request_judge_candidates.sample.json --response .opencray/personality_service/cache/last_judge_response.json
```

```powershell
python service/graphiti_adapter.py run_request --request service/examples/request_get_import_session.sample.json --response .opencray/personality_service/cache/last_session_response.json
```
```powershell
python service/graphiti_adapter.py run_request --request service/examples/request_withdraw_import.sample.json --response .opencray/personality_service/cache/last_withdraw_response.json
```
The request format is defined by `service/schemas/request_envelope.schema.json`.

## Service Data Layout

By default the service writes under:

- `.opencray/personality_service/graphs/`
  `*.relationship_graph_manifest.json` files keep the persisted anchor neighborhood used by the selector.
- `.opencray/personality_service/bindings/`
- `.opencray/personality_service/exports/`
- `.opencray/personality_service/soul/`
- `.opencray/personality_service/cache/`

## Notes

- `graphiti_adapter.py` handles relationship graph import; it does not implement soul extraction.
- `soul_extractor.py` is an import-time initialization module, not a runtime soul resolver.
- The adapter can now best-effort resolve the anchor node UUID from the persisted manifest and a live Graphiti store. If Graphiti is unavailable, selector ranking falls back to the persisted relationship graph manifest and any explicit `focal_node_uuid` already stored on the binding.
- Neither module is wired into app startup or Gradle packaging. Right now they are independent import-time modules only.





