#!/usr/bin/env python3
"""
Quick validation script for skills.
"""

import re
import sys
from pathlib import Path

import yaml

MAX_SKILL_NAME_LENGTH = 64


def validate_skill(skill_path):
    skill_path = Path(skill_path)
    skill_md = skill_path / "SKILL.md"
    if not skill_md.exists():
        return False, "SKILL.md not found"

    content = skill_md.read_text()
    if not content.startswith("---"):
        return False, "No YAML frontmatter found"

    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        return False, "Invalid frontmatter format"

    frontmatter = yaml.safe_load(match.group(1))
    if not isinstance(frontmatter, dict):
        return False, "Frontmatter must be a YAML dictionary"

    name = str(frontmatter.get("name", "")).strip()
    if not name:
        return False, "Missing 'name' in frontmatter"
    if not re.match(r"^[a-z0-9-]+$", name):
        return False, "Name should be hyphen-case"
    if len(name) > MAX_SKILL_NAME_LENGTH:
        return False, "Name is too long"

    description = str(frontmatter.get("description", "")).strip()
    if not description:
        return False, "Missing 'description' in frontmatter"

    return True, "Skill is valid!"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python quick_validate.py <skill_directory>")
        sys.exit(1)

    valid, message = validate_skill(sys.argv[1])
    print(message)
    sys.exit(0 if valid else 1)
