#!/usr/bin/env python3
"""
OpenAI YAML Generator - Creates agents/openai.yaml for a skill folder.
"""

import argparse
import re
import sys
from pathlib import Path

ACRONYMS = {"GH", "MCP", "API", "CI", "CLI", "LLM", "PDF", "PR", "UI", "URL", "SQL"}
BRANDS = {
    "openai": "OpenAI",
    "openapi": "OpenAPI",
    "github": "GitHub",
    "pagerduty": "PagerDuty",
    "datadog": "DataDog",
    "sqlite": "SQLite",
    "fastapi": "FastAPI",
}
SMALL_WORDS = {"and", "or", "to", "up", "with"}
ALLOWED_INTERFACE_KEYS = {
    "display_name",
    "short_description",
    "icon_small",
    "icon_large",
    "brand_color",
    "default_prompt",
}


def yaml_quote(value):
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return f'"{escaped}"'


def format_display_name(skill_name):
    words = [word for word in skill_name.split("-") if word]
    formatted = []
    for index, word in enumerate(words):
        lower = word.lower()
        upper = word.upper()
        if upper in ACRONYMS:
            formatted.append(upper)
            continue
        if lower in BRANDS:
            formatted.append(BRANDS[lower])
            continue
        if index > 0 and lower in SMALL_WORDS:
            formatted.append(lower)
            continue
        formatted.append(word.capitalize())
    return " ".join(formatted)


def generate_short_description(display_name):
    description = f"Help with {display_name} tasks"
    if len(description) < 25:
        description = f"Help with {display_name} tasks and workflows"
    if len(description) > 64:
        description = f"{display_name} helper"
    return description[:64].rstrip()


def read_frontmatter_name(skill_dir):
    skill_md = Path(skill_dir) / "SKILL.md"
    if not skill_md.exists():
        print(f"[ERROR] SKILL.md not found in {skill_dir}")
        return None
    content = skill_md.read_text()
    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        print("[ERROR] Invalid SKILL.md frontmatter format.")
        return None

    import yaml

    frontmatter = yaml.safe_load(match.group(1))
    if not isinstance(frontmatter, dict):
        print("[ERROR] Frontmatter must be a YAML dictionary.")
        return None
    name = frontmatter.get("name", "")
    return name.strip() if isinstance(name, str) else None


def parse_interface_overrides(raw_overrides):
    overrides = {}
    optional_order = []
    for item in raw_overrides:
        if "=" not in item:
            return None, None
        key, value = item.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key not in ALLOWED_INTERFACE_KEYS:
          return None, None
        overrides[key] = value
        if key not in ("display_name", "short_description") and key not in optional_order:
            optional_order.append(key)
    return overrides, optional_order


def write_openai_yaml(skill_dir, skill_name, raw_overrides):
    overrides, optional_order = parse_interface_overrides(raw_overrides)
    if overrides is None:
        return None

    display_name = overrides.get("display_name") or format_display_name(skill_name)
    short_description = overrides.get("short_description") or generate_short_description(display_name)

    interface_lines = [
        "interface:",
        f"  display_name: {yaml_quote(display_name)}",
        f"  short_description: {yaml_quote(short_description)}",
    ]

    for key in optional_order:
        value = overrides.get(key)
        if value is not None:
            interface_lines.append(f"  {key}: {yaml_quote(value)}")

    agents_dir = Path(skill_dir) / "agents"
    agents_dir.mkdir(parents=True, exist_ok=True)
    output_path = agents_dir / "openai.yaml"
    output_path.write_text("\n".join(interface_lines) + "\n")
    print("[OK] Created agents/openai.yaml")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Create agents/openai.yaml for a skill directory.")
    parser.add_argument("skill_dir", help="Path to the skill directory")
    parser.add_argument("--name", help="Skill name override")
    parser.add_argument("--interface", action="append", default=[], help="Interface override key=value")
    args = parser.parse_args()

    skill_dir = Path(args.skill_dir).resolve()
    skill_name = args.name or read_frontmatter_name(skill_dir)
    if not skill_name:
        sys.exit(1)
    sys.exit(0 if write_openai_yaml(skill_dir, skill_name, args.interface) else 1)


if __name__ == "__main__":
    main()
