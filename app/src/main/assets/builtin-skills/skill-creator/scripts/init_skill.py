#!/usr/bin/env python3
"""
Skill Initializer - Creates a new skill from template.
"""

import argparse
import re
import sys
from pathlib import Path

from generate_openai_yaml import write_openai_yaml

MAX_SKILL_NAME_LENGTH = 64
ALLOWED_RESOURCES = {"scripts", "references", "assets"}

SKILL_TEMPLATE = """---
name: {skill_name}
description: [TODO: Complete and informative explanation of what the skill does and when to use it.]
---

# {skill_title}

## Overview

[TODO: Explain what this skill enables]
"""


def normalize_skill_name(skill_name):
    normalized = skill_name.strip().lower()
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized)
    normalized = normalized.strip("-")
    normalized = re.sub(r"-{2,}", "-", normalized)
    return normalized


def title_case_skill_name(skill_name):
    return " ".join(word.capitalize() for word in skill_name.split("-"))


def parse_resources(raw_resources):
    if not raw_resources:
        return []
    resources = [item.strip() for item in raw_resources.split(",") if item.strip()]
    invalid = sorted({item for item in resources if item not in ALLOWED_RESOURCES})
    if invalid:
        print(f"[ERROR] Unknown resource type(s): {', '.join(invalid)}")
        sys.exit(1)
    deduped = []
    seen = set()
    for resource in resources:
        if resource not in seen:
            deduped.append(resource)
            seen.add(resource)
    return deduped


def create_resource_dirs(skill_dir, resources):
    for resource in resources:
        resource_dir = skill_dir / resource
        resource_dir.mkdir(exist_ok=True)
        print(f"[OK] Created {resource}/")


def init_skill(skill_name, path, resources, interface_overrides):
    skill_dir = Path(path).resolve() / skill_name
    if skill_dir.exists():
        print(f"[ERROR] Skill directory already exists: {skill_dir}")
        return None

    skill_dir.mkdir(parents=True, exist_ok=False)
    print(f"[OK] Created skill directory: {skill_dir}")

    skill_title = title_case_skill_name(skill_name)
    skill_md_path = skill_dir / "SKILL.md"
    skill_md_path.write_text(
        SKILL_TEMPLATE.format(skill_name=skill_name, skill_title=skill_title)
    )
    print("[OK] Created SKILL.md")

    result = write_openai_yaml(skill_dir, skill_name, interface_overrides)
    if not result:
        return None

    create_resource_dirs(skill_dir, resources)
    print(f"[OK] Skill '{skill_name}' initialized successfully at {skill_dir}")
    return skill_dir


def main():
    parser = argparse.ArgumentParser(description="Create a new skill directory with a template.")
    parser.add_argument("skill_name", help="Skill name")
    parser.add_argument("--path", required=True, help="Output directory for the skill")
    parser.add_argument("--resources", default="", help="Comma-separated list: scripts,references,assets")
    parser.add_argument("--interface", action="append", default=[], help="Interface override key=value")
    args = parser.parse_args()

    raw_skill_name = args.skill_name
    skill_name = normalize_skill_name(raw_skill_name)
    if not skill_name:
        print("[ERROR] Skill name must include at least one letter or digit.")
        sys.exit(1)
    if len(skill_name) > MAX_SKILL_NAME_LENGTH:
        print(f"[ERROR] Skill name '{skill_name}' is too long.")
        sys.exit(1)

    resources = parse_resources(args.resources)
    result = init_skill(skill_name, args.path, resources, args.interface)
    sys.exit(0 if result else 1)


if __name__ == "__main__":
    main()
