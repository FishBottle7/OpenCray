from __future__ import annotations

import argparse
import asyncio
import hashlib
import inspect
import json
import sys
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


DEFAULT_SERVICE_ROOT = Path(".opencray") / "personality_service"
DEFAULT_GRAPH_DIR = "graphs"
DEFAULT_BINDING_DIR = "bindings"
DEFAULT_EXPORT_DIR = "exports"
DEFAULT_CACHE_DIR = "cache"
DEFAULT_SELECTED_RELATIONSHIP_DIR = "selected_relationships"
DEFAULT_IMPORT_SESSION_DIR = "import_sessions"


@dataclass
class TwinBinding:
    twin_id: str
    graph_group_id: str
    anchor_person_id: str
    interaction_mode: str
    source_mode: str
    current_user_role_binding: dict[str, Any]
    selected_relationship_binding_id: str | None = None
    import_session_id: str | None = None
    focal_node_uuid: str | None = None
    created_at: str | None = None
    updated_at: str | None = None


@dataclass
class SelectedRelationshipBinding:
    binding_id: str
    twin_id: str
    anchor_person_id: str
    counterpart_entity_id: str
    counterpart_binding_type: str
    relationship_label: str
    overlay_key: str
    selection_source: str
    status: str
    selection_version: int
    created_at: str | None = None
    updated_at: str | None = None


@dataclass
class ImportSession:
    session_id: str
    twin_id: str
    anchor_person_id: str
    state: str
    source_mode: str
    source_refs: list[str] = field(default_factory=list)
    source_hash: str | None = None
    current_user_role_binding: dict[str, Any] = field(default_factory=dict)
    selected_relationship_binding_id: str | None = None
    artifact_refs: dict[str, Any] = field(default_factory=dict)
    created_at: str | None = None
    updated_at: str | None = None


class ServiceError(RuntimeError):
    pass


def _now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _service_root(path: str | None) -> Path:
    return Path(path) if path else DEFAULT_SERVICE_ROOT


def _graph_path(service_root: Path, twin_id: str) -> Path:
    return service_root / DEFAULT_GRAPH_DIR / f"{twin_id}.kuzu"


def _relationship_graph_manifest_path(service_root: Path, twin_id: str) -> Path:
    return service_root / DEFAULT_GRAPH_DIR / f"{twin_id}.relationship_graph_manifest.json"


def _binding_path(service_root: Path, twin_id: str) -> Path:
    return service_root / DEFAULT_BINDING_DIR / f"{twin_id}.binding.json"


def _selected_relationship_dir(service_root: Path, twin_id: str) -> Path:
    return service_root / DEFAULT_BINDING_DIR / DEFAULT_SELECTED_RELATIONSHIP_DIR / twin_id


def _selected_relationship_path(service_root: Path, twin_id: str, binding_id: str) -> Path:
    return _selected_relationship_dir(service_root, twin_id) / f"{binding_id}.json"


def _import_session_path(service_root: Path, session_id: str) -> Path:
    return service_root / DEFAULT_BINDING_DIR / DEFAULT_IMPORT_SESSION_DIR / f"{session_id}.json"


def _export_path(service_root: Path, twin_id: str, export_name: str) -> Path:
    return service_root / DEFAULT_EXPORT_DIR / twin_id / export_name


