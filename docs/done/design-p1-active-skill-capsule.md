# Design: P1 Active Skill Capsule

Date: 2026-03-16

## Goal

Implement the next strict Level 2 skill step from `docs/context-management-design.md`:

- keep `Skill Inventory` as the discovery layer
- promote one selected skill into a dedicated `Active Skill` capsule
- keep `ContextManager` as allocator/renderer only
- avoid jumping straight to `skill_execute` or full fork runtime support

## Implemented shape

The active skill path is now progressive disclosure driven by explicit runtime behavior:

1. the model sees the bounded `Skill Inventory`
2. the model calls `skill_read` for one concrete skill
3. runtime records that activation for the current run
4. later turns inject a dedicated `Active Skill` layer built from that skill's `SKILL.md`
5. if the skill exposes a simple allowlist-style tool policy, the runtime narrows the visible tool list and rejects later tool calls outside that allowlist

## Boundary decisions

- Skill selection is not done by `ContextManager`
- `ContextManager` only renders the already-selected capsule into prompt space and reports trace data
- turn-aware activation lives in `OpenCrayAgentRuntime`
- catalog loading lives in `AppAgentSessionTaskRuntimeFactory`
- host/local snapshot projection stays in `OpenCrayHostRuntime`

## What shipped

- skill catalog resolution alongside existing inventory resolution
- `ActiveSkillCapsule`, prompt layer, and trace model under `runtime/skills`
- turn-local activation after successful `skill_read`
- dedicated `[Active Skill]` prompt layer on later turns
- active skill metadata in runtime result metadata
- host/local run snapshot projection for `activeSkill`
- allowlist-style tool narrowing for active skills that declare simple allowed tools
- focused runtime/app unit tests for activation, prompt injection, policy blocking, and snapshot projection

## Intentionally not included

- automatic implicit skill selection
- `skill_execute`
- `context: fork` execution
- subagent spawning from skill metadata
- full pattern-based tool permission engine beyond the current simple allowlist-style narrowing

## Next recommended step

Move to the OpenClaw-aligned memory pressure path:

1. pre-compaction memory flush
2. durable compaction summaries
3. bootstrap context files
