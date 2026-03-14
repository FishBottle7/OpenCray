# openai.yaml fields (full example + descriptions)

`agents/openai.yaml` is an extended, product-specific config intended for the machine or harness to read, not the agent.

## Full example

```yaml
interface:
  display_name: "Optional user-facing name"
  short_description: "Optional user-facing description"
  icon_small: "./assets/small-400px.png"
  icon_large: "./assets/large-logo.svg"
  brand_color: "#3B82F6"
  default_prompt: "Optional surrounding prompt to use the skill with"

dependencies:
  tools:
    - type: "mcp"
      value: "github"
      description: "GitHub MCP server"
      transport: "streamable_http"
      url: "https://api.githubcopilot.com/mcp/"

policy:
  allow_implicit_invocation: true
```

## Field descriptions and constraints

- Quote all string values.
- Keep keys unquoted.
- For `interface.default_prompt`, generate a short example prompt and explicitly mention the skill as `$skill-name`.
- `interface.display_name`: Human-facing title shown in UI lists and chips.
- `interface.short_description`: Human-facing short UI blurb for quick scanning.
- `interface.icon_small`: Relative path to a small icon asset.
- `interface.icon_large`: Relative path to a larger logo asset.
- `interface.brand_color`: Hex color used for UI accents.
- `interface.default_prompt`: Default prompt snippet inserted when invoking the skill.
- `dependencies.tools[].type`: Only `mcp` is supported for now.
- `dependencies.tools[].value`: Dependency identifier.
- `dependencies.tools[].description`: Human-readable explanation of the dependency.
- `dependencies.tools[].transport`: Connection type for an MCP dependency.
- `dependencies.tools[].url`: MCP server URL.
- `policy.allow_implicit_invocation`: When false, the skill is not injected by default but can still be invoked explicitly via `$skill`.
