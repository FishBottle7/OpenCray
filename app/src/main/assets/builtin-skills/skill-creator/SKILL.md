---
name: skill-creator
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Codex's capabilities with specialized knowledge, workflows, or tool integrations.
metadata:
  short-description: Create or update a skill
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained folders that extend Codex's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks. They transform Codex from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Codex needs: system prompt, conversation history, other skills' metadata, and the actual user request.

**Default assumption: Codex is already very smart.** Only add context Codex does not already have. Challenge each piece of information: "Does Codex really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid, decisions depend on context, or heuristics guide the approach.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists, some variation is acceptable, or configuration affects behavior.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile and error-prone, consistency is critical, or a specific sequence must be followed.

Think of Codex as exploring a path: a narrow bridge with cliffs needs specific guardrails, while an open field allows many routes.

### Anatomy of a Skill

Every skill consists of a required `SKILL.md` file and optional bundled resources:

```text
skill-name/
├── SKILL.md
├── agents/
│   └── openai.yaml
├── scripts/
├── references/
└── assets/
```

#### SKILL.md

Every `SKILL.md` contains:

- **Frontmatter**: `name` and `description`
- **Body**: Instructions and guidance for using the skill

#### Agents metadata

- UI-facing metadata for skill lists and chips
- Read `references/openai_yaml.md` before generating values and follow its constraints
- Generate `agents/openai.yaml` by passing values as `--interface key=value` to `scripts/generate_openai_yaml.py` or `scripts/init_skill.py`
- Validate `agents/openai.yaml` still matches `SKILL.md` after updates

#### Bundled resources

- `scripts/` for deterministic helper code
- `references/` for detailed documentation loaded as needed
- `assets/` for templates, icons, fonts, and boilerplate not meant to sit in context

#### What to Not Include in a Skill

Do not add extra documentation like `README.md`, installation guides, quick references, or changelogs unless they directly support the skill runtime behavior.

## Progressive Disclosure

Skills use a three-level loading model:

1. Metadata in frontmatter
2. `SKILL.md` body when the skill triggers
3. Bundled resources loaded only when needed

Keep `SKILL.md` lean and move long, optional details into `references/`.

## Skill Creation Process

Follow these steps in order unless there is a clear reason not to:

1. Understand the skill with concrete examples
2. Plan reusable contents such as scripts, references, and assets
3. Initialize the skill
4. Edit the skill
5. Validate the skill
6. Iterate based on real usage

### Skill Naming

- Use lowercase letters, digits, and hyphens only
- Keep names under 64 characters
- Prefer short, action-oriented names
- Name the skill folder exactly after the skill name

### Step 1: Understand the Skill

Collect a few concrete examples of how the skill should be used. Focus on:

- what the user will ask for
- what should trigger the skill
- what output or automation the skill should enable

### Step 2: Plan Reusable Contents

For each example, decide whether the repeated value belongs in:

- `scripts/`
- `references/`
- `assets/`

### Step 3: Initialize the Skill

When creating a new skill from scratch, run:

```bash
scripts/init_skill.py <skill-name> --path <output-directory> [--resources scripts,references,assets] [--examples]
```

The initializer creates:

- a new skill directory
- a `SKILL.md` template
- `agents/openai.yaml`
- optional resource directories

Generate the UI metadata by reading the skill and passing `--interface key=value` values to the initializer or to:

```bash
scripts/generate_openai_yaml.py <path/to/skill-folder> --interface key=value
```

For field definitions and examples, see `references/openai_yaml.md`.

### Step 4: Edit the Skill

Start with the reusable resources, then update `SKILL.md`.

For frontmatter:

- keep only `name` and `description`
- make the description explain both what the skill does and when it should be used

For the body:

- include only non-obvious guidance another Codex instance really needs

### Step 5: Validate the Skill

Run:

```bash
scripts/quick_validate.py <path/to/skill-folder>
```

Fix any reported issues before considering the skill done.

### Step 6: Iterate

After real use, refine the skill by:

1. noticing where the agent struggled
2. identifying what to change in `SKILL.md` or resources
3. updating the skill
4. testing again
