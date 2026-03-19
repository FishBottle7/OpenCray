---
name: find-skills
description: Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill.
---

# Find Skills

This skill helps you discover and install skills from the open agent skills ecosystem.

## When to Use This Skill

Use this skill when the user:

- Asks "how do I do X" where X might be a common task with an existing skill
- Says "find a skill for X" or "is there a skill for X"
- Asks "can you do X" where X is a specialized capability
- Expresses interest in extending agent capabilities
- Wants to search for tools, templates, or workflows
- Mentions they wish they had help with a specific domain (design, testing, deployment, etc.)

## Native Skills Tools

OpenCray exposes native skills package-manager tools. Skills are modular packages that extend agent capabilities with specialized knowledge, workflows, and tools.

**Key tools:**

- `SkillsFind` - Search the host-managed local catalog and remote skills index
- `SkillsAdd` - Install a skill from the local catalog, a local path, GitHub, or GitLab
- `SkillsCheck` - Check installed skills for updates using recorded provenance
- `SkillsUpdate` - Update installed skills in place
- `SkillsInit` - Scaffold a new local skill when native init support is available

**Browse skills at:** https://skills.sh/

## How to Help Users Find Skills

### Step 1: Understand What They Need

When a user asks for help with something, identify:

1. The domain (e.g., React, testing, design, deployment)
2. The specific task (e.g., writing tests, creating animations, reviewing PRs)
3. Whether this is a common enough task that a skill likely exists

### Step 2: Search for Skills

Run the native find tool with a relevant query.

For example:

- User asks "how do I make my React app faster?" -> use `SkillsFind` with query `react performance`
- User asks "can you help me with PR reviews?" -> use `SkillsFind` with query `pr review`
- User asks "I need to create a changelog" -> use `SkillsFind` with query `changelog`

The tool returns installable refs and detail URLs, for example:

```
vercel-react-best-practices    remote    install_ref=vercel-labs/agent-skills@vercel-react-best-practices
detail_url=https://skills.sh/vercel-labs/agent-skills/vercel-react-best-practices
```

### Step 3: Present Options to the User

When you find relevant skills, present them to the user with:

1. The skill name and what it does
2. The install ref or the fact that you can install it for them with `SkillsAdd`
3. A link to learn more at skills.sh

Example response:

```
I found a skill that might help! The "vercel-react-best-practices" skill provides
React and Next.js performance optimization guidelines from Vercel Engineering.

I can install it with:
SkillsAdd source_ref="vercel-labs/agent-skills@vercel-react-best-practices"

Learn more: https://skills.sh/vercel-labs/agent-skills/vercel-react-best-practices
```

### Step 4: Offer to Install

If the user wants to proceed, you can install the skill for them:

Use `SkillsAdd` with the chosen `source_ref`.

## Common Skill Categories

When searching, consider these common categories:

| Category        | Example Queries                          |
| --------------- | ---------------------------------------- |
| Web Development | react, nextjs, typescript, css, tailwind |
| Testing         | testing, jest, playwright, e2e           |
| DevOps          | deploy, docker, kubernetes, ci-cd        |
| Documentation   | docs, readme, changelog, api-docs        |
| Code Quality    | review, lint, refactor, best-practices   |
| Design          | ui, ux, design-system, accessibility     |
| Productivity    | workflow, automation, git                |

## Tips for Effective Searches

1. **Use specific keywords**: "react testing" is better than just "testing"
2. **Try alternative terms**: If "deploy" doesn't work, try "deployment" or "ci-cd"
3. **Check popular sources**: Many skills come from `vercel-labs/agent-skills` or `ComposioHQ/awesome-claude-skills`

## When No Skills Are Found

If no relevant skills exist:

1. Acknowledge that no existing skill was found
2. Offer to help with the task directly using your general capabilities
3. Suggest the user could create their own skill with `SkillsInit` once native scaffolding is available

Example:

```
I searched for skills related to "xyz" but didn't find any matches.
I can still help you with this task directly! Would you like me to proceed?

If this is something you do often, creating a dedicated skill may make sense once
the native `SkillsInit` scaffold flow is available.
```
