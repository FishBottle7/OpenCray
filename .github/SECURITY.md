# Security Policy

## Reporting a Vulnerability

OpenCray is a mobile AI agent runtime. Because it can execute commands, manage workspace files, and hold API credentials, security concerns are taken seriously.

Please **do not** open a public GitHub issue for security vulnerabilities. Instead, report them privately by emailing the maintainer:

**Maintainer:** FishBottle
**Email:** (via GitHub — you can reach the maintainer through the GitHub account `FishBottle7`)

When reporting, please include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce it.
- The affected module / version, if known.

## Supported Versions

| Version | Supported |
| --- | --- |
| `master` (latest) | ✅ |

## Scope

Security-sensitive areas in this project include, but are not limited to:

- The `ToolPolicyPipeline` in `runtime/src/main/kotlin/com/opencray/runtime/policy/`
- Workspace / filesystem boundary checks and SAF grants
- Command and process execution
- Credential / API-key handling
- Path-protection and path-escape safeguards

Reports outside these areas are still welcome and will be triaged.

## Disclosure Policy

We aim to acknowledge reports within a reasonable timeframe, triage them, and coordinate a fix before public disclosure when the issue warrants it.