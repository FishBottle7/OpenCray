# Design: Native Skills Package Manager Tools

Date: 2026-03-18

## Goal

Add a first-class skills package-manager surface to OpenCray's Android-hosted runtime without depending on `bash`, Node.js, `npm`, or `npx`.

The target outcome is not partial compatibility. The target outcome is feature parity with the practical `npx skills` workflow through OpenCray-native tools and host-managed installation state.

## Decision

OpenCray should not implement a `bash`-level or shell-level compatibility layer for `npx skills`.

OpenCray should instead add a dedicated native tool surface for skill package management and update built-in skill guidance to use those native tools directly.

## Why this is the right decision

### 1. `npx` is not the real dependency

The real dependency is the skills package-management workflow:

- search
- install
- list
- remove
- check
- update
- init

`npx` is only one host-specific invocation method for that workflow.

If OpenCray implements the workflow directly, it gets the actual capability without inheriting Node/npm runtime assumptions that do not fit the Android host.

### 2. Shell compatibility would be the wrong abstraction boundary

OpenCray already has structured runtime tools, policy gates, approval semantics, and traceability for agent actions.

If `npx skills` is tunneled through `bash`, several problems appear:

- policy and approval become attached to one opaque shell string instead of a typed skill-management action
- search and install state become harder to trace in runtime metadata
- argument parsing has to emulate shell and CLI behavior instead of using structured contracts
- non-skills `npx` commands become an ongoing ambiguity and maintenance burden

This would optimize for preserving a command spelling rather than preserving the real product capability.

### 3. The current app architecture already favors a native implementation

The current Android app already has:

- app-managed skill roots under `filesDir/skills`
- a catalog root under `filesDir/skills-catalog`
- a loader and validator that accept local `SKILL.md` directory structures

What is missing is package-manager behavior, not local skill loading.

That means the shortest credible path is:

1. fetch or resolve a remote skill source
2. materialize a local skill directory
3. validate with existing skill loading rules
4. record installation metadata
5. expose the installed skill to the app and runtime

### 4. The built-in `find-skills` guidance is allowed to change

There is no product requirement to preserve the current `npx skills` phrasing inside built-in skills forever.

If OpenCray grows its own native package-manager tools, built-in guidance should be updated to recommend those tools explicitly instead of teaching the model to emit `npx skills ...`.

That is a smaller and cleaner maintenance cost than carrying a compatibility layer indefinitely.

## Current repository reality

The current implementation is still much closer to local skill storage than to a package manager:

- `SkillsFacade` installs suggested skills by copying directories from the local catalog into the managed root
- local and git install sources are present in the UI model but still unwired
- `OpenCrayHostRuntime.installSuggestedSkill(...)` assumes installation from the local catalog
- `SkillLoader` and `SkillValidator` already provide the file-based validation and loading path that a native package manager can reuse

This matters because it means package-manager work should be built above the existing loader instead of trying to route around it.

## Implementation update

Status as of 2026-03-18:

- `SkillInstallManifestStore` and `SkillPackageManager` are now in place for local catalog installs
- the existing local catalog install/remove path has been routed through `SkillPackageManager`
- the first native runtime tools now exist for the local-only slice:
  - `SkillsFind`
  - `SkillsList`
  - `SkillsAdd`
  - `SkillsRemove`
- these tools run through `OpenCrayToolDispatcher` and the shared `ToolPolicyPipeline`

Important boundary update:

- skills package-manager tools operate on host-managed roots under app-private storage, not the user workspace
- to keep them inside the unified tool pipeline, policy evaluation now needs explicit approved host-managed read/write roots outside the workspace root
- this is preferable to bypassing the pipeline through host-only bridge methods because approvals, metadata, and audit traces stay consistent with the rest of the tool system

## Scope

This design covers:

- native tools for skill package management
- host-managed installation metadata
- source resolution and remote fetch strategy
- rollout strategy for search, add, list, remove, check, update, and init
- follow-up updates to built-in skill instructions

This design does not cover:

- executable runtime skill behavior beyond current skill visibility and activation work
- sub-agent execution semantics for `context: fork` skills
- a public marketplace UI redesign
- generic shell compatibility for arbitrary npm ecosystem commands

## Proposed tool surface

OpenCray should add typed native tools instead of overloading `bash`.

### Core typed tools

- `SkillsFind`
- `SkillsAdd`
- `SkillsList`
- `SkillsRemove`
- `SkillsCheck`
- `SkillsUpdate`
- `SkillsInit`