def _ensure_service_dirs(service_root: Path) -> None:
    (service_root / DEFAULT_GRAPH_DIR).mkdir(parents=True, exist_ok=True)
    (service_root / DEFAULT_BINDING_DIR).mkdir(parents=True, exist_ok=True)
    (service_root / DEFAULT_BINDING_DIR / DEFAULT_SELECTED_RELATIONSHIP_DIR).mkdir(parents=True, exist_ok=True)
    (service_root / DEFAULT_BINDING_DIR / DEFAULT_IMPORT_SESSION_DIR).mkdir(parents=True, exist_ok=True)
    (service_root / DEFAULT_EXPORT_DIR).mkdir(parents=True, exist_ok=True)
    (service_root / DEFAULT_CACHE_DIR).mkdir(parents=True, exist_ok=True)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ServiceError(f"JSON file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ServiceError(f"Invalid JSON in {path}: {exc}") from exc


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _print_json(payload: Any) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def _require_string(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ServiceError(f"Expected non-empty string field '{key}'.")
    return value.strip()


def _require_list(payload: dict[str, Any], key: str) -> list[Any]:
    value = payload.get(key)
    if not isinstance(value, list):
        raise ServiceError(f"Expected list field '{key}'.")
    return value


def _require_string_list(payload: dict[str, Any], key: str) -> list[str]:
    values = _require_list(payload, key)
    result: list[str] = []
    for value in values:
        if not isinstance(value, str) or not value.strip():
            raise ServiceError(f"Expected list field '{key}' to contain only non-empty strings.")
        result.append(value.strip())
    return result


def _role_binding(binding_type: str, entity_id: str) -> dict[str, Any]:
    if not entity_id.strip():
        raise ServiceError("current_user_role_binding.entity_id must be a non-empty string.")
    return {
        "type": binding_type,
        "entity_id": entity_id.strip(),
    }


def _role_binding_from_payload(payload: dict[str, Any]) -> dict[str, Any]:
    inline = payload.get("current_user_role_binding")
    if isinstance(inline, dict):
        return _role_binding(
            _require_string(inline, "type"),
            _require_string(inline, "entity_id"),
        )
    binding_entity_id = _require_string(payload, "binding_entity_id")
    binding_type = str(payload.get("binding_type") or "real_user").strip()
    return _role_binding(binding_type, binding_entity_id)


def _binding_entity_id(binding: TwinBinding) -> str:
    entity_id = binding.current_user_role_binding.get("entity_id")
    if not isinstance(entity_id, str) or not entity_id.strip():
        raise ServiceError("Twin binding is missing current_user_role_binding.entity_id.")
    return entity_id.strip()


def _source_refs_from_payload(payload: dict[str, Any]) -> list[str]:
    source_refs = payload.get("source_refs")
    if source_refs is not None:
        return _normalize_source_refs(source_refs)
    source = payload.get("source")
    if isinstance(source, str) and source.strip():
        return [source.strip()]
    return []


def _load_binding(service_root: Path, twin_id: str) -> TwinBinding:
    payload = _load_json(_binding_path(service_root, twin_id))
    if not isinstance(payload, dict):
        raise ServiceError("Binding JSON must be an object.")
    return TwinBinding(
        twin_id=_require_string(payload, "twin_id"),
        graph_group_id=_require_string(payload, "graph_group_id"),
        anchor_person_id=_require_string(payload, "anchor_person_id"),
        interaction_mode=_require_string(payload, "interaction_mode"),
        source_mode=_require_string(payload, "source_mode"),
        current_user_role_binding=payload.get("current_user_role_binding") or {},
        selected_relationship_binding_id=payload.get("selected_relationship_binding_id") or payload.get("active_selected_relationship_binding_id"),
        import_session_id=payload.get("import_session_id"),
        focal_node_uuid=payload.get("focal_node_uuid"),
        created_at=payload.get("created_at"),
        updated_at=payload.get("updated_at"),
    )


def _save_binding(service_root: Path, binding: TwinBinding) -> Path:
    binding.updated_at = _now_iso()
    if not binding.created_at:
        binding.created_at = binding.updated_at
    path = _binding_path(service_root, binding.twin_id)
    _write_json(path, asdict(binding))
    return path


def _load_selected_relationship_binding(
    service_root: Path,
    twin_id: str,
    binding_id: str,
) -> SelectedRelationshipBinding:
    payload = _load_json(_selected_relationship_path(service_root, twin_id, binding_id))
    if not isinstance(payload, dict):
        raise ServiceError("Selected relationship binding JSON must be an object.")
    return SelectedRelationshipBinding(
        binding_id=_require_string(payload, "binding_id"),
        twin_id=_require_string(payload, "twin_id"),
        anchor_person_id=_require_string(payload, "anchor_person_id"),
        counterpart_entity_id=_require_string(payload, "counterpart_entity_id"),
        counterpart_binding_type=_require_string(payload, "counterpart_binding_type"),
        relationship_label=_require_string(payload, "relationship_label"),
        overlay_key=_require_string(payload, "overlay_key"),
        selection_source=_require_string(payload, "selection_source"),
        status=_require_string(payload, "status"),
        selection_version=int(payload.get("selection_version") or 1),
        created_at=payload.get("created_at"),
        updated_at=payload.get("updated_at"),
    )


def _save_selected_relationship_binding(
    service_root: Path,
    selected_binding: SelectedRelationshipBinding,
) -> Path:
    selected_binding.updated_at = _now_iso()
    if not selected_binding.created_at:
        selected_binding.created_at = selected_binding.updated_at
    path = _selected_relationship_path(
        service_root,
        selected_binding.twin_id,
        selected_binding.binding_id,
    )
    _write_json(path, asdict(selected_binding))
    return path


def _load_import_session(service_root: Path, session_id: str) -> ImportSession:
    payload = _load_json(_import_session_path(service_root, session_id))
    if not isinstance(payload, dict):
        raise ServiceError("Import session JSON must be an object.")
    return ImportSession(
        session_id=_require_string(payload, "session_id"),
        twin_id=_require_string(payload, "twin_id"),
        anchor_person_id=_require_string(payload, "anchor_person_id"),
        state=_require_string(payload, "state"),
        source_mode=_require_string(payload, "source_mode"),
        source_refs=[str(item) for item in (payload.get("source_refs") or [])],
        source_hash=payload.get("source_hash"),
        current_user_role_binding=payload.get("current_user_role_binding") or {},
        selected_relationship_binding_id=payload.get("selected_relationship_binding_id"),
        artifact_refs=payload.get("artifact_refs") or {},
        created_at=payload.get("created_at"),
        updated_at=payload.get("updated_at"),
    )


def _save_import_session(service_root: Path, session: ImportSession) -> Path:
    session.updated_at = _now_iso()
    if not session.created_at:
        session.created_at = session.updated_at
    path = _import_session_path(service_root, session.session_id)
    _write_json(path, asdict(session))
    return path


def _normalize_source_refs(source_refs: list[str] | str | None) -> list[str]:
    if source_refs is None:
        return []
    if isinstance(source_refs, str):
        return [source_refs.strip()] if source_refs.strip() else []
    normalized: list[str] = []
    for ref in source_refs:
        if not isinstance(ref, str) or not ref.strip():
            raise ServiceError("source_refs must contain only non-empty strings.")
        normalized.append(ref.strip())
    return normalized


def _hash_source_refs(source_refs: list[str]) -> str | None:
    refs = _normalize_source_refs(source_refs)
    if not refs:
        return None
    digest = hashlib.sha256()
    for ref in refs:
        path = Path(ref)
        digest.update(str(path).encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return f"sha256:{digest.hexdigest()}"


def _trim_excerpt(text: str, limit: int = 120) -> str:
    stripped = " ".join(text.split())
    if len(stripped) <= limit:
        return stripped
    return stripped[: limit - 1].rstrip() + "..."


def _safe_timestamp(raw: Any) -> str | None:
    if not isinstance(raw, str) or not raw.strip():
        return None
    return raw.strip()


def _merge_anchor_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for candidate in candidates:
        entity_id = str(candidate.get("entity_id") or "").strip()
        if not entity_id:
            continue
        current = merged.setdefault(
            entity_id,
            {
                "entity_id": entity_id,
                "display_name": candidate.get("display_name") or entity_id,
                "role_hint": candidate.get("role_hint"),
            },
        )
        if not current.get("role_hint") and candidate.get("role_hint"):
            current["role_hint"] = candidate["role_hint"]
    return sorted(
        merged.values(),
        key=lambda item: (
            0 if item.get("role_hint") == "anchor" else 1,
            str(item.get("display_name") or item["entity_id"]),
        ),
    )


def _merge_counterpart_cards(cards: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for card in cards:
        entity_id = str(card.get("entity_id") or "").strip()
        if not entity_id:
            continue
        current = merged.setdefault(
            entity_id,
            {
                "entity_id": entity_id,
                "display_name": card.get("display_name") or entity_id,
                "actor_kind": card.get("actor_kind") or "unknown",
                "relationship_labels": [],
                "direct_interaction_count": 0,
                "recent_event_tags": [],
                "last_seen_at": None,
                "sample_supporting_lines": [],
                "role_hint": card.get("role_hint"),
                "is_current_user_binding_candidate": bool(card.get("is_current_user_binding_candidate")),
            },
        )
        current["direct_interaction_count"] += int(card.get("direct_interaction_count") or 0)
        current["relationship_labels"] = sorted({*current["relationship_labels"], *[str(item) for item in (card.get("relationship_labels") or [])]})
        current["recent_event_tags"] = sorted({*current["recent_event_tags"], *[str(item) for item in (card.get("recent_event_tags") or [])]})
        if card.get("last_seen_at") and (not current.get("last_seen_at") or str(card["last_seen_at"]) > str(current["last_seen_at"])):
            current["last_seen_at"] = card["last_seen_at"]
        current["is_current_user_binding_candidate"] = current["is_current_user_binding_candidate"] or bool(card.get("is_current_user_binding_candidate"))
        if not current.get("role_hint") and card.get("role_hint"):
            current["role_hint"] = card["role_hint"]
        for line in card.get("sample_supporting_lines") or []:
            text_line = str(line).strip()
            if text_line and text_line not in current["sample_supporting_lines"]:
                current["sample_supporting_lines"].append(text_line)
        current["sample_supporting_lines"] = current["sample_supporting_lines"][:2]
    return sorted(
        merged.values(),
        key=lambda item: (-int(item.get("direct_interaction_count") or 0), str(item.get("display_name") or item["entity_id"])),
    )


def _resolve_chat_speaker_id(turn: dict[str, Any], participants_by_name: dict[str, str]) -> str | None:
    speaker_id = turn.get("speaker_id")
    if isinstance(speaker_id, str) and speaker_id.strip():
        return speaker_id.strip()
    speaker_name = turn.get("speaker")
    if isinstance(speaker_name, str) and speaker_name.strip():
        return participants_by_name.get(speaker_name.strip())
    return None


def _collect_chat_anchor_candidates(payload: dict[str, Any]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for participant in payload.get("participants") or []:
        if not isinstance(participant, dict):
            continue
        entity_id = str(participant.get("entity_id") or "").strip()
        display_name = str(participant.get("display_name") or entity_id).strip()
        if not entity_id or not display_name:
            continue
        candidates.append(
            {
                "entity_id": entity_id,
                "display_name": display_name,
                "role_hint": participant.get("role"),
            }
        )
    if candidates:
        return _merge_anchor_candidates(candidates)
    seen: dict[str, str] = {}
    for turn in payload.get("turns") or []:
        if not isinstance(turn, dict):
            continue
        speaker_id = turn.get("speaker_id")
        speaker_name = turn.get("speaker")
        if isinstance(speaker_id, str) and speaker_id.strip():
            seen.setdefault(speaker_id.strip(), str(speaker_name or speaker_id).strip())
    return _merge_anchor_candidates(
        [{"entity_id": entity_id, "display_name": display_name} for entity_id, display_name in seen.items()]
    )


def _collect_work_anchor_candidates(payload: dict[str, Any]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for character in payload.get("characters") or []:
        if not isinstance(character, dict):
            continue
        entity_id = str(character.get("entity_id") or "").strip()
        display_name = str(character.get("display_name") or entity_id).strip()
        if not entity_id or not display_name:
            continue
        candidates.append(
            {
                "entity_id": entity_id,
                "display_name": display_name,
                "role_hint": character.get("role"),
            }
        )
    return _merge_anchor_candidates(candidates)


def _resolve_anchor_id(candidates: list[dict[str, Any]], explicit_anchor_person_id: str | None) -> str | None:
    if explicit_anchor_person_id:
        return explicit_anchor_person_id
    for candidate in candidates:
        if candidate.get("role_hint") == "anchor":
            return str(candidate["entity_id"])
    if candidates:
        return str(candidates[0]["entity_id"])
    return None


def _relationship_labels_from_tags(tags: set[str], fallback: str) -> list[str]:
    labels = [fallback]
    if "repair" in tags:
        labels.append("recurrent_repair_partner")
    if "conflict" in tags or "boundary" in tags:
        labels.append("tension_link")
    return labels




def _graph_pair_key(left_entity_id: str, right_entity_id: str) -> tuple[str, str]:
    if left_entity_id <= right_entity_id:
        return left_entity_id, right_entity_id
    return right_entity_id, left_entity_id



def _empty_relationship_graph() -> dict[str, Any]:
    return {
        "nodes": {},
        "edges": {},
    }



def _register_graph_node(
    relationship_graph: dict[str, Any],
    *,
    entity_id: str,
    display_name: str,
    actor_kind: str,
    role_hint: str | None = None,
    is_current_user_binding_candidate: bool = False,
) -> None:
    if not entity_id.strip():
        return
    nodes = relationship_graph["nodes"]
    current = nodes.setdefault(
        entity_id,
        {
            "entity_id": entity_id,
            "display_name": display_name or entity_id,
            "actor_kind": actor_kind,
            "role_hint": role_hint,
            "is_current_user_binding_candidate": bool(is_current_user_binding_candidate),
        },
    )
    if not current.get("display_name") and display_name:
        current["display_name"] = display_name
    if current.get("actor_kind") == "unknown" and actor_kind != "unknown":
        current["actor_kind"] = actor_kind
    if not current.get("role_hint") and role_hint:
        current["role_hint"] = role_hint
    current["is_current_user_binding_candidate"] = current["is_current_user_binding_candidate"] or bool(is_current_user_binding_candidate)



def _register_graph_edge(
    relationship_graph: dict[str, Any],
    *,
    left_entity_id: str,
    right_entity_id: str,
    weight: float,
    timestamp: str | None,
    event_tags: set[str],
    support_line: str | None,
    direct_anchor_edge: bool,
    addressed_turn: bool = False,
    shared_scene: bool = False,
    co_presence_only: bool = False,
) -> None:
    if not left_entity_id.strip() or not right_entity_id.strip() or left_entity_id == right_entity_id:
        return
    edge_key = _graph_pair_key(left_entity_id, right_entity_id)
    edges = relationship_graph["edges"]
    current = edges.setdefault(
        edge_key,
        {
            "entity_ids": edge_key,
            "weight": 0.0,
            "interaction_count": 0,
            "direct_anchor_edge_count": 0,
            "addressed_turn_count": 0,
            "shared_scene_count": 0,
            "co_presence_count": 0,
            "event_tags": set(),
            "last_seen_at": None,
            "supporting_lines": [],
        },
    )
    current["weight"] += max(0.0, weight)
    current["interaction_count"] += 1
    if direct_anchor_edge:
        current["direct_anchor_edge_count"] += 1
    if addressed_turn:
        current["addressed_turn_count"] += 1
    if shared_scene:
        current["shared_scene_count"] += 1
    if co_presence_only:
        current["co_presence_count"] += 1
    current["event_tags"].update({tag for tag in event_tags if tag})
    if timestamp and (not current.get("last_seen_at") or timestamp > str(current["last_seen_at"])):
        current["last_seen_at"] = timestamp
    if support_line:
        excerpt = _trim_excerpt(support_line)
        if excerpt and excerpt not in current["supporting_lines"]:
            current["supporting_lines"].append(excerpt)
        current["supporting_lines"] = current["supporting_lines"][:3]



def _normalize_chat_addressed_to(turn: dict[str, Any], participants_by_name: dict[str, str]) -> list[str]:
    normalized: list[str] = []
    for item in turn.get("addressed_to") or []:
        if not isinstance(item, str) or not item.strip():
            continue
        candidate = item.strip()
        normalized.append(participants_by_name.get(candidate, candidate))
    return list(dict.fromkeys(normalized))



def _build_chat_relationship_graph(
    relationship_graph: dict[str, Any],
    payload: dict[str, Any],
    anchor_person_id: str | None,
) -> None:
    participants: list[dict[str, Any]] = [item for item in (payload.get("participants") or []) if isinstance(item, dict)]
    participants_by_name = {
        str(item.get("display_name") or "").strip(): str(item.get("entity_id") or "").strip()
        for item in participants
        if str(item.get("display_name") or "").strip() and str(item.get("entity_id") or "").strip()
    }
    participant_meta = {
        str(item.get("entity_id") or "").strip(): item
        for item in participants
        if str(item.get("entity_id") or "").strip()
    }
    participant_ids = [entity_id for entity_id in participant_meta if entity_id]
    participant_count = len(participant_ids)

    for entity_id, meta in participant_meta.items():
        _register_graph_node(
            relationship_graph,
            entity_id=entity_id,
            display_name=str(meta.get("display_name") or entity_id).strip(),
            actor_kind="real_person",
            role_hint=meta.get("role"),
            is_current_user_binding_candidate=meta.get("role") == "current_user",
        )

    for turn in payload.get("turns") or []:
        if not isinstance(turn, dict):
            continue
        speaker_id = _resolve_chat_speaker_id(turn, participants_by_name)
        if not speaker_id or speaker_id not in participant_meta:
            continue
        addressed_to = [entity_id for entity_id in _normalize_chat_addressed_to(turn, participants_by_name) if entity_id in participant_meta and entity_id != speaker_id]
        if not addressed_to and participant_count <= 2:
            addressed_to = [entity_id for entity_id in participant_ids if entity_id != speaker_id][:1]
        if not addressed_to:
            continue
        labels = {str(item).strip() for item in (turn.get("labels") or []) if isinstance(item, str) and item.strip()}
        timestamp = _safe_timestamp(turn.get("timestamp"))
        text = str(turn.get("text") or "").strip() or None
        for target_entity_id in addressed_to:
            direct_anchor_edge = anchor_person_id in {speaker_id, target_entity_id}
            base_weight = 1.25 if direct_anchor_edge else 0.55
            if labels:
                base_weight += 0.12 * min(len(labels), 3)
            _register_graph_edge(
                relationship_graph,
                left_entity_id=speaker_id,
                right_entity_id=target_entity_id,
                weight=base_weight,
                timestamp=timestamp,
                event_tags=labels,
                support_line=text,
                direct_anchor_edge=direct_anchor_edge,
                addressed_turn=bool(turn.get("addressed_to")),
            )



def _build_work_relationship_graph(
    relationship_graph: dict[str, Any],
    payload: dict[str, Any],
    anchor_person_id: str | None,
) -> None:
    characters: list[dict[str, Any]] = [item for item in (payload.get("characters") or []) if isinstance(item, dict)]
    display_names: dict[str, str] = {}
    for character in characters:
        entity_id = str(character.get("entity_id") or "").strip()
        display_name = str(character.get("display_name") or entity_id).strip()
        if not entity_id:
            continue
        display_names[entity_id] = display_name
        _register_graph_node(
            relationship_graph,
            entity_id=entity_id,
            display_name=display_name,
            actor_kind="fictional_character",
            role_hint=character.get("role"),
            is_current_user_binding_candidate=character.get("role") == "current_user_binding",
        )
    anchor_name = display_names.get(str(anchor_person_id or ""), "")

    for scene in payload.get("scenes") or []:
        if not isinstance(scene, dict):
            continue
        text = str(scene.get("text") or "")
        lowered = text.lower()
        perspective_character_id = str(scene.get("perspective_character_id") or "").strip()
        mentioned: list[str] = []
        if perspective_character_id and perspective_character_id in display_names:
            mentioned.append(perspective_character_id)
        if anchor_person_id and anchor_name and anchor_name.lower() in lowered:
            mentioned.append(str(anchor_person_id))
        for entity_id, display_name in display_names.items():
            if display_name and display_name.lower() in lowered:
                mentioned.append(entity_id)
        mentioned = list(dict.fromkeys([entity_id for entity_id in mentioned if entity_id in display_names]))
        if anchor_person_id and str(anchor_person_id) not in mentioned:
            continue
        if len(mentioned) < 2:
            continue
        labels = {str(item).strip() for item in (scene.get("labels") or []) if isinstance(item, str) and item.strip()}
        timestamp = _safe_timestamp(scene.get("timestamp"))
        support_line = str(scene.get("heading") or "").strip() or text.strip() or None
        for left_index in range(len(mentioned) - 1):
            for right_index in range(left_index + 1, len(mentioned)):
                left_entity_id = mentioned[left_index]
                right_entity_id = mentioned[right_index]
                direct_anchor_edge = anchor_person_id in {left_entity_id, right_entity_id}
                base_weight = 1.1 if direct_anchor_edge else 0.45
                if perspective_character_id and perspective_character_id in {left_entity_id, right_entity_id}:
                    base_weight += 0.25
                if labels:
                    base_weight += 0.1 * min(len(labels), 3)
                _register_graph_edge(
                    relationship_graph,
                    left_entity_id=left_entity_id,
                    right_entity_id=right_entity_id,
                    weight=base_weight,
                    timestamp=timestamp,
                    event_tags=labels,
                    support_line=support_line,
                    direct_anchor_edge=direct_anchor_edge,
                    shared_scene=True,
                    co_presence_only=not direct_anchor_edge,
                )



def _graph_edge_for(relationship_graph: dict[str, Any], left_entity_id: str, right_entity_id: str) -> dict[str, Any] | None:
    if not left_entity_id.strip() or not right_entity_id.strip() or left_entity_id == right_entity_id:
        return None
    return relationship_graph["edges"].get(_graph_pair_key(left_entity_id, right_entity_id))



def _graph_neighbors(relationship_graph: dict[str, Any], entity_id: str) -> dict[str, dict[str, Any]]:
    neighbors: dict[str, dict[str, Any]] = {}
    for edge in relationship_graph["edges"].values():
        left_entity_id, right_entity_id = edge["entity_ids"]
        if left_entity_id == entity_id:
            neighbors[right_entity_id] = edge
        elif right_entity_id == entity_id:
            neighbors[left_entity_id] = edge
    return neighbors



def _two_hop_support(
    relationship_graph: dict[str, Any],
    *,
    anchor_person_id: str,
    counterpart_entity_id: str,
) -> tuple[float, list[str]]:
    if anchor_person_id == counterpart_entity_id:
        return 0.0, []
    anchor_neighbors = _graph_neighbors(relationship_graph, anchor_person_id)
    counterpart_neighbors = _graph_neighbors(relationship_graph, counterpart_entity_id)
    scored_paths: list[tuple[float, str]] = []
    for mediator_entity_id, left_edge in anchor_neighbors.items():
        if mediator_entity_id in {anchor_person_id, counterpart_entity_id}:
            continue
        right_edge = counterpart_neighbors.get(mediator_entity_id)
        if not right_edge:
            continue
        support = min(float(left_edge.get("weight") or 0.0), float(right_edge.get("weight") or 0.0))
        if support <= 0:
            continue
        scored_paths.append((support, mediator_entity_id))
    scored_paths.sort(key=lambda item: item[0], reverse=True)
    support_score = sum(score for score, _ in scored_paths[:2])
    mediators = [mediator_entity_id for _, mediator_entity_id in scored_paths[:2]]
    return round(support_score, 3), mediators



def _timestamp_rank_value(raw: Any) -> float:
    if not isinstance(raw, str) or not raw.strip():
        return 0.0
    normalized = raw.strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(normalized).timestamp()
    except ValueError:
        return 0.0



def _rank_counterpart_cards(
    cards: list[dict[str, Any]],
    *,
    relationship_graph: dict[str, Any],
    anchor_person_id: str | None,
) -> list[dict[str, Any]]:
    if not cards:
        return []
    if not anchor_person_id or anchor_person_id not in relationship_graph["nodes"]:
        ranked = [dict(card) for card in cards]
        for index, item in enumerate(ranked, start=1):
            item["graph_neighbor_rank"] = index
            item["graph_distance"] = None
            item["graph_proximity_score"] = 0.0
            item["direct_anchor_weight"] = 0.0
            item["two_hop_support_score"] = 0.0
            item["ranking_source"] = "heuristic_fallback"
            item["ranking_reasons"] = ["anchor_graph_node_unresolved"]
        return ranked

    enriched: list[dict[str, Any]] = []
    for card in cards:
        item = dict(card)
        entity_id = str(item.get("entity_id") or "").strip()
        direct_edge = _graph_edge_for(relationship_graph, str(anchor_person_id), entity_id)
        direct_anchor_weight = float(direct_edge.get("weight") or 0.0) if direct_edge else 0.0
        two_hop_support_score, mediators = _two_hop_support(
            relationship_graph,
            anchor_person_id=str(anchor_person_id),
            counterpart_entity_id=entity_id,
        )
        graph_distance = 1 if direct_anchor_weight > 0 else (2 if two_hop_support_score > 0 else None)
        graph_event_tags = set(direct_edge.get("event_tags") or set()) if direct_edge else set()
        event_bonus = 0.05 * min(len(graph_event_tags.intersection({"repair", "conflict", "boundary", "comfort"})), 3)
        structure_bonus = 0.0
        if direct_edge:
            structure_bonus += 0.04 * min(int(direct_edge.get("addressed_turn_count") or 0), 2)
            structure_bonus += 0.04 * min(int(direct_edge.get("shared_scene_count") or 0), 2)
            if direct_edge.get("last_seen_at"):
                structure_bonus += 0.08
        raw_score = round(direct_anchor_weight + 0.35 * two_hop_support_score + event_bonus + structure_bonus, 4)
        reasons: list[str] = []
        if direct_anchor_weight > 0:
            reasons.append("direct_anchor_edge")
        if graph_event_tags.intersection({"repair", "conflict", "boundary", "comfort"}):
            reasons.append("shared_emotional_history")
        if direct_edge and direct_edge.get("last_seen_at"):
            reasons.append("recent_interaction")
        if two_hop_support_score > 0 and mediators:
            reasons.append("two_hop_via:" + ",".join(mediators))
        if not reasons:
            reasons.append("weak_graph_evidence")

        if direct_edge:
            merged_tags = sorted({*item.get("recent_event_tags", []), *[str(tag) for tag in graph_event_tags]})
            item["recent_event_tags"] = merged_tags
            if direct_edge.get("last_seen_at") and (not item.get("last_seen_at") or str(direct_edge["last_seen_at"]) > str(item["last_seen_at"])):
                item["last_seen_at"] = direct_edge["last_seen_at"]
            for line in direct_edge.get("supporting_lines") or []:
                if line not in item.get("sample_supporting_lines", []):
                    item.setdefault("sample_supporting_lines", []).append(line)
            item["sample_supporting_lines"] = item.get("sample_supporting_lines", [])[:3]

        item["graph_distance"] = graph_distance
        item["direct_anchor_weight"] = round(direct_anchor_weight, 2)
        item["two_hop_support_score"] = round(two_hop_support_score, 2)
        item["ranking_source"] = "relationship_graph" if raw_score > 0 else "heuristic_fallback"
        item["ranking_reasons"] = reasons
        item["_raw_graph_score"] = raw_score
        item["_sort_distance"] = graph_distance if graph_distance is not None else 99
        item["_sort_recent"] = _timestamp_rank_value(item.get("last_seen_at"))
        enriched.append(item)

    max_raw_score = max((item["_raw_graph_score"] for item in enriched), default=0.0)
    for item in enriched:
        if max_raw_score > 0 and item["_raw_graph_score"] > 0:
            item["graph_proximity_score"] = round(item["_raw_graph_score"] / max_raw_score, 2)
        else:
            item["graph_proximity_score"] = 0.0

    enriched.sort(
        key=lambda item: (
            int(item["_sort_distance"]),
            -float(item["graph_proximity_score"]),
            -int(item.get("direct_interaction_count") or 0),
            -float(item["_sort_recent"]),
            str(item.get("display_name") or item.get("entity_id") or ""),
        )
    )
    for index, item in enumerate(enriched, start=1):
        item["graph_neighbor_rank"] = index
        item.pop("_raw_graph_score", None)
        item.pop("_sort_distance", None)
        item.pop("_sort_recent", None)

    return enriched


def _serialize_relationship_graph(relationship_graph: dict[str, Any]) -> dict[str, Any]:
    nodes = [
        {
            "entity_id": str(node.get("entity_id") or entity_id),
            "display_name": str(node.get("display_name") or entity_id),
            "actor_kind": str(node.get("actor_kind") or "unknown"),
            "role_hint": node.get("role_hint"),
            "is_current_user_binding_candidate": bool(node.get("is_current_user_binding_candidate")),
        }
        for entity_id, node in sorted(relationship_graph.get("nodes", {}).items(), key=lambda item: item[0])
    ]
    edges: list[dict[str, Any]] = []
    for edge in sorted(relationship_graph.get("edges", {}).values(), key=lambda item: item["entity_ids"]):
        left_entity_id, right_entity_id = edge["entity_ids"]
        edges.append(
            {
                "left_entity_id": left_entity_id,
                "right_entity_id": right_entity_id,
                "weight": round(float(edge.get("weight") or 0.0), 2),
                "interaction_count": int(edge.get("interaction_count") or 0),
                "direct_anchor_edge_count": int(edge.get("direct_anchor_edge_count") or 0),
                "addressed_turn_count": int(edge.get("addressed_turn_count") or 0),
                "shared_scene_count": int(edge.get("shared_scene_count") or 0),
                "co_presence_count": int(edge.get("co_presence_count") or 0),
                "event_tags": sorted(str(tag) for tag in (edge.get("event_tags") or set()) if str(tag).strip()),
                "last_seen_at": edge.get("last_seen_at"),
                "supporting_lines": [str(line) for line in (edge.get("supporting_lines") or []) if str(line).strip()],
            }
        )
    return {
        "nodes": nodes,
        "edges": edges,
    }



def _build_relationship_graph_manifest(
    *,
    twin_id: str,
    graph_group_id: str | None,
    source_mode: str,
    source_refs: list[str],
    source_hash: str | None,
    resolved_anchor_person_id: str | None,
    relationship_graph: dict[str, Any],
    counterpart_candidates: list[dict[str, Any]],
    focal_node_uuid: str | None = None,
) -> dict[str, Any]:
    binding_status = "graphiti_uuid_bound" if focal_node_uuid else "entity_id_only"
    return {
        "manifest_version": 1,
        "generated_at": _now_iso(),
        "twin_id": twin_id,
        "graph_group_id": graph_group_id,
        "source_mode": source_mode,
        "source_refs": source_refs,
        "source_hash": source_hash,
        "resolved_anchor_person_id": resolved_anchor_person_id,
        "anchor_node_binding": {
            "entity_id": resolved_anchor_person_id,
            "focal_node_uuid": focal_node_uuid,
            "binding_status": binding_status,
        },
        "graph": _serialize_relationship_graph(relationship_graph),
        "counterpart_candidates": counterpart_candidates,
    }



def _save_relationship_graph_manifest(service_root: Path, twin_id: str, manifest: dict[str, Any]) -> Path:
    path = _relationship_graph_manifest_path(service_root, twin_id)
    _write_json(path, manifest)
    return path



def _load_relationship_graph_manifest(service_root: Path, twin_id: str) -> dict[str, Any]:
    payload = _load_json(_relationship_graph_manifest_path(service_root, twin_id))
    if not isinstance(payload, dict):
        raise ServiceError("Relationship graph manifest JSON must be an object.")
    return payload



def _scan_and_optionally_persist_manifest(
    *,
    service_root: Path | None,
    twin_id: str | None,
    graph_group_id: str | None,
    source_mode: str,
    source_refs: list[str],
    anchor_person_id: str | None,
    focal_node_uuid: str | None = None,
) -> tuple[dict[str, Any], str | None]:
    scan = _scan_sources(
        source_mode=source_mode,
        source_refs=source_refs,
        anchor_person_id=anchor_person_id,
    )
    manifest_path: str | None = None
    if service_root is not None and twin_id:
        manifest = _build_relationship_graph_manifest(
            twin_id=twin_id,
            graph_group_id=graph_group_id,
            source_mode=scan["source_mode"],
            source_refs=scan["source_refs"],
            source_hash=scan.get("source_hash"),
            resolved_anchor_person_id=scan.get("resolved_anchor_person_id"),
            relationship_graph=scan.get("relationship_graph") or _empty_relationship_graph(),
            counterpart_candidates=scan.get("counterpart_candidates") or [],
            focal_node_uuid=focal_node_uuid,
        )
        manifest_path = str(_save_relationship_graph_manifest(service_root, twin_id, manifest))
    return scan, manifest_path


def _chat_counterpart_cards(payload: dict[str, Any], anchor_person_id: str | None) -> list[dict[str, Any]]:
    participants: list[dict[str, Any]] = [item for item in (payload.get("participants") or []) if isinstance(item, dict)]
    participants_by_name = {
        str(item.get("display_name") or "").strip(): str(item.get("entity_id") or "").strip()
        for item in participants
        if str(item.get("display_name") or "").strip() and str(item.get("entity_id") or "").strip()
    }
    participant_meta = {
        str(item.get("entity_id") or "").strip(): item
        for item in participants
        if str(item.get("entity_id") or "").strip()
    }
    participant_count = max(1, len(participant_meta))
    cards: dict[str, dict[str, Any]] = {}
    for entity_id, meta in participant_meta.items():
        if entity_id == anchor_person_id:
            continue
        cards[entity_id] = {
            "entity_id": entity_id,
            "display_name": str(meta.get("display_name") or entity_id).strip(),
            "actor_kind": "real_person",
            "relationship_labels": [],
            "direct_interaction_count": 0,
            "recent_event_tags": [],
            "last_seen_at": None,
            "sample_supporting_lines": [],
            "role_hint": meta.get("role"),
            "is_current_user_binding_candidate": meta.get("role") == "current_user",
        }
    for turn in payload.get("turns") or []:
        if not isinstance(turn, dict):
            continue
        speaker_id = _resolve_chat_speaker_id(turn, participants_by_name)
        if not speaker_id or speaker_id == anchor_person_id:
            counterpart_id = None
        elif speaker_id in cards:
            counterpart_id = speaker_id
        else:
            counterpart_id = None
        addressed_to = [str(item).strip() for item in (turn.get("addressed_to") or []) if isinstance(item, str) and item.strip()]
        if counterpart_id is None and speaker_id == anchor_person_id:
            counterpart_id = next((entity_id for entity_id in addressed_to if entity_id in cards), None)
        if counterpart_id is None and participant_count <= 2 and speaker_id in cards:
            counterpart_id = speaker_id
        if counterpart_id is None:
            continue
        card = cards[counterpart_id]
        card["direct_interaction_count"] += 1
        labels = {str(item) for item in (turn.get("labels") or []) if isinstance(item, str) and item.strip()}
        card["recent_event_tags"] = sorted({*card["recent_event_tags"], *labels})
        card["relationship_labels"] = _relationship_labels_from_tags(set(card["recent_event_tags"]), "direct_chat_counterpart")
        timestamp = _safe_timestamp(turn.get("timestamp"))
        if timestamp and (not card["last_seen_at"] or timestamp > str(card["last_seen_at"])):
            card["last_seen_at"] = timestamp
        text = turn.get("text")
        if isinstance(text, str) and text.strip():
            excerpt = _trim_excerpt(text)
            if excerpt not in card["sample_supporting_lines"]:
                card["sample_supporting_lines"].append(excerpt)
            card["sample_supporting_lines"] = card["sample_supporting_lines"][:2]
    return _merge_counterpart_cards(list(cards.values()))


def _work_counterpart_cards(payload: dict[str, Any], anchor_person_id: str | None) -> list[dict[str, Any]]:
    characters: list[dict[str, Any]] = [item for item in (payload.get("characters") or []) if isinstance(item, dict)]
    cards: dict[str, dict[str, Any]] = {}
    display_names: dict[str, str] = {}
    for character in characters:
        entity_id = str(character.get("entity_id") or "").strip()
        display_name = str(character.get("display_name") or entity_id).strip()
        if not entity_id or entity_id == anchor_person_id:
            continue
        display_names[entity_id] = display_name
        cards[entity_id] = {
            "entity_id": entity_id,
            "display_name": display_name,
            "actor_kind": "fictional_character",
            "relationship_labels": [],
            "direct_interaction_count": 0,
            "recent_event_tags": [],
            "last_seen_at": None,
            "sample_supporting_lines": [],
            "role_hint": character.get("role"),
            "is_current_user_binding_candidate": character.get("role") == "current_user_binding",
        }
    anchor_name = next((str(character.get("display_name") or "").strip() for character in characters if str(character.get("entity_id") or "").strip() == anchor_person_id), "")
    for scene in payload.get("scenes") or []:
        if not isinstance(scene, dict):
            continue
        text = str(scene.get("text") or "")
        lowered = text.lower()
        mentioned: set[str] = set()
        perspective_character_id = str(scene.get("perspective_character_id") or "").strip()
        if perspective_character_id:
            mentioned.add(perspective_character_id)
        if anchor_name and anchor_name.lower() in lowered:
            mentioned.add(str(anchor_person_id or ""))
        for entity_id, display_name in display_names.items():
            if display_name and display_name.lower() in lowered:
                mentioned.add(entity_id)
        if anchor_person_id and anchor_person_id not in mentioned:
            continue
        for counterpart_id, card in cards.items():
            if counterpart_id not in mentioned:
                continue
            card["direct_interaction_count"] += 1
            labels = {str(item) for item in (scene.get("labels") or []) if isinstance(item, str) and item.strip()}
            card["recent_event_tags"] = sorted({*card["recent_event_tags"], *labels})
            card["relationship_labels"] = _relationship_labels_from_tags(set(card["recent_event_tags"]), "scene_counterpart")
            timestamp = _safe_timestamp(scene.get("timestamp"))
            if timestamp and (not card["last_seen_at"] or timestamp > str(card["last_seen_at"])):
                card["last_seen_at"] = timestamp
            excerpt_source = scene.get("heading") if isinstance(scene.get("heading"), str) and scene.get("heading", "").strip() else text
            excerpt = _trim_excerpt(str(excerpt_source or text))
            if excerpt and excerpt not in card["sample_supporting_lines"]:
                card["sample_supporting_lines"].append(excerpt)
            card["sample_supporting_lines"] = card["sample_supporting_lines"][:2]
    return _merge_counterpart_cards(list(cards.values()))


def _scan_sources(
    *,
    source_mode: str,
    source_refs: list[str],
    anchor_person_id: str | None,
) -> dict[str, Any]:
    refs = _normalize_source_refs(source_refs)
    if not refs:
        raise ServiceError("source_refs must contain at least one corpus path.")
    all_anchor_candidates: list[dict[str, Any]] = []
    all_counterpart_cards: list[dict[str, Any]] = []
    relationship_graph = _empty_relationship_graph()
    for ref in refs:
        payload = _load_json(Path(ref))
        if not isinstance(payload, dict):
            raise ServiceError(f"Corpus JSON must be an object: {ref}")
        if source_mode == "chat_history":
            anchors = _collect_chat_anchor_candidates(payload)
            resolved_anchor_id = _resolve_anchor_id(anchors, anchor_person_id)
            cards = _chat_counterpart_cards(payload, resolved_anchor_id)
            _build_chat_relationship_graph(relationship_graph, payload, resolved_anchor_id)
        elif source_mode == "fiction_work":
            anchors = _collect_work_anchor_candidates(payload)
            resolved_anchor_id = _resolve_anchor_id(anchors, anchor_person_id)
            cards = _work_counterpart_cards(payload, resolved_anchor_id)
            _build_work_relationship_graph(relationship_graph, payload, resolved_anchor_id)
        else:
            raise ServiceError(f"Unsupported source_mode: {source_mode}")
        all_anchor_candidates.extend(anchors)
        all_counterpart_cards.extend(cards)
    merged_anchor_candidates = _merge_anchor_candidates(all_anchor_candidates)
    resolved_anchor_person_id = _resolve_anchor_id(merged_anchor_candidates, anchor_person_id)
    merged_counterpart_cards = _merge_counterpart_cards(all_counterpart_cards)
    ranked_counterpart_cards = _rank_counterpart_cards(
        merged_counterpart_cards,
        relationship_graph=relationship_graph,
        anchor_person_id=resolved_anchor_person_id,
    )
    return {
        "source_mode": source_mode,
        "source_refs": refs,
        "source_hash": _hash_source_refs(refs),
        "resolved_anchor_person_id": resolved_anchor_person_id,
        "anchor_candidates": merged_anchor_candidates,
        "counterpart_candidates": ranked_counterpart_cards,
        "relationship_graph": relationship_graph,
    }


def _annotate_selection_eligibility(cards: list[dict[str, Any]], binding: TwinBinding) -> list[dict[str, Any]]:
    current_entity_id = _binding_entity_id(binding)
    annotated: list[dict[str, Any]] = []
    for card in cards:
        item = dict(card)
        entity_id = str(item.get("entity_id") or "").strip()
        compatible = entity_id == current_entity_id
        item["is_current_user_binding_candidate"] = compatible or bool(item.get("is_current_user_binding_candidate"))
        item["selection_eligibility"] = "publish_ready" if compatible else "requires_role_rebind"
        item["selection_notes"] = (
            ["Matches current_user_role_binding and can become the active one-on-one lens in v1."]
            if compatible
            else ["Selecting this counterpart requires rebinding current_user_role_binding for a v1 one-on-one twin."]
        )
        annotated.append(item)
    return annotated


def _active_selected_binding(service_root: Path, binding: TwinBinding) -> SelectedRelationshipBinding | None:
    if not binding.selected_relationship_binding_id:
        return None
    try:
        return _load_selected_relationship_binding(service_root, binding.twin_id, binding.selected_relationship_binding_id)
    except ServiceError:
        return None


def _artifact_refs(service_root: Path, twin_id: str) -> dict[str, Any]:
    return {
        "graph_path": str(_graph_path(service_root, twin_id)),
        "binding_path": str(_binding_path(service_root, twin_id)),
        "selected_relationship_dir": str(_selected_relationship_dir(service_root, twin_id)),
        "relationship_graph_manifest_path": str(_relationship_graph_manifest_path(service_root, twin_id)),
    }


def _selected_binding_id(twin_id: str, counterpart_entity_id: str) -> str:
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    safe_counterpart = counterpart_entity_id.replace(":", "_").replace("/", "_")
    return f"relbind_{twin_id}_{safe_counterpart}_{stamp}"


def _parse_reference_time(raw: str | None) -> datetime:
    if raw is None or not raw.strip():
        return datetime.now(UTC)
    normalized = raw.strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ServiceError(f"Invalid ISO timestamp: {raw}") from exc


def _import_graphiti() -> tuple[Any, Any, Any]:
    try:
        from graphiti_core import Graphiti
        from graphiti_core.driver.kuzu_driver import KuzuDriver
        from graphiti_core.nodes import EpisodeType
    except ImportError as exc:
        raise ServiceError(
            "Graphiti is not installed. Install it with "
            "\"pip install 'graphiti-core[kuzu]'\" first."
        ) from exc
    return Graphiti, KuzuDriver, EpisodeType


async def _open_graphiti(service_root: Path, twin_id: str) -> Any:
    Graphiti, KuzuDriver, _ = _import_graphiti()
    graph_path = _graph_path(service_root, twin_id)
    graph_path.parent.mkdir(parents=True, exist_ok=True)
    driver = KuzuDriver(db=str(graph_path))
    graphiti = Graphiti(graph_driver=driver)
    build = getattr(graphiti, "build_indices_and_constraints", None)
    if callable(build):
        await build()
    return graphiti


async def _close_graphiti(graphiti: Any) -> None:
    close = getattr(graphiti, "close", None)
    if callable(close):
        await close()


def _message_episode_body(turns: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for turn in turns:
        speaker = (
            turn.get("speaker")
            or turn.get("speaker_id")
            or turn.get("speaker_name")
            or "Unknown"
        )
        text = turn.get("text")
        if not isinstance(text, str) or not text.strip():
            continue
        lines.append(f"{speaker}: {text.strip()}")
    if not lines:
        raise ServiceError("Message episode batch produced no usable lines.")
    return "\n".join(lines)


def _chunked(items: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
    return [items[index:index + size] for index in range(0, len(items), size)]


async def _add_episode(
    graphiti: Any,
    *,
    name: str,
    episode_body: Any,
    source: Any,
    source_description: str,
    reference_time: datetime,
    group_id: str,
) -> None:
    await graphiti.add_episode(
        name=name,
        episode_body=episode_body,
        source=source,
        source_description=source_description,
        reference_time=reference_time,
        group_id=group_id,
    )


async def _search_graph(
    graphiti: Any,
    *,
    query: str,
    group_id: str,
    focal_node_uuid: str | None,
) -> Any:
    search = getattr(graphiti, "search")
    signature = inspect.signature(search)
    kwargs: dict[str, Any] = {}
    if "query" in signature.parameters:
        kwargs["query"] = query
    if "group_id" in signature.parameters:
        kwargs["group_id"] = group_id
    if focal_node_uuid and "focal_node_uuid" in signature.parameters:
        kwargs["focal_node_uuid"] = focal_node_uuid
    if kwargs:
        return await search(**kwargs)
    if focal_node_uuid:
        return await search(query, focal_node_uuid)
    return await search(query)


def _object_field(payload: Any, field: str) -> Any:
    if isinstance(payload, dict):
        return payload.get(field)
    return getattr(payload, field, None)



def _edge_to_dict(edge: Any) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for field in (
        "uuid",
        "name",
        "fact",
        "group_id",
        "source_node_uuid",
        "target_node_uuid",
        "source_node_name",
        "target_node_name",
        "source_name",
        "target_name",
        "source_entity_id",
        "target_entity_id",
        "created_at",
        "expired_at",
        "valid_at",
        "invalid_at",
    ):
        value = _object_field(edge, field)
        if value is not None:
            result[field] = str(value)
    if not result:
        result["repr"] = repr(edge)
    return result





def _node_to_dict(node: Any) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for field in (
        "uuid",
        "name",
        "display_name",
        "entity_id",
        "group_id",
        "node_type",
        "label",
    ):
        value = _object_field(node, field)
        if value is not None:
            result[field] = str(value)
    if not result:
        result["repr"] = repr(node)
    return result



def _normalize_graphiti_result_items(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, tuple):
        return list(value)
    if isinstance(value, dict):
        for key in ("results", "items", "nodes", "edges", "neighbors", "hits"):
            nested = value.get(key)
            if isinstance(nested, list):
                return nested
        return []
    for attr in ("results", "items", "nodes", "edges", "neighbors", "hits"):
        nested = getattr(value, attr, None)
        if isinstance(nested, list):
            return nested
    return []



def _unique_identity_terms(values: list[str]) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = value.strip()
        if not normalized:
            continue
        key = normalized.lower()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(normalized)
    return ordered



def _manifest_anchor_terms(manifest_payload: dict[str, Any], anchor_person_id: str) -> list[str]:
    graph_payload = manifest_payload.get("graph") or {}
    node_rows = graph_payload.get("nodes") or []
    terms = [anchor_person_id]
    for node in node_rows:
        if not isinstance(node, dict):
            continue
        if str(node.get("entity_id") or "").strip() != anchor_person_id:
            continue
        terms.append(str(node.get("display_name") or "").strip())
        terms.append(str(node.get("name") or "").strip())
    resolved_anchor = str(manifest_payload.get("resolved_anchor_person_id") or "").strip()
    if resolved_anchor:
        terms.append(resolved_anchor)
    return _unique_identity_terms(terms)



def _node_identity_tokens(node_payload: dict[str, Any]) -> set[str]:
    tokens: set[str] = set()
    for key in ("entity_id", "display_name", "name", "label"):
        value = str(node_payload.get(key) or "").strip()
        if value:
            tokens.add(value.lower())
    return tokens



def _candidate_identity_tokens(card: dict[str, Any]) -> set[str]:
    tokens: set[str] = set()
    for key in ("entity_id", "display_name", "graphiti_counterpart_node_uuid"):
        value = str(card.get(key) or "").strip()
        if value:
            tokens.add(value.lower())
    return tokens



async def _invoke_graphiti_method(graphiti: Any, method_names: tuple[str, ...], kwargs_options: list[dict[str, Any]]) -> Any:
    for method_name in method_names:
        method = getattr(graphiti, method_name, None)
        if not callable(method):
            continue
        for option in kwargs_options:
            try:
                signature = inspect.signature(method)
            except (TypeError, ValueError):
                signature = None
            if signature is None:
                filtered = {key: value for key, value in option.items() if value is not None}
            else:
                filtered = {
                    key: value
                    for key, value in option.items()
                    if value is not None and key in signature.parameters
                }
                missing_required = [
                    parameter.name
                    for parameter in signature.parameters.values()
                    if parameter.kind in (inspect.Parameter.POSITIONAL_OR_KEYWORD, inspect.Parameter.KEYWORD_ONLY)
                    and parameter.default is inspect._empty
                    and parameter.name not in filtered
                ]
                if missing_required:
                    continue
            try:
                result = method(**filtered)
            except TypeError:
                continue
            if inspect.isawaitable(result):
                result = await result
            return result
    raise ServiceError("No compatible Graphiti method matched the requested operation.")



async def _resolve_anchor_node_binding_with_graphiti(
    graphiti: Any,
    *,
    group_id: str,
    anchor_person_id: str,
    manifest_payload: dict[str, Any],
    current_focal_node_uuid: str | None,
) -> dict[str, Any]:
    anchor_binding = dict(manifest_payload.get("anchor_node_binding") or {})
    if current_focal_node_uuid:
        anchor_binding["entity_id"] = anchor_person_id
        anchor_binding["focal_node_uuid"] = current_focal_node_uuid
        anchor_binding["binding_status"] = "graphiti_uuid_bound"
        return anchor_binding
    existing_uuid = str(anchor_binding.get("focal_node_uuid") or "").strip()
    if existing_uuid:
        anchor_binding["binding_status"] = str(anchor_binding.get("binding_status") or "graphiti_uuid_bound")
        anchor_binding["entity_id"] = anchor_person_id
        return anchor_binding

    terms = _manifest_anchor_terms(manifest_payload, anchor_person_id)
    node_candidates: list[dict[str, Any]] = []
    for term in terms:
        try:
            result = await _invoke_graphiti_method(
                graphiti,
                ("search_nodes", "query_nodes", "find_nodes", "get_nodes"),
                [
                    {"query": term, "group_id": group_id, "limit": 8},
                    {"text": term, "group_id": group_id, "limit": 8},
                    {"name": term, "group_id": group_id, "limit": 8},
                    {"node_name": term, "group_id": group_id, "limit": 8},
                    {"group_id": group_id, "limit": 8},
                ],
            )
        except ServiceError:
            result = None
        for item in _normalize_graphiti_result_items(result):
            payload = _node_to_dict(item)
            if payload:
                node_candidates.append(payload)
    best_uuid = ""
    best_score = -1
    lower_terms = {term.lower() for term in terms}
    for node_payload in node_candidates:
        node_uuid = str(node_payload.get("uuid") or "").strip()
        if not node_uuid:
            continue
        score = len(_node_identity_tokens(node_payload).intersection(lower_terms))
        if score > best_score:
            best_score = score
            best_uuid = node_uuid
    if not best_uuid:
        frequency: dict[str, int] = {}
        for term in terms:
            try:
                search_hits = await _search_graph(graphiti, query=term, group_id=group_id, focal_node_uuid=None)
            except Exception:
                continue
            for raw_edge in list(search_hits or [])[:12]:
                edge_payload = _edge_to_dict(raw_edge)
                for endpoint in ("source_node_uuid", "target_node_uuid"):
                    node_uuid = str(edge_payload.get(endpoint) or "").strip()
                    if node_uuid:
                        frequency[node_uuid] = frequency.get(node_uuid, 0) + 1
        if frequency:
            best_uuid = max(frequency.items(), key=lambda item: item[1])[0]
            anchor_binding["binding_status"] = "graphiti_search_inferred"
    else:
        anchor_binding["binding_status"] = "graphiti_uuid_bound"
    anchor_binding["entity_id"] = anchor_person_id
    anchor_binding["focal_node_uuid"] = best_uuid or None
    if not anchor_binding.get("binding_status"):
        anchor_binding["binding_status"] = "entity_id_only"
    return anchor_binding



def _match_counterpart_card_from_edge(edge_payload: dict[str, Any], focal_node_uuid: str, cards: list[dict[str, Any]]) -> dict[str, Any] | None:
    if str(edge_payload.get("source_node_uuid") or "") == focal_node_uuid:
        endpoint = {
            "node_uuid": edge_payload.get("target_node_uuid"),
            "entity_id": edge_payload.get("target_entity_id"),
            "display_name": edge_payload.get("target_node_name") or edge_payload.get("target_name"),
        }
    elif str(edge_payload.get("target_node_uuid") or "") == focal_node_uuid:
        endpoint = {
            "node_uuid": edge_payload.get("source_node_uuid"),
            "entity_id": edge_payload.get("source_entity_id"),
            "display_name": edge_payload.get("source_node_name") or edge_payload.get("source_name"),
        }
    else:
        return None
    endpoint_tokens = _candidate_identity_tokens(endpoint)
    if not endpoint_tokens:
        return None
    for card in cards:
        if _candidate_identity_tokens(card).intersection(endpoint_tokens):
            return card
    return None



async def _refresh_manifest_with_graphiti_neighborhood(
    *,
    service_root: Path,
    binding: TwinBinding,
    manifest_payload: dict[str, Any],
) -> dict[str, Any]:
    try:
        graphiti = await _open_graphiti(service_root, binding.twin_id)
    except ServiceError:
        return manifest_payload

    updated = False
    try:
        anchor_binding = await _resolve_anchor_node_binding_with_graphiti(
            graphiti,
            group_id=binding.graph_group_id,
            anchor_person_id=binding.anchor_person_id,
            manifest_payload=manifest_payload,
            current_focal_node_uuid=binding.focal_node_uuid,
        )
        focal_node_uuid = str(anchor_binding.get("focal_node_uuid") or "").strip()
        existing_anchor_binding = manifest_payload.get("anchor_node_binding") or {}
        if anchor_binding != existing_anchor_binding:
            manifest_payload["anchor_node_binding"] = anchor_binding
            updated = True
        if focal_node_uuid and binding.focal_node_uuid != focal_node_uuid:
            binding.focal_node_uuid = focal_node_uuid
            _save_binding(service_root, binding)
        if not focal_node_uuid:
            if updated:
                _save_relationship_graph_manifest(service_root, binding.twin_id, manifest_payload)
            return manifest_payload

        try:
            raw_neighbors = await _invoke_graphiti_method(
                graphiti,
                ("get_neighbors", "neighbors", "get_neighborhood", "query_neighborhood"),
                [
                    {"focal_node_uuid": focal_node_uuid, "group_id": binding.graph_group_id, "max_depth": 2, "limit": 32},
                    {"node_uuid": focal_node_uuid, "group_id": binding.graph_group_id, "max_depth": 2, "limit": 32},
                    {"center_node_uuid": focal_node_uuid, "group_id": binding.graph_group_id, "max_depth": 2, "limit": 32},
                    {"uuid": focal_node_uuid, "group_id": binding.graph_group_id, "max_depth": 2, "limit": 32},
                ],
            )
        except ServiceError:
            raw_neighbors = None
        edges = [_edge_to_dict(item) for item in _normalize_graphiti_result_items(raw_neighbors)]
        if not edges:
            if updated:
                _save_relationship_graph_manifest(service_root, binding.twin_id, manifest_payload)
            return manifest_payload

        cards = [dict(card) for card in (manifest_payload.get("counterpart_candidates") or [])]
        graphiti_scores: dict[str, dict[str, Any]] = {}
        for edge_payload in edges:
            matched_card = _match_counterpart_card_from_edge(edge_payload, focal_node_uuid, cards)
            if matched_card is None:
                continue
            entity_id = str(matched_card.get("entity_id") or "").strip()
            if not entity_id:
                continue
            current = graphiti_scores.setdefault(
                entity_id,
                {
                    "edge_count": 0,
                    "counterpart_node_uuid": None,
                },
            )
            current["edge_count"] += 1
            counterpart_uuid = (
                edge_payload.get("target_node_uuid")
                if str(edge_payload.get("source_node_uuid") or "") == focal_node_uuid
                else edge_payload.get("source_node_uuid")
            )
            current["counterpart_node_uuid"] = current["counterpart_node_uuid"] or counterpart_uuid
        if not graphiti_scores:
            if updated:
                _save_relationship_graph_manifest(service_root, binding.twin_id, manifest_payload)
            return manifest_payload

        max_edges = max(item["edge_count"] for item in graphiti_scores.values())
        refreshed_cards: list[dict[str, Any]] = []
        for index, card in enumerate(cards, start=1):
            item = dict(card)
            entity_id = str(item.get("entity_id") or "").strip()
            graphiti_entry = graphiti_scores.get(entity_id)
            if graphiti_entry:
                score = round(graphiti_entry["edge_count"] / max_edges, 2) if max_edges else 0.0
                item["graphiti_edge_count"] = graphiti_entry["edge_count"]
                item["graphiti_neighbor_score"] = score
                item["graphiti_counterpart_node_uuid"] = graphiti_entry["counterpart_node_uuid"]
                item["anchor_node_uuid"] = focal_node_uuid
                item["ranking_source"] = "graphiti_neighborhood"
                reasons = [str(reason) for reason in (item.get("ranking_reasons") or []) if str(reason).strip()]
                if "graphiti_direct_neighbor" not in reasons:
                    reasons.insert(0, "graphiti_direct_neighbor")
                item["ranking_reasons"] = reasons
            else:
                item["graphiti_edge_count"] = 0
                item["graphiti_neighbor_score"] = 0.0
                item.pop("graphiti_counterpart_node_uuid", None)
                item.pop("anchor_node_uuid", None)
                reasons = [str(reason) for reason in (item.get("ranking_reasons") or []) if str(reason).strip() and str(reason) != "graphiti_direct_neighbor"]
                item["ranking_reasons"] = reasons
                if str(item.get("ranking_source") or "") == "graphiti_neighborhood":
                    item["ranking_source"] = "relationship_graph" if float(item.get("graph_proximity_score") or 0.0) > 0 else "heuristic_fallback"
            item["_sort_graphiti"] = -float(item.get("graphiti_neighbor_score") or 0.0)
            item["_sort_rank"] = int(item.get("graph_neighbor_rank") or index)
            refreshed_cards.append(item)
        refreshed_cards.sort(
            key=lambda item: (
                item["_sort_graphiti"],
                item["_sort_rank"],
                -float(item.get("graph_proximity_score") or 0.0),
                str(item.get("display_name") or item.get("entity_id") or ""),
            )
        )
        for index, item in enumerate(refreshed_cards, start=1):
            item["graph_neighbor_rank"] = index
            item.pop("_sort_graphiti", None)
            item.pop("_sort_rank", None)
        manifest_payload["counterpart_candidates"] = refreshed_cards
        updated = True
        _save_relationship_graph_manifest(service_root, binding.twin_id, manifest_payload)
        return manifest_payload
    finally:
        await _close_graphiti(graphiti)

def _project_drafts(
    *,
    service_root: Path,
    binding: TwinBinding,
    query: str,
    hits: list[dict[str, Any]],
) -> dict[str, Any]:
    relationship_drafts: list[dict[str, Any]] = []
    relationship_projection_drafts: list[dict[str, Any]] = []
    background_context_drafts: list[dict[str, Any]] = []
    memory_drafts: list[dict[str, Any]] = []
    notes = [
        "This bundle is a draft export for review, not a direct runtime write.",
        "Graphiti is only supplying relationship and fact candidates here.",
        "Soul extraction still belongs to the separate personality pipeline.",
    ]
    active_selected_binding = _active_selected_binding(service_root, binding)
    if binding.selected_relationship_binding_id and active_selected_binding is None:
        notes.append("The active selected relationship binding could not be loaded; export stays review-only.")
    import_session = None
    if binding.import_session_id:
        try:
            import_session = asdict(_load_import_session(service_root, binding.import_session_id))
        except ServiceError:
            notes.append("The linked import session could not be loaded from disk.")

    for index, hit in enumerate(hits, start=1):
        fact = hit.get("fact")
        if not isinstance(fact, str) or not fact.strip():
            continue
        summary = fact.strip()
        relationship_drafts.append(
            {
                "draft_id": f"relationship-{index}",
                "anchor_person_id": binding.anchor_person_id,
                "counterpart_entity_id": active_selected_binding.counterpart_entity_id if active_selected_binding else None,
                "selected_relationship_binding_id": binding.selected_relationship_binding_id,
                "summary": summary,
                "confidence": 0.4,
                "source": "graphiti_search",
                "notes": [
                    "Review before promotion.",
                    "Anchor-node UUID resolution and neighborhood refresh are best-effort and depend on Graphiti availability.",
                ],
            }
        )
        relationship_projection_drafts.append(
            {
                "draft_id": f"projection-{index}",
                "anchor_person_id": binding.anchor_person_id,
                "counterpart_entity_id": active_selected_binding.counterpart_entity_id if active_selected_binding else None,
                "selected_relationship_binding_id": binding.selected_relationship_binding_id,
                "relationship_summary": summary,
                "supporting_events": [],
                "graph_distance": 1 if active_selected_binding else 2,
                "promotion_score": 0.72 if active_selected_binding else 0.35,
                "apply_scope": "selected_counterpart_default" if active_selected_binding else "anchor_background",
                "promotion_reason": [
                    "graphiti_search",
                    "selected_counterpart" if active_selected_binding else "no_active_selected_counterpart",
                ],
                "invalidation_scope": "reproject_on_rebind",
            }
        )
        if not active_selected_binding:
            background_context_drafts.append(
                {
                    "draft_id": f"background-{index}",
                    "kind": "relationship_context",
                    "summary": summary,
                    "source": "graphiti_search",
                }
            )
        memory_drafts.append(
            {
                "draft_id": f"memory-{index}",
                "kind": "project_fact",
                "content": summary,
                "confidence": 0.35,
                "source": "graphiti_search",
            }
        )

    return {
        "twin_id": binding.twin_id,
        "graph_group_id": binding.graph_group_id,
        "anchor_person_id": binding.anchor_person_id,
        "query": query,
        "generated_at": _now_iso(),
        "binding_context": {
            "current_user_role_binding": binding.current_user_role_binding,
            "selected_relationship_binding_id": binding.selected_relationship_binding_id,
            "counterpart_entity_id": active_selected_binding.counterpart_entity_id if active_selected_binding else None,
            "overlay_key": active_selected_binding.overlay_key if active_selected_binding else None,
        },
        "import_session": import_session,
        "graph_hits": hits,
        "relationship_drafts": relationship_drafts,
        "relationship_projection_drafts": relationship_projection_drafts,
        "background_context_drafts": background_context_drafts,
        "memory_drafts": memory_drafts,
        "notes": notes,
    }


async def initialize_twin(
    *,
    service_root: str | None,
    twin_id: str,
    anchor_person_id: str,
    interaction_mode: str,
    source_mode: str,
    binding_type: str = "real_user",
    binding_entity_id: str | None = None,
    focal_node_uuid: str | None = None,
    source_refs: list[str] | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    _ensure_service_dirs(root)
    role_binding = _role_binding(binding_type, _require_string({"binding_entity_id": binding_entity_id}, "binding_entity_id"))
    normalized_source_refs = _normalize_source_refs(source_refs)
    session_id = f"import_{twin_id}_{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}"
    session = ImportSession(
        session_id=session_id,
        twin_id=twin_id,
        anchor_person_id=anchor_person_id,
        state="binding_created",
        source_mode=source_mode,
        source_refs=normalized_source_refs,
        source_hash=_hash_source_refs(normalized_source_refs),
        current_user_role_binding=role_binding,
        artifact_refs=_artifact_refs(root, twin_id),
    )
    binding = TwinBinding(
        twin_id=twin_id,
        graph_group_id=f"twin:{twin_id}",
        anchor_person_id=anchor_person_id,
        interaction_mode=interaction_mode,
        source_mode=source_mode,
        current_user_role_binding=role_binding,
        selected_relationship_binding_id=None,
        import_session_id=session.session_id,
        focal_node_uuid=focal_node_uuid,
    )
    session_path = _save_import_session(root, session)
    binding_path = _save_binding(root, binding)
    return {
        "status": "ok",
        "binding_path": str(binding_path),
        "session_path": str(session_path),
        "graph_path": str(_graph_path(root, twin_id)),
        "binding": asdict(binding),
        "import_session": asdict(session),
    }


async def create_twin_binding(
    *,
    service_root: str | None,
    twin_id: str,
    anchor_person_id: str,
    interaction_mode: str,
    source_mode: str,
    current_user_role_binding: dict[str, Any] | None = None,
    binding_type: str = "real_user",
    binding_entity_id: str | None = None,
    focal_node_uuid: str | None = None,
    source_refs: list[str] | None = None,
) -> dict[str, Any]:
    role_binding = (
        _role_binding(
            str(current_user_role_binding.get("type") or binding_type),
            _require_string(current_user_role_binding, "entity_id"),
        )
        if current_user_role_binding
        else _role_binding(binding_type, _require_string({"binding_entity_id": binding_entity_id}, "binding_entity_id"))
    )
    created = await initialize_twin(
        service_root=service_root,
        twin_id=twin_id,
        anchor_person_id=anchor_person_id,
        interaction_mode=interaction_mode,
        source_mode=source_mode,
        binding_type=str(role_binding.get("type") or binding_type),
        binding_entity_id=str(role_binding.get("entity_id") or binding_entity_id or ""),
        focal_node_uuid=focal_node_uuid,
        source_refs=source_refs,
    )
    refs = _normalize_source_refs(source_refs)
    if not refs:
        return created
    root = _service_root(service_root)
    scan, manifest_path = _scan_and_optionally_persist_manifest(
        service_root=root,
        twin_id=twin_id,
        graph_group_id=created["binding"]["graph_group_id"],
        source_mode=source_mode,
        source_refs=refs,
        anchor_person_id=anchor_person_id,
        focal_node_uuid=focal_node_uuid,
    )
    if created.get("import_session", {}).get("session_id"):
        session = _load_import_session(root, created["import_session"]["session_id"])
        session.artifact_refs = {
            **session.artifact_refs,
            **_artifact_refs(root, twin_id),
        }
        _save_import_session(root, session)
        created["import_session"] = asdict(session)
        created["session_path"] = str(_import_session_path(root, session.session_id))
    created["scan_summary"] = {
        "resolved_anchor_person_id": scan.get("resolved_anchor_person_id"),
        "candidate_count": len(scan.get("counterpart_candidates") or []),
        "source_hash": scan.get("source_hash"),
    }
    created["relationship_graph_manifest_path"] = manifest_path
    return created


async def preflight_scan(
    *,
    service_root: str | None,
    source_mode: str,
    source_refs: list[str],
    anchor_person_id: str | None = None,
) -> dict[str, Any]:
    scan = _scan_sources(
        source_mode=source_mode,
        source_refs=source_refs,
        anchor_person_id=anchor_person_id,
    )
    response = {
        "status": "ok",
        **{key: value for key, value in scan.items() if key != "relationship_graph"},
    }
    if service_root:
        response["notes"] = [
            "Create the twin binding to persist this preflight graph neighborhood as a manifest for later selector reuse.",
        ]
    return response


async def list_relationship_candidates(
    *,
    service_root: str | None,
    twin_id: str,
    source_refs: list[str] | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    refs = _normalize_source_refs(source_refs)
    session = _load_import_session(root, binding.import_session_id) if binding.import_session_id else None
    manifest_path = _relationship_graph_manifest_path(root, twin_id)

    manifest_payload: dict[str, Any] | None = None
    if not refs and manifest_path.exists():
        manifest_payload = _load_relationship_graph_manifest(root, twin_id)
    if manifest_payload is None:
        if not refs and session:
            refs = _normalize_source_refs(session.source_refs)
        scan, saved_manifest_path = _scan_and_optionally_persist_manifest(
            service_root=root,
            twin_id=twin_id,
            graph_group_id=binding.graph_group_id,
            source_mode=binding.source_mode,
            source_refs=refs,
            anchor_person_id=binding.anchor_person_id,
            focal_node_uuid=binding.focal_node_uuid,
        )
        if saved_manifest_path:
            manifest_payload = _load_relationship_graph_manifest(root, twin_id)
            if session is not None:
                session.artifact_refs = {
                    **session.artifact_refs,
                    **_artifact_refs(root, twin_id),
                }
                _save_import_session(root, session)
        else:
            manifest_payload = {
                "source_refs": scan["source_refs"],
                "source_hash": scan["source_hash"],
                "counterpart_candidates": scan["counterpart_candidates"],
            }
    elif manifest_path.exists():
        manifest_payload = await _refresh_manifest_with_graphiti_neighborhood(
            service_root=root,
            binding=binding,
            manifest_payload=manifest_payload,
        )
    candidates = _annotate_selection_eligibility(list(manifest_payload.get("counterpart_candidates") or []), binding)
    return {
        "status": "ok",
        "twin_id": twin_id,
        "anchor_person_id": binding.anchor_person_id,
        "current_user_role_binding": binding.current_user_role_binding,
        "selected_relationship_binding_id": binding.selected_relationship_binding_id,
        "source_mode": binding.source_mode,
        "source_refs": list(manifest_payload.get("source_refs") or refs),
        "source_hash": manifest_payload.get("source_hash"),
        "relationship_graph_manifest_path": str(manifest_path) if manifest_path.exists() else None,
        "candidates": candidates,
    }


async def select_relationship(
    *,
    service_root: str | None,
    twin_id: str,
    anchor_person_id: str,
    counterpart_entity_id: str,
    selection_source: str = "user_selected",
    relationship_label: str | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    if anchor_person_id != binding.anchor_person_id:
        raise ServiceError("anchor_person_id does not match the stored twin binding.")
    expected_counterpart_id = _binding_entity_id(binding)
    if counterpart_entity_id != expected_counterpart_id:
        raise ServiceError(
            "For v1 one-on-one twins, selected counterpart must match current_user_role_binding.entity_id. "
            "Update current_user_role_binding first if you want to rebind to another counterpart."
        )
    previous_binding = _active_selected_binding(root, binding)
    if previous_binding:
        previous_binding.status = "superseded"
        _save_selected_relationship_binding(root, previous_binding)
    candidates_payload = None
    try:
        candidates_payload = await list_relationship_candidates(service_root=service_root, twin_id=twin_id)
    except ServiceError:
        candidates_payload = None
    selected_label = relationship_label or "selected_counterpart"
    if candidates_payload:
        matched = next(
            (
                item for item in candidates_payload["candidates"]
                if str(item.get("entity_id") or "") == counterpart_entity_id
            ),
            None,
        )
        if matched and matched.get("relationship_labels"):
            selected_label = str(matched["relationship_labels"][0])
    selected_binding = SelectedRelationshipBinding(
        binding_id=_selected_binding_id(twin_id, counterpart_entity_id),
        twin_id=twin_id,
        anchor_person_id=anchor_person_id,
        counterpart_entity_id=counterpart_entity_id,
        counterpart_binding_type=str(binding.current_user_role_binding.get("type") or "real_user"),
        relationship_label=selected_label,
        overlay_key=f"counterpart:{counterpart_entity_id}",
        selection_source=selection_source,
        status="active",
        selection_version=(previous_binding.selection_version + 1) if previous_binding else 1,
    )
    selected_path = _save_selected_relationship_binding(root, selected_binding)
    binding.selected_relationship_binding_id = selected_binding.binding_id
    binding_path = _save_binding(root, binding)
    session_path = None
    if binding.import_session_id:
        session = _load_import_session(root, binding.import_session_id)
        session.selected_relationship_binding_id = selected_binding.binding_id
        session.state = "rebound" if previous_binding else "relationship_selected"
        session.current_user_role_binding = binding.current_user_role_binding
        session.artifact_refs = _artifact_refs(root, twin_id)
        session_path = _save_import_session(root, session)
    return {
        "status": "ok",
        "twin_id": twin_id,
        "binding_path": str(binding_path),
        "selected_relationship_binding_path": str(selected_path),
        "session_path": str(session_path) if session_path else None,
        "superseded_binding_id": previous_binding.binding_id if previous_binding else None,
        "selected_relationship_binding": asdict(selected_binding),
    }


async def rebind_relationship(
    *,
    service_root: str | None,
    twin_id: str,
    anchor_person_id: str,
    to_counterpart_entity_id: str,
    selection_source: str = "user_selected",
    from_binding_id: str | None = None,
    binding_type: str | None = None,
    binding_entity_id: str | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    if anchor_person_id != binding.anchor_person_id:
        raise ServiceError("anchor_person_id does not match the stored twin binding.")
    if from_binding_id and binding.selected_relationship_binding_id != from_binding_id:
        raise ServiceError("from_binding_id does not match the current active selected relationship binding.")
    target_binding_entity_id = binding_entity_id or to_counterpart_entity_id
    current_entity_id = _binding_entity_id(binding)
    if to_counterpart_entity_id != current_entity_id or target_binding_entity_id != current_entity_id:
        binding.current_user_role_binding = _role_binding(
            str(binding_type or binding.current_user_role_binding.get("type") or "real_user"),
            target_binding_entity_id,
        )
        _save_binding(root, binding)
    return await select_relationship(
        service_root=service_root,
        twin_id=twin_id,
        anchor_person_id=anchor_person_id,
        counterpart_entity_id=to_counterpart_entity_id,
        selection_source=selection_source,
    )


async def ingest_chat_file(
    *,
    service_root: str | None,
    twin_id: str,
    source: str,
    batch_size: int = 8,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    payload = _load_json(Path(source))
    if not isinstance(payload, dict):
        raise ServiceError("Chat corpus JSON must be an object.")
    turns = _require_list(payload, "turns")
    chunk_size = max(1, batch_size)

    _, _, EpisodeType = _import_graphiti()
    graphiti = await _open_graphiti(root, twin_id)
    added = 0
    try:
        for batch_index, batch in enumerate(_chunked(turns, chunk_size), start=1):
            if not batch:
                continue
            first_turn = batch[0]
            timestamp = _parse_reference_time(first_turn.get("timestamp"))
            episode_name = f"{payload.get('source_id', 'chat')}_batch_{batch_index:04d}"
            await _add_episode(
                graphiti,
                name=episode_name,
                episode_body=_message_episode_body(batch),
                source=EpisodeType.message,
                source_description=payload.get("title", "Imported chat corpus"),
                reference_time=timestamp,
                group_id=binding.graph_group_id,
            )
            added += 1
    finally:
        await _close_graphiti(graphiti)

    result = {
        "status": "ok",
        "mode": "ingest_chat",
        "twin_id": twin_id,
        "group_id": binding.graph_group_id,
        "episodes_added": added,
    }
    manifest_path = _relationship_graph_manifest_path(root, twin_id)
    if manifest_path.exists():
        manifest = _load_relationship_graph_manifest(root, twin_id)
        anchor_binding = dict(manifest.get("anchor_node_binding") or {})
        anchor_binding["focal_node_uuid"] = binding.focal_node_uuid
        anchor_binding["binding_status"] = "graphiti_uuid_bound" if binding.focal_node_uuid else "entity_id_only"
        manifest["graph_group_id"] = binding.graph_group_id
        manifest["anchor_node_binding"] = anchor_binding
        _save_relationship_graph_manifest(root, twin_id, manifest)
        result["relationship_graph_manifest_path"] = str(manifest_path)
    return result


async def ingest_work_file(
    *,
    service_root: str | None,
    twin_id: str,
    source: str,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    payload = _load_json(Path(source))
    if not isinstance(payload, dict):
        raise ServiceError("Work corpus JSON must be an object.")
    scenes = _require_list(payload, "scenes")

    _, _, EpisodeType = _import_graphiti()
    graphiti = await _open_graphiti(root, twin_id)
    added = 0
    try:
        for index, scene in enumerate(scenes, start=1):
            if not isinstance(scene, dict):
                raise ServiceError("Each scene entry must be an object.")
            text = scene.get("text")
            if not isinstance(text, str) or not text.strip():
                continue
            timestamp = _parse_reference_time(scene.get("timestamp"))
            scene_id = scene.get("scene_id") or f"scene_{index:04d}"
            await _add_episode(
                graphiti,
                name=f"{payload.get('source_id', 'work')}_{scene_id}",
                episode_body=text.strip(),
                source=EpisodeType.text,
                source_description=payload.get("title", "Imported work corpus"),
                reference_time=timestamp,
                group_id=binding.graph_group_id,
            )
            added += 1
    finally:
        await _close_graphiti(graphiti)

    result = {
        "status": "ok",
        "mode": "ingest_work",
        "twin_id": twin_id,
        "group_id": binding.graph_group_id,
        "episodes_added": added,
    }
    manifest_path = _relationship_graph_manifest_path(root, twin_id)
    if manifest_path.exists():
        manifest = _load_relationship_graph_manifest(root, twin_id)
        anchor_binding = dict(manifest.get("anchor_node_binding") or {})
        anchor_binding["focal_node_uuid"] = binding.focal_node_uuid
        anchor_binding["binding_status"] = "graphiti_uuid_bound" if binding.focal_node_uuid else "entity_id_only"
        manifest["graph_group_id"] = binding.graph_group_id
        manifest["anchor_node_binding"] = anchor_binding
        _save_relationship_graph_manifest(root, twin_id, manifest)
        result["relationship_graph_manifest_path"] = str(manifest_path)
    return result


async def search_anchor_graph(
    *,
    service_root: str | None,
    twin_id: str,
    query: str,
    limit: int = 10,
    focal_node_uuid: str | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    resolved_focal_node_uuid = focal_node_uuid or binding.focal_node_uuid

    manifest_path = _relationship_graph_manifest_path(root, twin_id)
    manifest_payload = _load_relationship_graph_manifest(root, twin_id) if manifest_path.exists() else None
    graphiti = await _open_graphiti(root, twin_id)
    try:
        if manifest_payload is not None and not resolved_focal_node_uuid:
            anchor_binding = await _resolve_anchor_node_binding_with_graphiti(
                graphiti,
                group_id=binding.graph_group_id,
                anchor_person_id=binding.anchor_person_id,
                manifest_payload=manifest_payload,
                current_focal_node_uuid=binding.focal_node_uuid,
            )
            resolved_focal_node_uuid = str(anchor_binding.get("focal_node_uuid") or "").strip() or None
            if resolved_focal_node_uuid and binding.focal_node_uuid != resolved_focal_node_uuid:
                binding.focal_node_uuid = resolved_focal_node_uuid
                _save_binding(root, binding)
            if anchor_binding != (manifest_payload.get("anchor_node_binding") or {}):
                manifest_payload["anchor_node_binding"] = anchor_binding
                _save_relationship_graph_manifest(root, twin_id, manifest_payload)
        edges = await _search_graph(
            graphiti,
            query=query,
            group_id=binding.graph_group_id,
            focal_node_uuid=resolved_focal_node_uuid,
        )
    finally:
        await _close_graphiti(graphiti)

    hits = [_edge_to_dict(edge) for edge in list(edges or [])[:limit]]
    return {
        "status": "ok",
        "twin_id": twin_id,
        "group_id": binding.graph_group_id,
        "query": query,
        "focal_node_uuid": resolved_focal_node_uuid,
        "results": hits,
    }


async def export_draft_bundle(
    *,
    service_root: str | None,
    twin_id: str,
    query: str,
    limit: int = 10,
    focal_node_uuid: str | None = None,
    output_name: str | None = None,
) -> dict[str, Any]:
    root = _service_root(service_root)
    binding = _load_binding(root, twin_id)
    search_payload = await search_anchor_graph(
        service_root=service_root,
        twin_id=twin_id,
        query=query,
        limit=limit,
        focal_node_uuid=focal_node_uuid,
    )
    bundle = _project_drafts(
        service_root=root,
        binding=binding,
        query=query,
        hits=search_payload["results"],
    )
    export_name = output_name or f"{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}_drafts.json"
    path = _export_path(root, twin_id, export_name)
    _write_json(path, bundle)
    return {
        "status": "ok",
        "export_path": str(path),
        "relationship_drafts": len(bundle["relationship_drafts"]),
        "memory_drafts": len(bundle["memory_drafts"]),
        "bundle": bundle,
    }


async def run_request_envelope(
    *,
    request_payload: dict[str, Any],
    service_root_override: str | None = None,
) -> dict[str, Any]:
    operation = _require_string(request_payload, "operation")
    params = request_payload.get("params") or {}
    if not isinstance(params, dict):
        raise ServiceError("Request envelope 'params' must be an object.")
    service_root = service_root_override or params.get("service_root")

    if operation in {"init", "create_twin_binding"}:
        return await create_twin_binding(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            anchor_person_id=_require_string(params, "anchor_person_id"),
            interaction_mode=_require_string(params, "interaction_mode"),
            source_mode=_require_string(params, "source_mode"),
            current_user_role_binding=params.get("current_user_role_binding") if isinstance(params.get("current_user_role_binding"), dict) else None,
            binding_type=str(params.get("binding_type") or "real_user"),
            binding_entity_id=params.get("binding_entity_id"),
            focal_node_uuid=params.get("focal_node_uuid"),
            source_refs=_source_refs_from_payload(params),
        )
    if operation == "preflight_scan":
        return await preflight_scan(
            service_root=service_root,
            source_mode=_require_string(params, "source_mode"),
            source_refs=_source_refs_from_payload(params),
            anchor_person_id=params.get("anchor_person_id"),
        )
    if operation == "list_relationship_candidates":
        return await list_relationship_candidates(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            source_refs=_source_refs_from_payload(params),
        )
    if operation == "select_relationship":
        return await select_relationship(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            anchor_person_id=_require_string(params, "anchor_person_id"),
            counterpart_entity_id=_require_string(params, "counterpart_entity_id"),
            selection_source=str(params.get("selection_source") or "user_selected"),
            relationship_label=params.get("relationship_label"),
        )
    if operation == "rebind_relationship":
        to_counterpart_entity_id = params.get("to_counterpart_entity_id") or params.get("counterpart_entity_id")
        if not isinstance(to_counterpart_entity_id, str) or not to_counterpart_entity_id.strip():
            raise ServiceError("Expected non-empty string field 'to_counterpart_entity_id'.")
        return await rebind_relationship(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            anchor_person_id=_require_string(params, "anchor_person_id"),
            to_counterpart_entity_id=to_counterpart_entity_id.strip(),
            selection_source=str(params.get("selection_source") or "user_selected"),
            from_binding_id=params.get("from_binding_id"),
            binding_type=params.get("binding_type"),
            binding_entity_id=params.get("binding_entity_id"),
        )
    if operation == "ingest_chat":
        return await ingest_chat_file(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            source=_require_string(params, "source"),
            batch_size=int(params.get("batch_size") or 8),
        )
    if operation == "ingest_work":
        return await ingest_work_file(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            source=_require_string(params, "source"),
        )
    if operation == "search_anchor":
        return await search_anchor_graph(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            query=_require_string(params, "query"),
            limit=int(params.get("limit") or 10),
            focal_node_uuid=params.get("focal_node_uuid"),
        )
    if operation == "export_opencray_drafts":
        return await export_draft_bundle(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            query=_require_string(params, "query"),
            limit=int(params.get("limit") or 10),
            focal_node_uuid=params.get("focal_node_uuid"),
            output_name=params.get("output_name"),
        )

    raise ServiceError(f"Unsupported operation: {operation}")


async def cmd_init(args: argparse.Namespace) -> int:
    _print_json(
        await create_twin_binding(
            service_root=args.service_root,
            twin_id=args.twin_id,
            anchor_person_id=args.anchor_person_id,
            interaction_mode=args.interaction_mode,
            source_mode=args.source_mode,
            binding_type=args.binding_type,
            binding_entity_id=args.binding_entity_id,
            focal_node_uuid=args.focal_node_uuid,
            source_refs=args.source_refs,
        )
    )
    return 0


async def cmd_preflight_scan(args: argparse.Namespace) -> int:
    _print_json(
        await preflight_scan(
            service_root=args.service_root,
            source_mode=args.source_mode,
            source_refs=args.source_refs,
            anchor_person_id=args.anchor_person_id,
        )
    )
    return 0


async def cmd_create_twin_binding(args: argparse.Namespace) -> int:
    return await cmd_init(args)


async def cmd_list_relationship_candidates(args: argparse.Namespace) -> int:
    _print_json(
        await list_relationship_candidates(
            service_root=args.service_root,
            twin_id=args.twin_id,
            source_refs=args.source_refs,
        )
    )
    return 0


async def cmd_select_relationship(args: argparse.Namespace) -> int:
    _print_json(
        await select_relationship(
            service_root=args.service_root,
            twin_id=args.twin_id,
            anchor_person_id=args.anchor_person_id,
            counterpart_entity_id=args.counterpart_entity_id,
            selection_source=args.selection_source,
            relationship_label=args.relationship_label,
        )
    )
    return 0


async def cmd_rebind_relationship(args: argparse.Namespace) -> int:
    _print_json(
        await rebind_relationship(
            service_root=args.service_root,
            twin_id=args.twin_id,
            anchor_person_id=args.anchor_person_id,
            to_counterpart_entity_id=args.to_counterpart_entity_id,
            selection_source=args.selection_source,
            from_binding_id=args.from_binding_id,
            binding_type=args.binding_type,
            binding_entity_id=args.binding_entity_id,
        )
    )
    return 0


async def cmd_ingest_chat(args: argparse.Namespace) -> int:
    _print_json(
        await ingest_chat_file(
            service_root=args.service_root,
            twin_id=args.twin_id,
            source=args.source,
            batch_size=args.batch_size,
        )
    )
    return 0


async def cmd_ingest_work(args: argparse.Namespace) -> int:
    _print_json(
        await ingest_work_file(
            service_root=args.service_root,
            twin_id=args.twin_id,
            source=args.source,
        )
    )
    return 0


async def _run_search(args: argparse.Namespace) -> dict[str, Any]:
    return await search_anchor_graph(
        service_root=args.service_root,
        twin_id=args.twin_id,
        query=args.query,
        limit=args.limit,
        focal_node_uuid=args.focal_node_uuid,
    )


async def cmd_search_anchor(args: argparse.Namespace) -> int:
    _print_json(await _run_search(args))
    return 0


async def cmd_export_opencray_drafts(args: argparse.Namespace) -> int:
    result = await export_draft_bundle(
        service_root=args.service_root,
        twin_id=args.twin_id,
        query=args.query,
        limit=args.limit,
        focal_node_uuid=args.focal_node_uuid,
        output_name=args.output_name,
    )
    _print_json(
        {
            "status": result["status"],
            "export_path": result["export_path"],
            "relationship_drafts": result["relationship_drafts"],
            "memory_drafts": result["memory_drafts"],
        }
    )
    return 0


async def cmd_run_request(args: argparse.Namespace) -> int:
    payload = _load_json(Path(args.request))
    if not isinstance(payload, dict):
        raise ServiceError("Request envelope JSON must be an object.")
    result = await run_request_envelope(
        request_payload=payload,
        service_root_override=args.service_root,
    )
    if args.response:
        _write_json(Path(args.response), result)
    else:
        _print_json(result)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="graphiti_adapter",
        description="Standalone Graphiti adapter for digital-twin corpus import.",
    )
    parser.add_argument(
        "--service-root",
        default=None,
        help="Override the service root directory. Defaults to .opencray/personality_service.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    init_p = sub.add_parser("init", help="Create or update a twin binding.")
    init_p.add_argument("--twin-id", required=True)
    init_p.add_argument("--anchor-person-id", required=True)
    init_p.add_argument(
        "--interaction-mode",
        choices=("chat_twin", "work_role"),
        required=True,
    )
    init_p.add_argument(
        "--source-mode",
        choices=("chat_history", "fiction_work"),
        required=True,
    )
    init_p.add_argument(
        "--binding-type",
        choices=("real_user", "fictional_character"),
        default="real_user",
    )
    init_p.add_argument("--binding-entity-id", required=True)
    init_p.add_argument("--focal-node-uuid")
    init_p.add_argument("--source-refs", nargs="*")
    init_p.set_defaults(func=cmd_init)

    preflight_p = sub.add_parser("preflight_scan", help="Scan corpus files and emit anchor/counterpart selector candidates.")
    preflight_p.add_argument("--source-mode", choices=("chat_history", "fiction_work"), required=True)
    preflight_p.add_argument("--source-refs", nargs="+", required=True)
    preflight_p.add_argument("--anchor-person-id")
    preflight_p.set_defaults(func=cmd_preflight_scan)

    create_binding_p = sub.add_parser("create_twin_binding", help="Create a twin binding and import session for selector-driven import.")
    create_binding_p.add_argument("--twin-id", required=True)
    create_binding_p.add_argument("--anchor-person-id", required=True)
    create_binding_p.add_argument("--interaction-mode", choices=("chat_twin", "work_role"), required=True)
    create_binding_p.add_argument("--source-mode", choices=("chat_history", "fiction_work"), required=True)
    create_binding_p.add_argument("--binding-type", choices=("real_user", "fictional_character"), default="real_user")
    create_binding_p.add_argument("--binding-entity-id", required=True)
    create_binding_p.add_argument("--focal-node-uuid")
    create_binding_p.add_argument("--source-refs", nargs="*")
    create_binding_p.set_defaults(func=cmd_create_twin_binding)

    list_candidates_p = sub.add_parser("list_relationship_candidates", help="List anchor-centered relationship candidates for one twin binding.")
    list_candidates_p.add_argument("--twin-id", required=True)
    list_candidates_p.add_argument("--source-refs", nargs="*")
    list_candidates_p.set_defaults(func=cmd_list_relationship_candidates)

    select_p = sub.add_parser("select_relationship", help="Persist one selected relationship binding for the twin.")
    select_p.add_argument("--twin-id", required=True)
    select_p.add_argument("--anchor-person-id", required=True)
    select_p.add_argument("--counterpart-entity-id", required=True)
    select_p.add_argument("--selection-source", default="user_selected")
    select_p.add_argument("--relationship-label")
    select_p.set_defaults(func=cmd_select_relationship)

    rebind_p = sub.add_parser("rebind_relationship", help="Rebind the twin to another counterpart and supersede the previous active relationship lens.")
    rebind_p.add_argument("--twin-id", required=True)
    rebind_p.add_argument("--anchor-person-id", required=True)
    rebind_p.add_argument("--to-counterpart-entity-id", required=True)
    rebind_p.add_argument("--selection-source", default="user_selected")
    rebind_p.add_argument("--from-binding-id")
    rebind_p.add_argument("--binding-type", choices=("real_user", "fictional_character"))
    rebind_p.add_argument("--binding-entity-id")
    rebind_p.set_defaults(func=cmd_rebind_relationship)

    ingest_chat_p = sub.add_parser("ingest_chat", help="Ingest a normalized chat corpus.")
    ingest_chat_p.add_argument("--twin-id", required=True)
    ingest_chat_p.add_argument("--source", required=True)
    ingest_chat_p.add_argument("--batch-size", type=int, default=8)
    ingest_chat_p.set_defaults(func=cmd_ingest_chat)

    ingest_work_p = sub.add_parser("ingest_work", help="Ingest a normalized work corpus.")
    ingest_work_p.add_argument("--twin-id", required=True)
    ingest_work_p.add_argument("--source", required=True)
    ingest_work_p.set_defaults(func=cmd_ingest_work)

    search_p = sub.add_parser("search_anchor", help="Search the graph in the anchor namespace.")
    search_p.add_argument("--twin-id", required=True)
    search_p.add_argument("--query", required=True)
    search_p.add_argument("--limit", type=int, default=10)
    search_p.add_argument("--focal-node-uuid")
    search_p.set_defaults(func=cmd_search_anchor)

    export_p = sub.add_parser(
        "export_opencray_drafts",
        help="Search and project graph hits into a draft bundle for OpenCray review.",
    )
    export_p.add_argument("--twin-id", required=True)
    export_p.add_argument("--query", required=True)
    export_p.add_argument("--limit", type=int, default=10)
    export_p.add_argument("--focal-node-uuid")
    export_p.add_argument("--output-name")
    export_p.set_defaults(func=cmd_export_opencray_drafts)

    request_p = sub.add_parser(
        "run_request",
        help="Execute one request envelope for local file-bridge integration.",
    )
    request_p.add_argument("--request", required=True)
    request_p.add_argument("--response")
    request_p.set_defaults(func=cmd_run_request)

    return parser


async def async_main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return await args.func(args)


def main(argv: list[str] | None = None) -> int:
    try:
        return asyncio.run(async_main(list(sys.argv[1:] if argv is None else argv)))
    except ServiceError as exc:
        print(
            json.dumps(
                {
                    "status": "error",
                    "error": str(exc),
                },
                ensure_ascii=False,
                indent=2,
            ),
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())