### Optional convenience wrapper

An optional wrapper tool may be added later:

- `SkillsCli`

This wrapper would accept a structured subcommand request and dispatch internally to the typed tools.

It is optional because the typed tools are the real API. The wrapper exists only if preserving a CLI-like mental model is still useful inside prompts or migration code.

## Why a thin wrapper could still exist

A compatibility layer is not required at the shell boundary.

However, an internal wrapper can still be useful for two narrower reasons:

- it gives the model one obvious "package manager" surface if that improves prompting
- it centralizes command-shape documentation without tying OpenCray to `bash`

That wrapper should remain:

- structured
- host-native
- free of shell parsing

It should not pretend to be `bash`, `npx`, or Node.

## Native tool contracts

### 1. `SkillsFind`

Purpose:

- search remote skills by query
- return installable skill references and detail URLs

Minimum inputs:

- `query`
- `limit`

Minimum outputs:

- result list with:
  - `source`
  - `skill`
  - `title`
  - `description`
  - `detailUrl`
  - `installRef`

Implementation note:

- this should call the same remote search service currently used by the upstream skills ecosystem, but the HTTP call must be owned by OpenCray

### 2. `SkillsAdd`

Purpose:

- install one skill from a supported source reference

Minimum inputs:

- `source_ref`
- optional `skill`
- optional `yes`
- optional `global`

Minimum outputs:

- installed skill id
- target directory
- install manifest entry
- validation outcome

Supported first-party source forms should include:

- `owner/repo`
- `owner/repo@skill-name`
- `owner/repo --skill skill-name`
- direct GitHub URL if later needed

### 3. `SkillsList`

Purpose:

- enumerate installed skills and their installation metadata

Minimum outputs:

- installed skill ids
- enabled state
- source reference
- installed version or resolved revision
- last checked time
- update availability if known

### 4. `SkillsRemove`

Purpose:

- uninstall one installed skill

Minimum inputs:

- `skill_id`

Minimum outputs:

- removed skill id
- removed paths
- manifest update result

### 5. `SkillsCheck`

Purpose:

- compare installed manifest entries against their upstream sources

Minimum inputs:

- optional `skill_id`
- optional `all`

Minimum outputs:

- per-skill status:
  - up to date
  - update available
  - source unavailable
  - unsupported source

### 6. `SkillsUpdate`

Purpose:

- update one or more installed skills in place using recorded provenance

Minimum inputs:

- optional `skill_id`
- optional `all`
- optional `yes`

Minimum outputs:

- updated skill ids
- skipped skill ids
- failed skill ids
- manifest changes

### 7. `SkillsInit`

Purpose:

- create a new local skill scaffold inside the workspace

Minimum inputs:

- `name`
- optional `path`

Minimum outputs:

- created paths
- scaffold summary

Implementation note:

- this should reuse or extend the existing built-in `skill-creator` assets where practical

## Required host-side model

The current implementation does not keep enough provenance to support real `check` and `update`.

OpenCray should add a persisted installation manifest, for example:

- `filesDir/skills-manifest.json`

### Proposed manifest entry

Each installed skill should record at least:

- `skillId`
- `installRoot`
- `sourceType`
- `sourceRef`
- `sourceUrl`
- `selectedSkillName`
- `resolvedRevision`
- `resolvedCommitSha`
- `sourceSubdirectory`
- `installedAtEpochMs`
- `updatedAtEpochMs`
- `lastCheckedAtEpochMs`
- `contentHash`
- `installStrategy`
- `enabled`

### Why this manifest is required

Without this data, OpenCray cannot reliably answer:

- where a skill came from
- whether an update is available
- which upstream revision is installed
- whether a remove or reinstall operation is safe

This is the main difference between "copy files into a directory" and "own a package manager."

## Source resolution strategy

OpenCray should treat source resolution as a dedicated component, not as incidental logic inside the UI facade.

### New host/runtime components

- `SkillSourceResolver`
- `SkillSourceFetcher`
- `SkillInstallManifestStore`
- `SkillPackageManager`

### Responsibilities

`SkillSourceResolver`

- parse user-facing install refs
- normalize source references
- identify source type
- resolve selected skill name when one repo contains multiple skills

`SkillSourceFetcher`

- fetch remote metadata and contents
- clone or download source material into a staging area
- return a staged local directory

`SkillInstallManifestStore`

- persist and read installation provenance
- support list, check, update, and remove operations

`SkillPackageManager`

- coordinate resolve, fetch, validate, install, remove, check, and update flows

## Installation pipeline

The install path should be explicit and testable.

### Proposed `SkillsAdd` pipeline

1. Parse and normalize the source reference.
2. Resolve the target skill identity.
3. Fetch the source into a temporary staging directory.
4. Discover candidate `SKILL.md` files.
5. Select the intended skill directory.
6. Validate via `SkillLoader` and `SkillValidator`.
7. Copy the validated directory into the managed skills root.
8. Write or update the install manifest entry.
9. Refresh the app-visible skills snapshot.

### Important boundary decision

Validation should happen before copying into the managed root.

Invalid or ambiguous remote contents should never be allowed to become "installed but broken."

## Search implementation

`SkillsFind` should be independent from install.

That separation matters because:

- search is read-only
- search does not require installation provenance
- search can often be shipped first
- search failures should not block local skill management

## Check and update semantics

`SkillsCheck` and `SkillsUpdate` should not be treated as optional polish.

They are the operations that force OpenCray to become a real package manager rather than a downloader.

### `SkillsCheck`

The system should:

- load manifest entries
- resolve each source
- compare installed revision or content hash with upstream
- report a structured status

### `SkillsUpdate`

The system should:

- reuse manifest provenance
- fetch the latest supported source state
- validate staged content again
- replace the installed directory atomically
- update manifest timestamps and revision fields

### Atomicity requirement

Install and update operations should stage into a temporary directory and only replace the live installed directory after validation succeeds.

This avoids partially-updated skills becoming visible to runtime or UI.

## Remove semantics

`SkillsRemove` should:

- verify the target is inside the managed skills root
- remove the installed directory
- remove or mark the manifest entry
- refresh app and runtime-visible snapshots

Remove must not rely on guessing the source after the fact. It should use manifest state as the source of truth.

## Init semantics

`SkillsInit` is the easiest command to make native because it is local-only.

The preferred implementation is:

- create a local skill directory in the workspace
- seed `SKILL.md`
- optionally reuse templates or scripts from `app/src/main/assets/builtin-skills/skill-creator`

This path should not require the package manager to already support remote install.

## App integration changes

The current `SkillsFacade` is a UI-oriented storage facade, not a package manager.

That should remain true.

### Keep `SkillsFacade` focused on

- load current snapshot
- toggle enabled state
- delete installed skill
- read instructions
- expose enabled roots

### Move package-manager behavior out of `SkillsFacade`

Do not keep growing `SkillsFacade` into:

- remote search
- git fetch logic
- update policy
- provenance state owner

Instead, package-manager behavior should live in a dedicated component and be called by the host runtime and UI surfaces.

## Runtime tool integration

These new tools belong in the runtime tool dispatcher, not in `bash`.

This gives OpenCray:

- first-class policy metadata
- predictable prompt descriptions
- structured results
- better testability
- clearer user-visible approval semantics

Because the managed skills and catalog directories live outside the workspace root, runtime integration also needs a first-class notion of approved host-managed roots. Generic workspace tools should not automatically inherit access to those roots. Only the dedicated skills package-manager tools should receive that policy scope.

The tools should be classified as follows:

- `SkillsFind`: read-only network action
- `SkillsList`: read-only local action
- `SkillsAdd`: mutating install action
- `SkillsRemove`: mutating uninstall action
- `SkillsCheck`: read-only network action
- `SkillsUpdate`: mutating network + filesystem action
- `SkillsInit`: mutating local filesystem action

## Policy implications

The new tools should participate in the existing policy pipeline.

Recommended initial policy shape:

- `SkillsFind`: allowed wherever web search is allowed
- `SkillsList`: read-only
- `SkillsCheck`: ask or follow web policy if network access is restricted
- `SkillsAdd`: approval-gated mutating tool
- `SkillsUpdate`: approval-gated mutating tool
- `SkillsRemove`: approval-gated mutating tool
- `SkillsInit`: approval-gated mutating tool

For the local Android host, policy also needs to distinguish between:

- workspace roots
- approved external read roots
- approved host-managed package roots

That distinction is required so `SkillsAdd` and `SkillsRemove` can mutate the app-managed skills directory through the same approval system without accidentally broadening generic workspace tool access.

If the package manager later supports direct remote archive or git fetch, those side effects should still remain visible as one structured tool action, not as shell text hidden inside `bash`.

## Built-in skill updates

After native tools exist, built-in skill guidance must stop teaching `npx skills ...`.

### Required follow-up

Update `find-skills` so it teaches:

- use `SkillsFind` for discovery
- use `SkillsAdd` for installation
- use `SkillsCheck` for update checks
- use `SkillsUpdate` for updates
- use `SkillsInit` for scaffolding a new local skill

This follow-up is required for product coherence. Otherwise the model will continue preferring a host command path that OpenCray intentionally does not want to support.

## Rollout plan

The package-manager work should ship in phases.

### Phase 1: Local package-manager foundation

- add install manifest store
- extract `SkillPackageManager`
- keep existing local catalog install working through the new manager
- add local-only `SkillsFind`
- add `SkillsList`
- add `SkillsAdd`
- add `SkillsRemove`
- route these tools through the shared runtime tool pipeline
- extend policy to allow approved host-managed roots outside workspace

Exit condition:

- all current local install/remove flows route through the package-manager abstraction
- local package-manager tools no longer bypass the runtime tool pipeline

### Phase 2: Remote discovery and add

- add `SkillsFind`
- add remote source resolution
- add staged remote install for `SkillsAdd`
- keep validation before activation

Exit condition:

- OpenCray can search and install a remote skill without shelling out to Node/npm

### Phase 3: Provenance-driven maintenance

- add `SkillsCheck`
- add `SkillsUpdate`
- add revision and hash comparison rules
- add staged atomic update flow

Exit condition:

- OpenCray can answer whether installed skills are outdated and can update them safely

### Phase 4: Built-in guidance migration

- update built-in `find-skills`
- update any prompt text or operator guidance that still refers to `npx skills`
- remove any leftover product wording that implies shell-based package management is required

Exit condition:

- runtime guidance and runtime capability fully match

## Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillPackageManager.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillInstallManifest.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillSourceResolver.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillSourceFetcher.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/facade/skills/SkillsFacade.kt`
- `app/src/main/assets/builtin-skills/find-skills/SKILL.md`

## Testing strategy

This work should be test-first and mostly JVM-first.

### Required coverage

- source-ref parsing tests
- remote search result mapping tests
- staged install validation tests
- manifest persistence tests
- update availability detection tests
- atomic update rollback tests
- policy gating tests for mutating package-manager actions
- host/runtime integration tests for installed skill visibility after add/update/remove

### Suggested test files

- `runtime/src/test/kotlin/com/opencray/runtime/skills/SkillSourceResolverTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/skills/SkillPackageManagerTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/skills/SkillInstallManifestStoreTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/skills/SkillPackageManagerUpdateTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/AgentToolPolicyGateTest.kt`
- `app/src/test/kotlin/com/opencray/app/OpenCrayHostRuntimeTest.kt`

## Main risks

### Risk 1: Package-manager logic leaks into UI facade code

If remote source logic, provenance state, and update semantics are left inside `SkillsFacade`, the result will be hard to test and harder to reuse from runtime tools.

Mitigation:

- keep `SkillsFacade` UI-oriented
- move package-manager behavior into a dedicated manager

### Risk 2: Search and install are coupled too early

If search results are treated as the only install path, direct install by source ref becomes brittle.

Mitigation:

- keep `SkillsFind` and `SkillsAdd` separate
- let install resolve refs independently

### Risk 3: Updates are built without provenance discipline

If `check` and `update` are attempted before manifest fields are defined, behavior will quickly become unreliable.

Mitigation:

- define the manifest first
- route install through the manifest before adding update

### Risk 4: Package-manager behavior outruns prompt guidance

If OpenCray ships native tools but the built-in skill guidance still teaches `npx skills`, the model will continue choosing the wrong surface.

Mitigation:

- treat built-in guidance update as a required rollout phase, not optional cleanup

## Acceptance criteria

This package should be considered complete when all of the following are true:

1. OpenCray exposes native tools for search, add, list, remove, check, update, and init.
2. No shell or Node/npm dependency is required to use those flows on Android.
3. Installed skills have persisted provenance sufficient for real check and update behavior.
4. Remote installs are staged and validated before activation.
5. Built-in skill guidance points to native package-manager tools instead of `npx skills`.

## Final recommendation

Do not spend engineering time on shell-level `npx skills` compatibility unless a very narrow migration case appears later.

The stronger path is:

1. own the package-manager behavior natively
2. record provenance correctly
3. expose typed runtime tools
4. update built-in guidance to match the native surface

That gives OpenCray a real Android-native skill package manager instead of a brittle shell emulation layer.
