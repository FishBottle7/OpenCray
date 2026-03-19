from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

DEFAULT_SERVICE_ROOT = Path(".opencray") / "personality_service"
DEFAULT_SOUL_DIR = "soul"

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
SENTENCE_SPLIT_RE = re.compile(r"[。！？!?\.]+")
ELLIPSIS_RE = re.compile(r"\.\.\.|…")
EMOJI_CHARS = set("😂🤣😊🙂😅😭🥲😡❤❤️💕💔👍🙏✨😔🥹😌🙃😢🤔😴😘😳😞😩😤😆😁😍😉🥺")

SCENE_KEYWORDS: dict[str, tuple[str, ...]] = {
    "conflict": ("不是在生气", "很累", "失望", "答应", "别再", "不要这样", "angry", "tired", "broken promise", "disappointed"),
    "repair": ("对不起", "抱歉", "下次", "以后", "补上", "sorry", "make it up", "apologize", "repair"),
    "boundary": ("不要", "别", "不行", "不能", "算了", "今天先这样", "stop", "don't", "can't", "need space"),
    "comfort": ("没事", "别怕", "注意安全", "休息", "take care", "it's okay", "rest"),
    "uncertainty": ("可能", "也许", "不知道", "不确定", "maybe", "not sure", "I think"),
    "self_disclosure": ("我觉得", "我不是", "我有点", "我只是", "I feel", "I am", "I'm just"),
    "playfulness": ("哈哈", "hhh", "lol", "开玩笑", "jk"),
    "planning": ("明天", "周末", "安排", "plan", "tomorrow", "schedule"),
}

SPEECH_ACT_KEYWORDS: dict[str, tuple[str, ...]] = {
    "apologize": ("对不起", "抱歉", "sorry", "apologize"),
    "reassure": ("没事", "别怕", "会好的", "it's okay", "take care"),
    "advise": ("你要", "最好", "建议", "you should", "try to"),
    "promise": ("我会", "答应", "下次会", "I will", "promise"),
    "boundary_set": ("不要", "别", "不行", "不能", "今天先这样", "stop", "don't", "can't"),
    "confess": ("我觉得", "我其实", "I feel", "to be honest"),
    "joke": ("哈哈", "lol", "开玩笑", "jk"),
}

VALUE_KEYWORDS: dict[str, tuple[str, ...]] = {
    "truth": ("说实话", "坦白", "诚实", "honest", "truth"),
    "care": ("照顾", "注意安全", "关心", "take care", "rest", "care"),
    "autonomy": ("自己", "空间", "边界", "need space", "my own", "independent"),
    "fairness": ("答应", "公平", "说到做到", "promise", "fair", "keep your word"),
    "harmony": ("别吵", "算了", "和气", "keep the peace", "avoid drama"),
    "security": ("稳定", "安全", "稳妥", "safe", "stable"),
    "achievement": ("做到", "完成", "目标", "finish", "goal"),
    "power": ("控制", "必须听", "dominate", "control"),
    "novelty": ("新鲜", "刺激", "try something new", "novel"),
}

SOFTENER_KEYWORDS = ("只是", "有点", "可能", "先", "please", "maybe", "a bit", "just")
DIRECTIVE_KEYWORDS = ("要", "必须", "不要", "别", "should", "need to", "must", "don't")
EXPLANATION_KEYWORDS = ("因为", "只是", "不是", "所以", "because", "just", "not", "only")
WITHDRAW_KEYWORDS = ("累", "算了", "先这样", "改天", "tired", "leave it", "later", "need space")
INTIMACY_KEYWORDS = ("宝贝", "亲爱的", "想你", "love", "miss you", "baby", "dear")
TEASING_KEYWORDS = ("笨", "傻", "笑死", "dummy", "idiot", "tease")
AGGRESSIVE_KEYWORDS = ("闭嘴", "滚", "恶心", "太过分", "不想理你", "别来找我", "shut up", "disgusting", "hate you", "leave me alone", "go away")
THERAPY_STYLE_KEYWORDS = ("允许自己", "接纳", "疗愈", "hold space", "process your emotions", "healing")
SALESY_KEYWORDS = ("你一定可以", "你值得最好的", "you got this", "best version of yourself")
FORMAL_AI_KEYWORDS = ("根据您的描述", "建议您", "作为", "I understand your feelings and suggest", "based on your description")
ABSOLUTE_KEYWORDS = ("永远", "从来", "再也不", "绝不", "always", "never", "forever", "every time")
WARMTH_KEYWORDS = ("辛苦", "照顾", "关心", "没事", "抱抱", "注意安全", "休息", "想你", "love", "miss you", "take care", "rest", "sorry")
PRESSURE_KEYWORDS = ("必须", "立刻", "马上", "现在就", "一定要", "must", "right now", "immediately")
ACCUSATION_KEYWORDS = ("你总是", "都是你", "你的问题", "your fault", "you always", "you never")
REPAIR_OFFER_KEYWORDS = ("以后", "下次", "补上", "回来", "再聊", "next time", "make it up", "later", "reach out again")
SECOND_PERSON_KEYWORDS = ("你", "你们", "you", "your", "yours")
THIRD_PARTY_REFERENCE_KEYWORDS = ("他", "她", "他们", "她们", "她的", "他的", "she", "he", "they", "them", "her", "his")


class ServiceError(RuntimeError):
    pass


@dataclass(slots=True)
class EvidenceUnit:
    unit_id: str
    text: str
    speaker: str
    speaker_id: str
    source_id: str
    source_type: str
    timestamp: str | None
    labels: list[str]
    source_weight: float
    turn_index: int = 0
    conversation_id: str | None = None
    reply_to_turn_id: str | None = None
    reply_link_inferred: bool = False
    addressed_to: list[str] | None = None
    quoted_speech: list[str] | None = None
    counterpart_id: str | None = None
    counterpart_name: str | None = None


@dataclass(slots=True)
class JudgeSelectorContext:
    selected_relationship_binding_id: str | None
    counterpart_entity_id: str | None
    overlay_key: str | None
    relationship_state_hints: dict[str, Any]
    interaction_preference_hints: dict[str, Any]
    recent_script_hints: list[str]
    context_quality: str
    degraded_context_reason: str | None


def _now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _service_root(path: str | None) -> Path:
    return Path(path) if path else DEFAULT_SERVICE_ROOT


def _soul_dir(service_root: Path, twin_id: str) -> Path:
    return service_root / DEFAULT_SOUL_DIR / twin_id


def _ensure_service_dirs(service_root: Path, twin_id: str) -> None:
    _soul_dir(service_root, twin_id).mkdir(parents=True, exist_ok=True)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ServiceError(f"JSON file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ServiceError(f"Invalid JSON in {path}: {exc}") from exc


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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


def _clip(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return max(minimum, min(maximum, value))


def _count_markers(text: str, markers: tuple[str, ...]) -> int:
    lower = text.lower()
    total = 0
    for marker in markers:
        if CJK_RE.search(marker):
            total += text.count(marker)
        else:
            total += lower.count(marker.lower())
    return total


def _parse_timestamp(value: str | None) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _seconds_between(left: str | None, right: str | None) -> float | None:
    left_dt = _parse_timestamp(left)
    right_dt = _parse_timestamp(right)
    if left_dt is None or right_dt is None:
        return None
    return abs((left_dt - right_dt).total_seconds())


def _day_key(timestamp: str | None) -> str | None:
    parsed = _parse_timestamp(timestamp)
    return parsed.date().isoformat() if parsed else None


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    if isinstance(value, list):
        cleaned: list[str] = []
        for item in value:
            text = str(item).strip()
            if text:
                cleaned.append(text)
        return cleaned
    return []


def _participant_lookup(participants: list[dict[str, Any]]) -> tuple[dict[str, str], dict[str, str]]:
    token_to_id: dict[str, str] = {}
    id_to_name: dict[str, str] = {}
    for participant in participants:
        entity_id = str(participant.get("entity_id") or participant.get("display_name") or "").strip()
        if not entity_id:
            continue
        display_name = str(participant.get("display_name") or entity_id).strip()
        id_to_name[entity_id] = display_name
        token_to_id[entity_id] = entity_id
        token_to_id[display_name] = entity_id
    return token_to_id, id_to_name


def _normalize_addressed_to(value: Any, token_to_id: dict[str, str]) -> list[str]:
    return [token_to_id.get(item, item) for item in _string_list(value)]


def _infer_reply_target(units: list[EvidenceUnit], index: int) -> tuple[str | None, bool]:
    unit = units[index]
    if unit.reply_to_turn_id:
        return unit.reply_to_turn_id, False
    for previous in reversed(units[:index]):
        if previous.speaker_id == unit.speaker_id:
            continue
        if unit.conversation_id and previous.conversation_id and unit.conversation_id != previous.conversation_id:
            continue
        if unit.addressed_to and previous.speaker_id not in unit.addressed_to and previous.speaker not in unit.addressed_to:
            continue
        gap = _seconds_between(unit.timestamp, previous.timestamp)
        if gap is not None and gap > 300:
            if unit.timestamp and previous.timestamp:
                break
            continue
        return previous.unit_id, True
    return None, False


def _nearest_counterpart(units: list[EvidenceUnit], index: int) -> EvidenceUnit | None:
    unit = units[index]
    for previous in reversed(units[:index]):
        if previous.speaker_id == unit.speaker_id:
            continue
        if unit.conversation_id and previous.conversation_id and unit.conversation_id != previous.conversation_id:
            continue
        gap = _seconds_between(unit.timestamp, previous.timestamp)
        if gap is not None and gap > 900:
            if unit.timestamp and previous.timestamp:
                break
            continue
        return previous
    return None


def _infer_counterpart(unit: EvidenceUnit, by_id: dict[str, EvidenceUnit], fallback_id: str | None, fallback_name: str | None) -> tuple[str | None, str | None]:
    if unit.counterpart_id:
        return unit.counterpart_id, unit.counterpart_name
    if unit.reply_to_turn_id and unit.reply_to_turn_id in by_id:
        reply_unit = by_id[unit.reply_to_turn_id]
        if reply_unit.speaker_id != unit.speaker_id:
            return reply_unit.speaker_id, reply_unit.speaker
    for addressed in unit.addressed_to or []:
        if addressed != unit.speaker_id:
            if addressed in by_id:
                return addressed, by_id[addressed].speaker
            return addressed, fallback_name if fallback_id == addressed else addressed
    if fallback_id and fallback_id != unit.speaker_id:
        return fallback_id, fallback_name or fallback_id
    return None, None


def _link_chat_units(units: list[EvidenceUnit], fallback_counterparts: dict[str, tuple[str | None, str | None]]) -> None:
    by_id: dict[str, EvidenceUnit] = {unit.unit_id: unit for unit in units}
    for index, unit in enumerate(units):
        reply_to_turn_id, inferred = _infer_reply_target(units, index)
        if reply_to_turn_id:
            unit.reply_to_turn_id = reply_to_turn_id
            unit.reply_link_inferred = inferred
    for index, unit in enumerate(units):
        fallback_id, fallback_name = fallback_counterparts.get(unit.speaker_id, (None, None))
        counterpart_id, counterpart_name = _infer_counterpart(unit, by_id, fallback_id, fallback_name)
        if counterpart_id is None:
            nearest = _nearest_counterpart(units, index)
            if nearest is not None:
                counterpart_id = nearest.speaker_id
                counterpart_name = nearest.speaker
        unit.counterpart_id = counterpart_id
        unit.counterpart_name = counterpart_name


def _reply_unit(units: list[EvidenceUnit], index: int) -> EvidenceUnit | None:
    unit = units[index]
    if unit.reply_to_turn_id:
        for candidate in units:
            if candidate.unit_id == unit.reply_to_turn_id:
                return candidate
    return _nearest_counterpart(units, index)


def _next_counterpart_unit(units: list[EvidenceUnit], index: int) -> EvidenceUnit | None:
    unit = units[index]
    for candidate in units[index + 1:]:
        if candidate.speaker_id == unit.speaker_id:
            continue
        if unit.conversation_id and candidate.conversation_id and unit.conversation_id != candidate.conversation_id:
            continue
        gap = _seconds_between(candidate.timestamp, unit.timestamp)
        if gap is not None and gap > 900:
            if candidate.timestamp and unit.timestamp:
                break
            continue
        return candidate
    return None


def _make_source_ref(unit: EvidenceUnit) -> dict[str, Any]:
    source_ref: dict[str, Any] = {
        "source_id": unit.source_id,
        "turn_id": unit.unit_id,
        "source_type": unit.source_type,
    }
    if unit.timestamp:
        source_ref["timestamp"] = unit.timestamp
    if unit.reply_to_turn_id:
        source_ref["reply_to_turn_id"] = unit.reply_to_turn_id
    if unit.conversation_id:
        source_ref["conversation_id"] = unit.conversation_id
    if unit.counterpart_id:
        source_ref["counterpart_id"] = unit.counterpart_id
    if unit.counterpart_name:
        source_ref["counterpart_name"] = unit.counterpart_name
    if unit.reply_link_inferred:
        source_ref["reply_link_inferred"] = True
    return source_ref


def _resolve_anchor_from_chat(payload: dict[str, Any], anchor_person_id: str | None) -> tuple[str, str, int]:
    participants = payload.get("participants") or []
    if anchor_person_id:
        for participant in participants:
            if participant.get("entity_id") == anchor_person_id or participant.get("display_name") == anchor_person_id:
                return str(participant.get("entity_id") or anchor_person_id), str(participant.get("display_name") or anchor_person_id), len(participants)
        return anchor_person_id, anchor_person_id, len(participants)
    for participant in participants:
        if participant.get("role") == "anchor":
            return str(participant.get("entity_id") or participant.get("display_name") or "anchor"), str(participant.get("display_name") or participant.get("entity_id") or "anchor"), len(participants)
    turns = payload.get("turns") or []
    if turns:
        first = turns[0]
        return str(first.get("speaker_id") or first.get("speaker") or "anchor"), str(first.get("speaker") or first.get("speaker_id") or "anchor"), len(participants) or 2
    raise ServiceError("Unable to resolve anchor person from chat corpus.")


def _resolve_anchor_from_work(payload: dict[str, Any], anchor_person_id: str | None) -> tuple[str, str]:
    characters = payload.get("characters") or []
    if anchor_person_id:
        for character in characters:
            if character.get("entity_id") == anchor_person_id or character.get("display_name") == anchor_person_id:
                return str(character.get("entity_id") or anchor_person_id), str(character.get("display_name") or anchor_person_id)
        return anchor_person_id, anchor_person_id
    for character in characters:
        if character.get("role") == "anchor":
            return str(character.get("entity_id") or character.get("display_name") or "anchor"), str(character.get("display_name") or character.get("entity_id") or "anchor")
    raise ServiceError("Unable to resolve anchor person from work corpus.")


def _chat_units(payload: dict[str, Any], anchor_person_id: str | None) -> tuple[str, str, int, list[EvidenceUnit]]:
    anchor_id, anchor_name, participants_count = _resolve_anchor_from_chat(payload, anchor_person_id)
    participants = [participant for participant in (payload.get("participants") or []) if isinstance(participant, dict)]
    token_to_id, id_to_name = _participant_lookup(participants)
    fallback_counterparts: dict[str, tuple[str | None, str | None]] = {}
    if participants_count == 2:
        participant_ids = [str(participant.get("entity_id") or participant.get("display_name") or "").strip() for participant in participants]
        participant_ids = [item for item in participant_ids if item]
        if len(participant_ids) == 2:
            left, right = participant_ids
            fallback_counterparts[left] = (right, id_to_name.get(right, right))
            fallback_counterparts[right] = (left, id_to_name.get(left, left))

    turns = _require_list(payload, "turns")
    units: list[EvidenceUnit] = []
    default_conversation_id = str(payload.get("conversation_id") or payload.get("source_id") or "").strip() or None
    for turn in turns:
        if not isinstance(turn, dict):
            raise ServiceError("Each chat turn must be an object.")
        speaker_id = str(turn.get("speaker_id") or turn.get("speaker") or "Unknown")
        units.append(
            EvidenceUnit(
                unit_id=str(turn.get("turn_id") or f"turn_{len(units) + 1:04d}"),
                text=_require_string(turn, "text"),
                speaker=str(turn.get("speaker") or turn.get("speaker_id") or "Unknown"),
                speaker_id=speaker_id,
                source_id=_require_string(payload, "source_id"),
                source_type="private_chat",
                timestamp=turn.get("timestamp"),
                labels=[str(label) for label in (turn.get("labels") or [])],
                source_weight=1.0,
                turn_index=len(units),
                conversation_id=str(turn.get("conversation_id") or default_conversation_id or "").strip() or None,
                reply_to_turn_id=str(turn.get("reply_to_turn_id") or "").strip() or None,
                addressed_to=_normalize_addressed_to(turn.get("addressed_to"), token_to_id),
                quoted_speech=_string_list(turn.get("quoted_speech")),
            )
        )
    _link_chat_units(units, fallback_counterparts)
    return anchor_id, anchor_name, participants_count, units


def _work_units(payload: dict[str, Any], anchor_person_id: str | None) -> tuple[str, str, list[EvidenceUnit]]:
    anchor_id, anchor_name = _resolve_anchor_from_work(payload, anchor_person_id)
    characters = [character for character in (payload.get("characters") or []) if isinstance(character, dict)]
    counterpart_id: str | None = None
    counterpart_name: str | None = None
    for character in characters:
        entity_id = str(character.get("entity_id") or character.get("display_name") or "").strip()
        if entity_id and entity_id != anchor_id:
            counterpart_id = entity_id
            counterpart_name = str(character.get("display_name") or entity_id)
            break

    scenes = _require_list(payload, "scenes")
    units: list[EvidenceUnit] = []
    conversation_id = str(payload.get("work_id") or payload.get("source_id") or "").strip() or None
    for scene in scenes:
        if not isinstance(scene, dict):
            raise ServiceError("Each scene entry must be an object.")
        units.append(
            EvidenceUnit(
                unit_id=str(scene.get("scene_id") or f"scene_{len(units) + 1:04d}"),
                text=_require_string(scene, "text"),
                speaker=anchor_name,
                speaker_id=anchor_id,
                source_id=_require_string(payload, "source_id"),
                source_type="fiction_work",
                timestamp=scene.get("timestamp"),
                labels=[str(label) for label in (scene.get("labels") or [])],
                source_weight=0.35,
                turn_index=len(units),
                conversation_id=conversation_id,
                addressed_to=[counterpart_id] if counterpart_id else [],
                counterpart_id=counterpart_id,
                counterpart_name=counterpart_name,
            )
        )
    return anchor_id, anchor_name, units

def _infer_language(text: str) -> str:
    return "zh-CN" if CJK_RE.search(text) else "en-US"


def _infer_scene_labels(text: str, explicit_labels: list[str]) -> list[str]:
    labels = {label.replace("repair_attempt", "repair") for label in explicit_labels}
    for scene, markers in SCENE_KEYWORDS.items():
        if _count_markers(text, markers) > 0:
            labels.add(scene)
    return sorted(labels)


def _sentence_length_bucket(text: str) -> str:
    segments = [segment.strip() for segment in SENTENCE_SPLIT_RE.split(text) if segment.strip()]
    if not segments:
        return "short"
    average_length = sum(len(segment) for segment in segments) / len(segments)
    if average_length <= 16:
        return "short"
    if average_length <= 40:
        return "short_medium"
    if average_length <= 90:
        return "medium"
    return "long"


def _punctuation_style(text: str) -> str:
    punctuation_count = sum(text.count(ch) for ch in "，。！？,.!?;") + len(ELLIPSIS_RE.findall(text)) * 2
    density = punctuation_count / max(len(text), 1)
    return "heavy" if density >= 0.1 else "light"


def _emoji_density(text: str) -> str:
    emoji_count = sum(1 for ch in text if ch in EMOJI_CHARS)
    if emoji_count == 0:
        return "low"
    if emoji_count >= 3:
        return "high"
    return "medium"


def _directness_level(text: str) -> str:
    direct = _count_markers(text, DIRECTIVE_KEYWORDS)
    soft = _count_markers(text, SOFTENER_KEYWORDS)
    if direct and soft:
        return "softened_direct"
    if direct:
        return "direct"
    if soft:
        return "indirect"
    return "balanced"


def _extract_signature_patterns(texts: list[str]) -> list[str]:
    patterns: list[str] = []
    if any("不是" in text and ("只是" in text or "而是" in text or "我是" in text) for text in texts):
        patterns.append("不是...是...")
    if any("not" in text.lower() and "just" in text.lower() for text in texts):
        patterns.append("not...just...")

    prefix_counter: Counter[str] = Counter()
    for text in texts:
        stripped = text.strip()
        if not stripped:
            continue
        prefix = stripped[:6] if CJK_RE.search(stripped) else " ".join(stripped.split()[:2]).lower()
        if len(prefix) >= 2:
            prefix_counter[prefix] += 1

    for prefix, count in prefix_counter.most_common(3):
        if count >= 2 and prefix not in patterns:
            patterns.append(prefix)
    return patterns[:3]


def _infer_speech_acts(text: str, scene_labels: list[str]) -> tuple[list[str], str]:
    acts: list[str] = []
    for act, markers in SPEECH_ACT_KEYWORDS.items():
        if _count_markers(text, markers) > 0:
            acts.append(act)
    if ("boundary" in scene_labels or "conflict" in scene_labels) and "boundary_set" not in acts:
        acts.append("boundary_set")
    if re.search(r"[?？]", text):
        acts.append("request")
    if not acts:
        acts.append("confess" if "self_disclosure" in scene_labels else "inform")

    priority = ["apologize", "boundary_set", "reassure", "advise", "promise", "request", "confess", "joke", "inform"]
    unique_acts = list(dict.fromkeys(acts))
    ordered = sorted(unique_acts, key=lambda act: priority.index(act) if act in priority else len(priority))
    return ordered, ordered[0]


def _split_clauses(text: str) -> list[str]:
    return [part.strip() for part in re.split(r"[，,；;。！？!?]", text) if part.strip()]


def _ordered_unique(values: list[str]) -> list[str]:
    ordered: list[str] = []
    for value in values:
        if value and value not in ordered:
            ordered.append(value)
    return ordered


def _clause_move(clause: str, scene_labels: list[str]) -> str | None:
    stripped = clause.strip()
    if not stripped:
        return None
    if _count_markers(stripped, ("对不起", "抱歉", "sorry")) > 0:
        return "direct_apology"
    if any(stripped.startswith(prefix) for prefix in ("我觉得", "我不是", "我只是", "I feel", "I'm", "I am")):
        return "clarify_feeling"
    if _count_markers(stripped, ("我不是", "我只是", "I feel", "I am", "I'm")) > 0 and _count_markers(stripped, EXPLANATION_KEYWORDS) > 0:
        return "clarify_feeling"
    if _count_markers(stripped, ("不要", "别", "stop", "don't", "不行", "不能")) > 0:
        return "state_limit"
    if _count_markers(stripped, WITHDRAW_KEYWORDS) > 0:
        return "withdraw"
    if _count_markers(stripped, ("回来", "再聊", "later", "reach out again")) > 0:
        return "resume_contact"
    if _count_markers(stripped, ("以后", "下次", "补上", "next time", "make it up")) > 0:
        return "practical_repair"
    if _count_markers(stripped, ("注意安全", "休息", "没事", "take care", "rest", "it's okay")) > 0:
        return "light_reassurance"
    if re.search(r"[?？]", stripped):
        return "seek_alignment"
    if _count_markers(stripped, EXPLANATION_KEYWORDS) > 0:
        return "measured_explanation"
    if "repair" in scene_labels and _count_markers(stripped, ("我会", "答应", "I will", "promise")) > 0:
        return "practical_repair"
    return None


def _extract_clause_moves(text: str, scene_labels: list[str]) -> list[str]:
    moves = [_clause_move(clause, scene_labels) for clause in _split_clauses(text)]
    ordered = _ordered_unique([move for move in moves if move])
    if ordered:
        return ordered
    response_habit = _infer_response_habit(text)
    if response_habit == "feeling_first_then_reason":
        return ["clarify_feeling"]
    if "repair" in scene_labels:
        return ["practical_repair"]
    if "comfort" in scene_labels:
        return ["light_reassurance"]
    if "conflict" in scene_labels or "boundary" in scene_labels:
        return ["measured_explanation"]
    return []


def _infer_external_move(text: str, scene_labels: list[str]) -> str:
    acts, primary_act = _infer_speech_acts(text, scene_labels)
    if primary_act == "apologize" or _count_markers(text, REPAIR_OFFER_KEYWORDS) > 0:
        return "repair_offer"
    if _count_markers(text, ACCUSATION_KEYWORDS) > 0:
        return "pressure_or_accusation"
    if _count_markers(text, ("别生气", "不要生气", "don't be mad", "别走", "stay")) > 0:
        return "reassurance_request"
    if primary_act == "reassure":
        return "comfort_bid"
    if primary_act == "boundary_set" and _count_markers(text, PRESSURE_KEYWORDS) > 0:
        return "pressure_or_accusation"
    if re.search(r"[?？]", text):
        return "clarification_request"
    if _count_markers(text, ("好", "嗯", "知道了", "okay", "alright", "got it")) > 0:
        return "accepts_boundary"
    if acts:
        return primary_act
    return "neutral_reply"


def _infer_event_semantics(text: str, scene_labels: list[str], prev_text: str | None = None, next_text: str | None = None) -> dict[str, Any]:
    combined = " ".join(item for item in (prev_text, text, next_text) if item)
    anchor_moves = _extract_clause_moves(text, scene_labels)
    prev_labels = _infer_scene_labels(prev_text, []) if prev_text else []
    next_labels = _infer_scene_labels(next_text, []) if next_text else []
    prev_move = _infer_external_move(prev_text, prev_labels) if prev_text else "self_initiated"
    next_move = _infer_external_move(next_text, next_labels) if next_text else "no_external_follow_up"

    if _count_markers(combined, ("答应", "promise", "没做到", "again", "又", "还是")) > 0:
        event_type = "broken_expectation"
    elif "repair" in scene_labels or prev_move == "repair_offer":
        event_type = "repair_negotiation"
    elif "comfort" in scene_labels or _count_markers(combined, ("注意安全", "休息", "take care", "rest")) > 0:
        event_type = "care_checkin"
    elif "boundary" in scene_labels or "conflict" in scene_labels or "state_limit" in anchor_moves:
        event_type = "boundary_protection"
    elif "uncertainty" in scene_labels:
        event_type = "uncertainty_disclosure"
    else:
        event_type = "plain_exchange"

    trigger_source = "self_state"
    if prev_move in {"repair_offer", "pressure_or_accusation", "reassurance_request", "clarification_request", "boundary_push"}:
        trigger_source = "other_move"
    elif prev_move == "comfort_bid":
        trigger_source = "other_care"
    elif _count_markers(combined, ("答应", "promise", "again", "又", "还是")) > 0:
        trigger_source = "broken_expectation"

    target_focus = "clarity"
    if any(move in anchor_moves for move in ("state_limit", "withdraw")):
        target_focus = "relationship_boundary"
    elif any(move in anchor_moves for move in ("practical_repair", "direct_apology", "resume_contact")):
        target_focus = "repair"
    elif "light_reassurance" in anchor_moves:
        target_focus = "bond_maintenance"
    elif _count_markers(text, ("累", "休息", "rest", "take care", "later")) > 0:
        target_focus = "self_regulation"

    anticipated_outcome = "ongoing_tension"
    if "resume_contact" in anchor_moves or next_move in {"repair_offer", "accepts_boundary"}:
        anticipated_outcome = "returns_after_cooldown"
    elif "withdraw" in anchor_moves:
        anticipated_outcome = "pause_after_boundary"
    elif "light_reassurance" in anchor_moves:
        anticipated_outcome = "softened_settlement"

    return {
        "event_type": event_type,
        "trigger_source": trigger_source,
        "target_focus": target_focus,
        "opening_context": prev_move,
        "anticipated_outcome": anticipated_outcome,
        "anchor_moves": anchor_moves,
    }


def _infer_appraisal_hint(text: str, scene_labels: list[str], prev_text: str | None = None) -> dict[str, Any]:
    combined = " ".join(item for item in (prev_text, text) if item)
    prev_labels = _infer_scene_labels(prev_text, []) if prev_text else []
    prev_move = _infer_external_move(prev_text, prev_labels) if prev_text else "self_initiated"
    clause_moves = _extract_clause_moves(text, scene_labels)

    if _count_markers(text, ("累", "很累", "tired", "need space")) > 0:
        self_state = "tired_overloaded"
    elif _count_markers(text, ("失望", "难过", "hurt", "disappointed")) > 0:
        self_state = "hurt_disappointed"
    elif "uncertainty" in scene_labels:
        self_state = "uncertain"
    elif _count_markers(text, ("注意安全", "休息", "take care", "rest")) > 0:
        self_state = "caring"
    else:
        self_state = "steady"

    if _count_markers(combined, ("答应", "promise", "没做到", "again", "又", "还是")) > 0:
        other_appraisal = "unreliable_or_inconsistent"
    elif prev_move in {"pressure_or_accusation", "reassurance_request", "boundary_push"} or _count_markers(combined, ACCUSATION_KEYWORDS) > 0:
        other_appraisal = "pressuring"
    elif prev_move == "comfort_bid":
        other_appraisal = "caring"
    else:
        other_appraisal = "underdetermined"

    if any(move in clause_moves for move in ("state_limit", "withdraw")):
        core_need = "space_and_regulation"
    elif any(move in clause_moves for move in ("practical_repair", "direct_apology", "resume_contact")):
        core_need = "repair_and_follow_through"
    elif re.search(r"[?？]", text):
        core_need = "clarity"
    elif "comfort" in scene_labels:
        core_need = "connection"
    else:
        core_need = "clarity"

    regulation_style = "restrained" if _punctuation_style(text) == "light" and _count_markers(text, AGGRESSIVE_KEYWORDS) == 0 else "heated"
    return {
        "self_state": self_state,
        "other_appraisal": other_appraisal,
        "core_need": core_need,
        "regulation_style": regulation_style,
    }


def _infer_value_tradeoff_hints(text: str, scene_labels: list[str], values: Counter[str], appraisal: dict[str, Any], clause_moves: list[str]) -> list[dict[str, Any]]:
    hints: list[dict[str, Any]] = []
    warmth = _relational_features(text)["warmth"]

    if values.get("care", 0) > 0 and values.get("autonomy", 0) > 0:
        hints.append({
            "pair": ["care", "autonomy"],
            "favored": "autonomy" if any(move in clause_moves for move in ("state_limit", "withdraw")) else "care",
            "resolution": "soft_boundary_preserving_bond" if warmth > 0 else "care_with_space",
            "weight": values.get("care", 0) + values.get("autonomy", 0),
        })
    if values.get("truth", 0) > 0 and values.get("harmony", 0) > 0:
        hints.append({
            "pair": ["truth", "harmony"],
            "favored": "truth" if _count_markers(text, EXPLANATION_KEYWORDS) > 0 else "harmony",
            "resolution": "truth_softened_for_peace" if _directness_level(text) == "softened_direct" else "plain_truth",
            "weight": values.get("truth", 0) + values.get("harmony", 0),
        })
    if values.get("fairness", 0) > 0 and max(values.get("care", 0), values.get("autonomy", 0), values.get("harmony", 0)) > 0:
        secondary = max(("care", "autonomy", "harmony"), key=lambda item: values.get(item, 0))
        hints.append({
            "pair": ["fairness", secondary],
            "favored": "fairness" if appraisal.get("other_appraisal") == "unreliable_or_inconsistent" else secondary,
            "resolution": "keep_accountability_without_escalation" if _punctuation_style(text) == "light" else "hard_accountability",
            "weight": values.get("fairness", 0) + values.get(secondary, 0),
        })
    return hints[:3]


def _relational_features(text: str) -> dict[str, float]:
    clause_count = max(len(_split_clauses(text)), 1)
    warmth_hits = _count_markers(text, WARMTH_KEYWORDS) + 0.5 * _count_markers(text, SOFTENER_KEYWORDS)
    pressure_hits = _count_markers(text, PRESSURE_KEYWORDS) + _count_markers(text, DIRECTIVE_KEYWORDS) + 2 * _count_markers(text, ACCUSATION_KEYWORDS)
    distance_hits = _count_markers(text, WITHDRAW_KEYWORDS) + _count_markers(text, ("later", "明天", "改天", "先这样"))
    intimacy_hits = _count_markers(text, INTIMACY_KEYWORDS) + 0.5 * _count_markers(text, TEASING_KEYWORDS)
    return {
        "warmth": _clip(warmth_hits / (clause_count * 2.5)),
        "pressure": _clip(pressure_hits / (clause_count * 3.0)),
        "distance": _clip(distance_hits / (clause_count * 2.5)),
        "intimacy": _clip(intimacy_hits / (clause_count * 1.8)),
    }


def _structural_style_metrics(text: str) -> dict[str, float]:
    segments = [segment.strip() for segment in SENTENCE_SPLIT_RE.split(text) if segment.strip()]
    clause_count = max(len(_split_clauses(text)), 1)
    imperative_hits = _count_markers(text, DIRECTIVE_KEYWORDS) + _count_markers(text, PRESSURE_KEYWORDS)
    return {
        "sentence_count": float(len(segments) or 1),
        "clause_count": float(clause_count),
        "repeated_exclamation_groups": float(len(re.findall(r"[!！]{2,}", text))),
        "repeated_question_groups": float(len(re.findall(r"[?？]{2,}", text))),
        "imperative_density": round(imperative_hits / clause_count, 2),
        "absolute_hits": float(_count_markers(text, ABSOLUTE_KEYWORDS)),
    }


def _infer_response_habit(text: str) -> str:
    if any(text.strip().startswith(prefix) for prefix in ("我觉得", "我不是", "我只是", "I feel", "I'm", "I am")):
        return "feeling_first_then_reason"
    if _count_markers(text, ("因为", "所以", "because", "the fact is")) > 0:
        return "reason_first"
    if re.search(r"[?？]", text):
        return "question_first"
    return "balanced"


def _value_scores(text: str) -> Counter[str]:
    scores: Counter[str] = Counter()
    for value_name, markers in VALUE_KEYWORDS.items():
        hits = _count_markers(text, markers)
        if hits:
            scores[value_name] += hits
    if "不是" in text and "只是" in text:
        scores["truth"] += 1
    if _count_markers(text, ("答应", "promise", "做到", "keep your word")) > 0:
        scores["fairness"] += 1
    if _count_markers(text, ("累", "space", "边界", "today first")) > 0:
        scores["autonomy"] += 1
    if _count_markers(text, ("对不起", "抱歉", "sorry", "休息", "take care", "rest")) > 0:
        scores["care"] += 1
    if _count_markers(text, ("算了", "别吵", "keep the peace")) > 0:
        scores["harmony"] += 1
    if _count_markers(text, ("稳妥", "安全", "later", "改天")) > 0:
        scores["security"] += 1
    return scores


def _social_svo_label(value_scores: Counter[str], boundary_hits: int) -> str:
    care = value_scores.get("care", 0)
    fairness = value_scores.get("fairness", 0)
    autonomy = value_scores.get("autonomy", 0)
    power = value_scores.get("power", 0)
    if care + fairness >= 2 and boundary_hits:
        return "prosocial_but_bounded"
    if fairness > care and fairness >= 1:
        return "fairness_first"
    if power >= 1:
        return "competitive"
    if autonomy > care:
        return "self_protective"
    if care >= 1:
        return "prosocial"
    return "underdetermined"


def _stance_scores(text: str) -> tuple[int, int]:
    agency = _count_markers(text, DIRECTIVE_KEYWORDS) + _count_markers(text, ("不行", "不能", "must", "need to", "should"))
    communion = _count_markers(text, ("辛苦", "照顾", "关心", "没事", "take care", "sorry", "rest"))
    communion += _count_markers(text, SOFTENER_KEYWORDS)
    return agency, communion


def _bucket_three(score: float) -> str:
    if score < 1.0:
        return "low"
    if score < 2.5:
        return "medium"
    return "high"


def _relationship_scope(text: str, scene_labels: list[str], participants_count: int) -> str:
    if _count_markers(text, INTIMACY_KEYWORDS) > 0:
        return "close_relationship"
    if "self_disclosure" in scene_labels or "comfort" in scene_labels or "conflict" in scene_labels:
        return "close_relationship" if participants_count <= 2 else "personal_relationship"
    return "general"


def _condition_tags(text: str, scene_labels: list[str]) -> list[str]:
    tags: list[str] = []
    if _count_markers(text, ("又", "还是", "重复", "again", "still", "repeated")) > 0:
        tags.append("repeated_misunderstanding")
    if _count_markers(text, ("累", "晚点", "明天", "tired", "later")) > 0:
        tags.append("fatigue_or_delay")
    if "conflict" in scene_labels:
        tags.append("tension")
    return sorted(set(tags))


def _signal_confidence(source_weight: float, explicit_labels: list[str], marker_hits: int) -> float:
    base = 0.35 + source_weight * 0.25 + min(marker_hits, 3) * 0.12
    if explicit_labels:
        base += 0.08
    return _clip(base)


def _make_signal(
    *,
    signal_id: str,
    signal_type: str,
    signal_value: Any,
    confidence: float,
    support_weight: float,
    evidence_excerpt: str,
    scene_labels: list[str],
    relationship_scope: str,
    condition_tags: list[str],
    language_mode: str,
    time_scope: str,
    aggregation_target: str,
    source_ref: dict[str, Any],
) -> dict[str, Any]:
    return {
        "signal_id": signal_id,
        "signal_type": signal_type,
        "signal_value": signal_value,
        "confidence": round(confidence, 2),
        "support_weight": round(support_weight, 2),
        "evidence_excerpt": evidence_excerpt,
        "scene_labels": scene_labels,
        "relationship_scope": relationship_scope,
        "condition_tags": condition_tags,
        "language_mode": language_mode,
        "time_scope": time_scope,
        "aggregation_target": aggregation_target,
        "source_ref": source_ref,
    }


def _infer_conflict_move(text: str) -> str:
    explain_hits = _count_markers(text, EXPLANATION_KEYWORDS)
    withdraw_hits = _count_markers(text, WITHDRAW_KEYWORDS)
    accuse_hits = _count_markers(text, ("你总是", "都是你", "your fault", "you always"))
    if explain_hits and withdraw_hits:
        return "explain_then_withdraw"
    if accuse_hits:
        return "accuse_then_press"
    if _count_markers(text, ("不要", "别", "stop", "don't")) > 0:
        return "clear_boundary"
    return "measured_explanation"


def _infer_boundary_style(text: str) -> str:
    direct = _count_markers(text, DIRECTIVE_KEYWORDS)
    soft = _count_markers(text, SOFTENER_KEYWORDS)
    if direct and soft:
        return "clear_but_not_hostile"
    if direct:
        return "firm_direct"
    return "soft_boundary"


def _infer_repair_style(text: str) -> str:
    if _count_markers(text, ("对不起", "抱歉", "sorry")) > 0:
        return "direct_apology"
    if _count_markers(text, ("以后", "下次", "补上", "next time", "make it up")) > 0:
        return "practical_repair"
    if _count_markers(text, ("回来", "再聊", "later", "reach out again")) > 0:
        return "returns_after_cooldown"
    return "implicit_repair"


def _infer_affection_style(text: str) -> str:
    if _count_markers(text, INTIMACY_KEYWORDS) > 0:
        return "explicit_affection"
    if _count_markers(text, TEASING_KEYWORDS) > 0:
        return "teasing_affection"
    if _count_markers(text, ("注意安全", "休息", "take care", "rest")) > 0:
        return "restrained_indirect"
    return "reserved"


def _infer_uncertainty_style(text: str) -> str:
    if _count_markers(text, SCENE_KEYWORDS["uncertainty"]) > 0:
        return "admits_uncertainty"
    if _count_markers(text, ("一定", "绝对", "definitely", "absolutely")) > 0:
        return "projects_certainty"
    return "careful_but_clear"

def _extract_signals_from_units(*, units: list[EvidenceUnit], anchor_person_id: str, participants_count: int) -> list[dict[str, Any]]:
    signals: list[dict[str, Any]] = []
    anchor_texts = [unit.text for unit in units if unit.speaker_id == anchor_person_id]
    signature_patterns = _extract_signature_patterns(anchor_texts)

    for index, unit in enumerate(units):
        if unit.speaker_id != anchor_person_id:
            continue
        prev_unit = _reply_unit(units, index)
        next_unit = _next_counterpart_unit(units, index)
        prev_text = prev_unit.text if prev_unit and prev_unit.speaker_id != anchor_person_id else None
        next_text = next_unit.text if next_unit and next_unit.speaker_id != anchor_person_id else None

        scene_labels = _infer_scene_labels(unit.text, unit.labels)
        language_mode = _infer_language(unit.text)
        relationship_scope = _relationship_scope(unit.text, scene_labels, participants_count)
        condition_tags = _condition_tags(unit.text, scene_labels)
        support_weight = unit.source_weight * (1 + 0.08 * len(scene_labels))
        source_ref = _make_source_ref(unit)

        clause_moves = _extract_clause_moves(unit.text, scene_labels)
        event_semantics = _infer_event_semantics(unit.text, scene_labels, prev_text=prev_text, next_text=next_text)
        appraisal_hint = _infer_appraisal_hint(unit.text, scene_labels, prev_text=prev_text)
        values = _value_scores(unit.text)
        tradeoff_hints = _infer_value_tradeoff_hints(unit.text, scene_labels, values, appraisal_hint, clause_moves)

        speech_surface = {
            "sentence_length": _sentence_length_bucket(unit.text),
            "punctuation_style": _punctuation_style(unit.text),
            "emoji_density": _emoji_density(unit.text),
            "directness_level": _directness_level(unit.text),
            "signature_patterns": signature_patterns,
        }
        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:speech_surface:1",
            signal_type="speech_surface",
            signal_value=speech_surface,
            confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        acts, primary_act = _infer_speech_acts(unit.text, scene_labels)
        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:speech_act_profile:1",
            signal_type="speech_act_profile",
            signal_value={"acts": acts, "primary": primary_act},
            confidence=_signal_confidence(unit.source_weight, unit.labels, len(acts)),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:event_semantics:1",
            signal_type="event_semantics",
            signal_value=event_semantics,
            confidence=_signal_confidence(unit.source_weight, unit.labels, len(event_semantics.get("anchor_moves") or [])) + 0.04,
            support_weight=support_weight + 0.1,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="turn_window",
            aggregation_target="situation_policy",
            source_ref=source_ref,
        ))

        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:appraisal_hint:1",
            signal_type="appraisal_hint",
            signal_value=appraisal_hint,
            confidence=_signal_confidence(unit.source_weight, unit.labels, 3),
            support_weight=support_weight + 0.08,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="turn_window",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        response_habit = _infer_response_habit(unit.text)
        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:response_habit:1",
            signal_type="response_habit",
            signal_value=response_habit,
            confidence=_signal_confidence(unit.source_weight, unit.labels, 1),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        if "conflict" in scene_labels or "boundary" in scene_labels:
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:conflict_move:1",
                signal_type="conflict_move",
                signal_value={"label": _infer_conflict_move(unit.text), "speech_act": primary_act},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 3),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:boundary_move:1",
                signal_type="boundary_move",
                signal_value={"style": _infer_boundary_style(unit.text)},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))

        if "repair" in scene_labels:
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:repair_move:1",
                signal_type="repair_move",
                signal_value={"style": _infer_repair_style(unit.text)},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="situation_policy",
                source_ref=source_ref,
            ))

        if "comfort" in scene_labels or _count_markers(unit.text, ("注意安全", "休息", "take care", "rest")) > 0:
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:affection_move:1",
                signal_type="affection_move",
                signal_value={"style": _infer_affection_style(unit.text)},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))

        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:uncertainty_move:1",
            signal_type="uncertainty_move",
            signal_value={"style": _infer_uncertainty_style(unit.text)},
            confidence=_signal_confidence(unit.source_weight, unit.labels, 1),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        for value_name, score in values.items():
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:value_hint:{value_name}",
                signal_type="value_hint",
                signal_value={"value": value_name, "weight": score},
                confidence=_signal_confidence(unit.source_weight, unit.labels, score),
                support_weight=support_weight + score * 0.1,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))

        for tradeoff_index, tradeoff in enumerate(tradeoff_hints, start=1):
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:value_tradeoff_hint:{tradeoff_index}",
                signal_type="value_tradeoff_hint",
                signal_value=tradeoff,
                confidence=_signal_confidence(unit.source_weight, unit.labels, int(tradeoff.get("weight", 1))),
                support_weight=support_weight + float(tradeoff.get("weight", 1)) * 0.08,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="turn_window",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))

        if any(marker in unit.text for marker in ("我觉得", "我不是", "我只是", "I feel", "I am", "I'm")):
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:self_view_hint:1",
                signal_type="self_view_hint",
                signal_value={"statement": unit.text[:120]},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="base_soul",
                source_ref=source_ref,
            ))

        agency_score, communion_score = _stance_scores(unit.text)
        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:agency_communion_hint:1",
            signal_type="agency_communion_hint",
            signal_value={
                "agency": _bucket_three(float(agency_score)),
                "communion": _bucket_three(float(communion_score)),
                "agency_score": agency_score,
                "communion_score": communion_score,
            },
            confidence=_signal_confidence(unit.source_weight, unit.labels, agency_score + communion_score),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        attachment_tags: list[str] = []
        if _count_markers(unit.text, ("别走", "不要离开", "don't leave", "stay")) > 0:
            attachment_tags.append("reassurance_seeking")
        if _count_markers(unit.text, ("让我静一下", "先这样", "need space", "later")) > 0:
            attachment_tags.append("distance_under_tension")
        if _count_markers(unit.text, ("回来再说", "以后再聊", "talk later")) > 0:
            attachment_tags.append("repair_openness")
        if attachment_tags:
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:attachment_hint:1",
                signal_type="attachment_hint",
                signal_value={"tags": attachment_tags},
                confidence=_signal_confidence(unit.source_weight, unit.labels, len(attachment_tags)),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="relationship_overlay",
                source_ref=source_ref,
            ))

        social_svo = _social_svo_label(values, _count_markers(unit.text, ("不要", "别", "stop", "don't")))
        signals.append(_make_signal(
            signal_id=f"{unit.unit_id}:social_value_orientation_hint:1",
            signal_type="social_value_orientation_hint",
            signal_value={"label": social_svo},
            confidence=_signal_confidence(unit.source_weight, unit.labels, sum(values.values()) or 1),
            support_weight=support_weight,
            evidence_excerpt=unit.text[:160],
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            condition_tags=condition_tags,
            language_mode=language_mode,
            time_scope="instant",
            aggregation_target="base_soul",
            source_ref=source_ref,
        ))

        if any(pattern in unit.text for pattern in signature_patterns if pattern not in {"不是...是...", "not...just..."}) or "不是" in unit.text or "not" in unit.text.lower():
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:quote_candidate:1",
                signal_type="quote_candidate",
                signal_value={"excerpt": unit.text[:80]},
                confidence=_signal_confidence(unit.source_weight, unit.labels, 2),
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="instant",
                aggregation_target="quote_bank",
                source_ref=source_ref,
            ))

    return signals


def _mine_script_signals(*, units: list[EvidenceUnit], anchor_person_id: str, participants_count: int) -> list[dict[str, Any]]:
    signals: list[dict[str, Any]] = []
    for index, unit in enumerate(units):
        if unit.speaker_id != anchor_person_id:
            continue
        prev_unit = _reply_unit(units, index)
        next_unit = _next_counterpart_unit(units, index)
        prev_text = prev_unit.text if prev_unit and prev_unit.speaker_id != anchor_person_id else None
        next_text = next_unit.text if next_unit and next_unit.speaker_id != anchor_person_id else None

        scene_labels = _infer_scene_labels(unit.text, unit.labels)
        if "conflict" not in scene_labels and "repair" not in scene_labels and "boundary" not in scene_labels and "comfort" not in scene_labels:
            continue
        language_mode = _infer_language(unit.text)
        relationship_scope = _relationship_scope(unit.text, scene_labels, participants_count)
        condition_tags = _condition_tags(unit.text, scene_labels)
        clause_moves = _extract_clause_moves(unit.text, scene_labels)
        opening_context = _infer_external_move(prev_text, _infer_scene_labels(prev_text, prev_unit.labels if prev_unit else [])) if prev_text else "self_initiated"
        follow_up_context = _infer_external_move(next_text, _infer_scene_labels(next_text, next_unit.labels if next_unit else [])) if next_text else "no_external_follow_up"
        if len(clause_moves) < 2 and opening_context == "self_initiated":
            continue

        source_ref = _make_source_ref(unit)
        source_ref["window_id"] = f"{prev_unit.unit_id if prev_unit else 'start'}__{unit.unit_id}__{next_unit.unit_id if next_unit else 'end'}"

        opening_move = clause_moves[0] if clause_moves else opening_context
        follow_up_move = clause_moves[1] if len(clause_moves) >= 2 else follow_up_context
        closure_move = clause_moves[-1] if clause_moves else follow_up_context
        trigger = _ordered_unique(scene_labels + condition_tags + ([] if opening_context in {"self_initiated", "neutral_reply"} else [opening_context]))

        if "resume_contact" in clause_moves or follow_up_context in {"repair_offer", "accepts_boundary"}:
            outcome = "returns_after_cooldown"
        elif "withdraw" in clause_moves:
            outcome = "pause_after_boundary"
        elif follow_up_context == "comfort_bid":
            outcome = "softened_settlement"
        else:
            outcome = "ongoing_tension"

        confidence = _signal_confidence(unit.source_weight, unit.labels, len(clause_moves) + (1 if prev_text else 0))
        support_weight = unit.source_weight * (1.18 + 0.05 * min(len(clause_moves), 3))

        for signal_type, move_name in (
            ("opening_move_hint", opening_move),
            ("follow_up_move_hint", follow_up_move),
            ("closure_move_hint", closure_move),
        ):
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:{signal_type}:1",
                signal_type=signal_type,
                signal_value={"move": move_name, "opening_context": opening_context, "follow_up_context": follow_up_context},
                confidence=confidence,
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="turn_window",
                aggregation_target="evidence_only",
                source_ref=source_ref,
            ))

        for signal_type, target in (("conditioned_policy_hint", "situation_policy"), ("rupture_repair_script_hint", "base_soul")):
            signals.append(_make_signal(
                signal_id=f"{unit.unit_id}:{signal_type}:1",
                signal_type=signal_type,
                signal_value={
                    "trigger": trigger,
                    "move_sequence": clause_moves,
                    "target_scope": relationship_scope,
                    "opening_context": opening_context,
                    "follow_up_context": follow_up_context,
                    "outcome_tendency": outcome,
                },
                confidence=confidence,
                support_weight=support_weight,
                evidence_excerpt=unit.text[:160],
                scene_labels=scene_labels,
                relationship_scope=relationship_scope,
                condition_tags=condition_tags,
                language_mode=language_mode,
                time_scope="turn_window",
                aggregation_target=target,
                source_ref=source_ref,
            ))
    return signals


def _most_common(values: list[str], fallback: str) -> str:
    if not values:
        return fallback
    return Counter(values).most_common(1)[0][0]


def _build_identity_view(value_order: list[str], conflict_default: str, self_view_statements: list[str]) -> str:
    if self_view_statements:
        return f"Often frames the self through lines like: {self_view_statements[0][:80]}"
    top_values = ", ".join(value_order[:2]) if value_order else "care and honesty"
    if conflict_default == "explain_then_withdraw":
        return f"Often frames the self as responsible for being clear about {top_values} without escalating drama."
    return f"Often frames the self around {top_values} in a measured way."


def _derive_anti_patterns(speech_surface: dict[str, Any], uncertainty_style: str, conflict_default: str) -> list[str]:
    anti_patterns: list[str] = []
    if speech_surface.get("emoji_density") == "low":
        anti_patterns.append("high_emoji_gush")
    if speech_surface.get("sentence_length") in {"short", "short_medium"}:
        anti_patterns.extend(["long_motivational_monologue", "style_drift_monologue"])
    if speech_surface.get("punctuation_style") == "light":
        anti_patterns.append("overpunctuated_exclamation_style")
    if speech_surface.get("directness_level") in {"softened_direct", "balanced"}:
        anti_patterns.append("imperative_pressure_spiral")
    if uncertainty_style == "admits_uncertainty":
        anti_patterns.extend(["fake_certainty_when_unsure", "absolute_certainty_posturing"])
    if conflict_default == "explain_then_withdraw":
        anti_patterns.extend(["aggressive_personal_attack", "absolute_hostility"])
    anti_patterns.extend(["generic_therapy_tone", "salesy_encouragement", "overly_formal_ai_style"])
    ordered: list[str] = []
    for item in anti_patterns:
        if item not in ordered:
            ordered.append(item)
    return ordered


def _average(values: list[float], fallback: float = 0.0) -> float:
    return sum(values) / len(values) if values else fallback


def _signal_refs(signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    unique_refs: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str, str]] = set()
    for signal in signals:
        source_ref = signal.get("source_ref") or {}
        key = (
            str(source_ref.get("turn_id") or ""),
            str(source_ref.get("window_id") or ""),
            str(source_ref.get("counterpart_id") or source_ref.get("counterpart_name") or ""),
            str(source_ref.get("conversation_id") or ""),
            str(source_ref.get("timestamp") or ""),
        )
        if key in seen:
            continue
        seen.add(key)
        unique_refs.append(source_ref)
    return unique_refs


def _aggregate_language_modes(speech_signals: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    by_language: defaultdict[str, list[dict[str, Any]]] = defaultdict(list)
    for signal in speech_signals:
        by_language[str(signal.get("language_mode") or "unknown")].append(signal)

    language_modes: dict[str, dict[str, Any]] = {}
    for language_mode, language_group in by_language.items():
        language_patterns: list[str] = []
        for signal in language_group:
            for pattern in signal["signal_value"].get("signature_patterns", []):
                if pattern not in language_patterns:
                    language_patterns.append(pattern)
        language_modes[language_mode] = {
            "surface_confidence": round(_average([float(signal["confidence"]) for signal in language_group], 0.0), 2),
            "directness_shift": _most_common([signal["signal_value"].get("directness_level", "balanced") for signal in language_group], "balanced"),
            "emoji_density": _most_common([signal["signal_value"].get("emoji_density", "low") for signal in language_group], "low"),
            "sentence_length": _most_common([signal["signal_value"].get("sentence_length", "short_medium") for signal in language_group], "short_medium"),
            "punctuation_style": _most_common([signal["signal_value"].get("punctuation_style", "light") for signal in language_group], "light"),
            "signature_patterns": language_patterns[:3],
        }
    return language_modes


def _aggregate_speech_surface(speech_signals: list[dict[str, Any]]) -> dict[str, Any]:
    sentence_lengths = [signal["signal_value"].get("sentence_length", "short_medium") for signal in speech_signals]
    punctuation_styles = [signal["signal_value"].get("punctuation_style", "light") for signal in speech_signals]
    emoji_densities = [signal["signal_value"].get("emoji_density", "low") for signal in speech_signals]
    directness_levels = [signal["signal_value"].get("directness_level", "balanced") for signal in speech_signals]
    signature_patterns: list[str] = []
    for signal in speech_signals:
        for pattern in signal["signal_value"].get("signature_patterns", []):
            if pattern not in signature_patterns:
                signature_patterns.append(pattern)
    return {
        "sentence_length": _most_common(sentence_lengths, "short_medium"),
        "punctuation_style": _most_common(punctuation_styles, "light"),
        "emoji_density": _most_common(emoji_densities, "low"),
        "directness_level": _most_common(directness_levels, "softened_direct"),
        "signature_patterns": signature_patterns[:3],
    }


def _recurrence_profile(signals: list[dict[str, Any]]) -> dict[str, Any]:
    refs = _signal_refs(signals)
    day_keys = {day for day in (_day_key(ref.get("timestamp")) for ref in refs) if day}
    conversation_ids = {str(ref.get("conversation_id") or "").strip() for ref in refs if str(ref.get("conversation_id") or "").strip()}
    counterpart_counts: Counter[str] = Counter()
    for ref in refs:
        counterpart_key = str(ref.get("counterpart_id") or ref.get("counterpart_name") or "").strip()
        if counterpart_key:
            counterpart_counts[counterpart_key] += 1

    distinct_counterpart_count = len(counterpart_counts)
    observation_count = len(refs)
    relationship_concentration = counterpart_counts.most_common(1)[0][1] / observation_count if counterpart_counts and observation_count else 0.0
    recurrence_score = _clip(
        0.25 * min(observation_count, 4) / 4
        + 0.35 * min(len(day_keys), 3) / 3
        + 0.25 * min(len(conversation_ids), 3) / 3
        + 0.15 * min(distinct_counterpart_count, 3) / 3
    )
    stability_score = _clip(
        0.7 * recurrence_score
        + 0.15 * (1.0 if observation_count >= 2 else 0.35)
        + 0.15 * (0.8 if relationship_concentration >= 0.75 or distinct_counterpart_count >= 2 else 0.55)
    )
    return {
        "observations": observation_count,
        "distinct_day_count": len(day_keys),
        "distinct_conversation_count": len(conversation_ids),
        "distinct_counterpart_count": distinct_counterpart_count,
        "relationship_concentration": round(relationship_concentration, 2),
        "recurrence_score": round(recurrence_score, 2),
        "stability_score": round(stability_score, 2),
    }


def _aggregate_appraisal_tendencies(appraisal_signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    appraisal_counter: Counter[tuple[str, str, str]] = Counter()
    appraisal_confidence: defaultdict[tuple[str, str, str], list[float]] = defaultdict(list)
    for signal in appraisal_signals:
        value = signal["signal_value"]
        key = (
            str(value.get("self_state") or "steady"),
            str(value.get("other_appraisal") or "underdetermined"),
            str(value.get("core_need") or "clarity"),
        )
        appraisal_counter[key] += 1
        appraisal_confidence[key].append(float(signal["confidence"]))

    appraisal_tendencies: list[dict[str, Any]] = []
    for key, count in appraisal_counter.most_common(3):
        avg_confidence = _average(appraisal_confidence[key], 0.0)
        appraisal_tendencies.append({
            "self_state": key[0],
            "other_appraisal": key[1],
            "core_need": key[2],
            "confidence": round(_clip(avg_confidence + 0.05 * min(count - 1, 3)), 2),
            "observations": count,
        })
    return appraisal_tendencies


def _aggregate_value_tradeoffs(tradeoff_signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    tradeoff_counter: Counter[tuple[str, ...]] = Counter()
    tradeoff_confidence: defaultdict[tuple[str, ...], list[float]] = defaultdict(list)
    for signal in tradeoff_signals:
        value = signal["signal_value"]
        pair = tuple(str(item) for item in (value.get("pair") or []))
        if len(pair) < 2:
            continue
        key = pair + (str(value.get("favored") or pair[0]), str(value.get("resolution") or "underdetermined"))
        tradeoff_counter[key] += 1
        tradeoff_confidence[key].append(float(signal["confidence"]))

    value_tradeoffs: list[dict[str, Any]] = []
    for key, count in tradeoff_counter.most_common(3):
        avg_confidence = _average(tradeoff_confidence[key], 0.0)
        value_tradeoffs.append({
            "pair": [key[0], key[1]],
            "favored": key[2],
            "resolution": key[3],
            "confidence": round(_clip(avg_confidence + 0.05 * min(count - 1, 3)), 2),
            "observations": count,
        })
    return value_tradeoffs


def _narrative_theme(*, self_state: str, other_appraisal: str, core_need: str, favored_value: str) -> str:
    if other_appraisal == "unreliable_or_inconsistent" and favored_value == "fairness":
        return "steady accountability"
    if core_need == "space_and_regulation":
        return "space before repair"
    if core_need == "repair_and_follow_through":
        return "repair needs proof"
    if self_state == "caring":
        return "practical care"
    return "measured clarity"


def _build_narrative_summary(*, self_state: str, other_appraisal: str, core_need: str, favored_value: str, relationship_scope: str) -> str:
    if other_appraisal == "unreliable_or_inconsistent" and favored_value == "fairness":
        return "Frames broken follow-through as something that should be met with accountability before emotional repair."
    if core_need == "space_and_regulation":
        return "Under pressure, explains the feeling and protects space before deciding whether to reconnect."
    if core_need == "repair_and_follow_through":
        return "Treats repair as concrete follow-through, not words alone."
    if self_state == "caring" and relationship_scope in {"close_relationship", "personal_relationship"}:
        return "Shows care through practical, low-drama checking rather than overt gush."
    if relationship_scope == "close_relationship":
        return "In close relationships, prefers measured honesty over escalation."
    return "Frames the self as measured, relationship-aware, and oriented toward clarity."


def _aggregate_narrative_tendencies(signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    turn_groups: defaultdict[str, list[dict[str, Any]]] = defaultdict(list)
    for signal in signals:
        source_ref = signal.get("source_ref") or {}
        turn_id = str(source_ref.get("turn_id") or "").strip()
        if turn_id:
            turn_groups[turn_id].append(signal)

    narrative_buckets: dict[tuple[str, str, str, str, str, str, str], dict[str, Any]] = {}
    for turn_signals in turn_groups.values():
        self_views = [signal for signal in turn_signals if signal["signal_type"] == "self_view_hint"]
        appraisals = [signal for signal in turn_signals if signal["signal_type"] == "appraisal_hint"]
        tradeoffs = [signal for signal in turn_signals if signal["signal_type"] == "value_tradeoff_hint"]
        if not self_views and not appraisals and not tradeoffs:
            continue

        relationship_scope = _most_common([signal["relationship_scope"] for signal in turn_signals], "general")
        appraisal = max(appraisals, key=lambda signal: signal["confidence"]) if appraisals else None
        tradeoff = max(tradeoffs, key=lambda signal: signal["confidence"]) if tradeoffs else None
        appraisal_value = appraisal["signal_value"] if appraisal else {}
        tradeoff_value = tradeoff["signal_value"] if tradeoff else {}
        tradeoff_pair = tradeoff_value.get("pair") or ["clarity"]
        self_state = str(appraisal_value.get("self_state") or "steady")
        other_appraisal = str(appraisal_value.get("other_appraisal") or "underdetermined")
        core_need = str(appraisal_value.get("core_need") or "clarity")
        favored_value = str(tradeoff_value.get("favored") or tradeoff_pair[0])
        theme = _narrative_theme(
            self_state=self_state,
            other_appraisal=other_appraisal,
            core_need=core_need,
            favored_value=favored_value,
        )
        summary = _build_narrative_summary(
            self_state=self_state,
            other_appraisal=other_appraisal,
            core_need=core_need,
            favored_value=favored_value,
            relationship_scope=relationship_scope,
        )
        key = (theme, summary, relationship_scope, self_state, other_appraisal, core_need, favored_value)
        bucket = narrative_buckets.setdefault(key, {
            "observations": 0,
            "confidence_total": 0.0,
            "signature_quote": None,
            "source_signal_ids": [],
        })
        support = self_views + ([appraisal] if appraisal else []) + ([tradeoff] if tradeoff else [])
        support = [signal for signal in support if signal is not None]
        bucket["observations"] += 1
        bucket["confidence_total"] += _average([float(signal["confidence"]) for signal in support], 0.55)
        bucket["source_signal_ids"].extend(signal["signal_id"] for signal in support)
        if bucket["signature_quote"] is None and self_views:
            bucket["signature_quote"] = str(self_views[0]["signal_value"].get("statement") or "")[:120] or None

    rows: list[dict[str, Any]] = []
    sorted_buckets = sorted(narrative_buckets.items(), key=lambda item: (item[1]["observations"], item[1]["confidence_total"]), reverse=True)
    for key, bucket in sorted_buckets[:3]:
        theme, summary, _, _, _, _, _ = key
        row: dict[str, Any] = {
            "theme": theme,
            "summary": summary,
            "confidence": round(_clip(bucket["confidence_total"] / max(bucket["observations"], 1) + 0.05 * min(bucket["observations"] - 1, 3)), 2),
            "observations": bucket["observations"],
            "source_signal_ids": _ordered_unique(bucket["source_signal_ids"])[:6],
        }
        if bucket["signature_quote"]:
            row["signature_quote"] = bucket["signature_quote"]
        rows.append(row)
    return rows


def _overlay_narrative_moments(signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    moments: list[dict[str, Any]] = []
    for narrative in _aggregate_narrative_tendencies(signals):
        moments.append({
            "theme": narrative["theme"],
            "summary": narrative.get("summary", narrative["theme"]),
            "confidence": narrative["confidence"],
            "source_signal_ids": narrative.get("source_signal_ids", [])[:4],
        })
    return moments[:3]


def _aggregate_script_entries(*, signals: list[dict[str, Any]], signal_type: str, include_name: bool) -> list[dict[str, Any]]:
    filtered_signals = [signal for signal in signals if signal["signal_type"] == signal_type]
    buckets: dict[tuple[tuple[str, ...], tuple[str, ...], str], dict[str, Any]] = {}
    for signal in filtered_signals:
        trigger = tuple(signal["signal_value"].get("trigger", []))
        moves = tuple(signal["signal_value"].get("move_sequence", []))
        scope = str(signal.get("relationship_scope") or signal["signal_value"].get("target_scope") or "general")
        if not trigger or not moves:
            continue
        key = (trigger, moves, scope)
        bucket = buckets.setdefault(key, {
            "signals": [],
            "opening_contexts": [],
            "outcomes": [],
            "scene_scopes": [],
        })
        bucket["signals"].append(signal)
        bucket["opening_contexts"].append(str(signal["signal_value"].get("opening_context") or "self_initiated"))
        bucket["outcomes"].append(str(signal["signal_value"].get("outcome_tendency") or "ongoing_tension"))
        bucket["scene_scopes"].extend(signal.get("scene_labels") or [])

    rows: list[dict[str, Any]] = []
    for trigger, moves, scope in sorted(buckets.keys()):
        bucket = buckets[(trigger, moves, scope)]
        recurrence = _recurrence_profile(bucket["signals"])
        confidence = _clip(
            _average([float(signal["confidence"]) for signal in bucket["signals"]], 0.0)
            + 0.05 * min(recurrence["observations"] - 1, 3)
            + 0.08 * recurrence["recurrence_score"]
        )
        row: dict[str, Any] = {
            "relationship_scope": scope,
            "confidence": round(confidence, 2),
            "opening_context": _most_common(bucket["opening_contexts"], "self_initiated"),
            "outcome_tendency": _most_common(bucket["outcomes"], "ongoing_tension"),
            "observations": recurrence["observations"],
            "distinct_day_count": recurrence["distinct_day_count"],
            "distinct_conversation_count": recurrence["distinct_conversation_count"],
            "distinct_counterpart_count": recurrence["distinct_counterpart_count"],
            "recurrence_score": recurrence["recurrence_score"],
            "relationship_concentration": recurrence["relationship_concentration"],
            "stability_score": recurrence["stability_score"],
        }
        if include_name:
            row["trigger"] = list(trigger)
            row["moves"] = list(moves)
            row["scene_scope"] = _most_common(bucket["scene_scopes"], trigger[0] if trigger else "general")
        else:
            row["when"] = list(trigger)
            row["move_sequence"] = list(moves)
        rows.append(row)

    overall_recurrence = _recurrence_profile(filtered_signals)
    if filtered_signals and overall_recurrence["observations"] >= 2:
        trigger_tokens: list[str] = []
        move_tokens: list[str] = []
        opening_contexts: list[str] = []
        outcomes: list[str] = []
        scene_scopes: list[str] = []
        scopes: list[str] = []
        for signal in filtered_signals:
            for token in signal["signal_value"].get("trigger", []):
                if token not in trigger_tokens:
                    trigger_tokens.append(token)
            for move in signal["signal_value"].get("move_sequence", []):
                if move not in move_tokens:
                    move_tokens.append(move)
            opening_contexts.append(str(signal["signal_value"].get("opening_context") or "self_initiated"))
            outcomes.append(str(signal["signal_value"].get("outcome_tendency") or "ongoing_tension"))
            scene_scopes.extend(signal.get("scene_labels") or [])
            scopes.append(str(signal.get("relationship_scope") or signal["signal_value"].get("target_scope") or "general"))
        if trigger_tokens and move_tokens and overall_recurrence["observations"] > max((row["observations"] for row in rows), default=0):
            confidence = _clip(
                _average([float(signal["confidence"]) for signal in filtered_signals], 0.0)
                + 0.06 * min(overall_recurrence["observations"] - 1, 3)
                + 0.1 * overall_recurrence["recurrence_score"]
            )
            row = {
                "relationship_scope": _most_common(scopes, "general"),
                "confidence": round(confidence, 2),
                "opening_context": _most_common(opening_contexts, "self_initiated"),
                "outcome_tendency": _most_common(outcomes, "ongoing_tension"),
                "observations": overall_recurrence["observations"],
                "distinct_day_count": overall_recurrence["distinct_day_count"],
                "distinct_conversation_count": overall_recurrence["distinct_conversation_count"],
                "distinct_counterpart_count": overall_recurrence["distinct_counterpart_count"],
                "recurrence_score": overall_recurrence["recurrence_score"],
                "relationship_concentration": overall_recurrence["relationship_concentration"],
                "stability_score": overall_recurrence["stability_score"],
            }
            if include_name:
                row["trigger"] = trigger_tokens[:4]
                row["moves"] = move_tokens[:4]
                row["scene_scope"] = _most_common(scene_scopes, trigger_tokens[0])
            else:
                row["when"] = trigger_tokens[:4]
                row["move_sequence"] = move_tokens[:4]
            rows.append(row)

    rows.sort(
        key=lambda row: (
            row["observations"],
            row["distinct_day_count"],
            row["distinct_conversation_count"],
            row["recurrence_score"],
            row["confidence"],
        ),
        reverse=True,
    )
    if include_name:
        for index, row in enumerate(rows, start=1):
            row["name"] = f"script_{index}"
    return rows


def _dominant_conflict_patterns(signals: list[dict[str, Any]]) -> list[str]:
    labels: list[str] = []
    for signal in signals:
        if signal["signal_type"] == "conflict_move":
            label = str(signal["signal_value"].get("label") or "").strip()
            if label and label not in labels:
                labels.append(label)
        for scene_label in signal.get("scene_labels") or []:
            if scene_label in {"conflict", "boundary", "repair", "comfort"} and scene_label not in labels:
                labels.append(scene_label)
    return labels[:4]


def _overlay_provenance(overlays: list[dict[str, Any]]) -> list[dict[str, Any]]:
    provenance: list[dict[str, Any]] = []
    for index, overlay in enumerate(overlays):
        supporting = overlay.get("source_signal_ids") or []
        if not supporting:
            continue
        provenance.append({
            "field_path": f"overlays[{index}].relationship_concentration",
            "confidence": overlay["confidence"],
            "supporting_signal_ids": supporting[:6],
            "notes": ["Counterpart-specific overlay; apply only after explicit role mapping."],
        })
    return provenance


def _aggregate_relationship_overlay_draft(*, twin_id: str, anchor_person_id: str, signals: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: defaultdict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for signal in signals:
        source_ref = signal.get("source_ref") or {}
        counterpart_id = str(source_ref.get("counterpart_id") or "").strip()
        counterpart_name = str(source_ref.get("counterpart_name") or counterpart_id or "").strip()
        if not counterpart_id and not counterpart_name:
            continue
        grouped[(counterpart_id or counterpart_name, counterpart_name or counterpart_id or "unknown")].append(signal)

    overlays: list[dict[str, Any]] = []
    for (counterpart_id, counterpart_name), counterpart_signals in grouped.items():
        recurrence = _recurrence_profile(counterpart_signals)
        if recurrence["observations"] < 2 and not any(signal["signal_type"] == "rupture_repair_script_hint" for signal in counterpart_signals):
            continue

        speech_signals = [signal for signal in counterpart_signals if signal["signal_type"] == "speech_surface"]
        relational_scripts = _aggregate_script_entries(signals=counterpart_signals, signal_type="rupture_repair_script_hint", include_name=True)
        source_signal_ids = _ordered_unique([signal["signal_id"] for signal in counterpart_signals])
        overlay = {
            "counterpart_id": counterpart_id,
            "counterpart_name": counterpart_name,
            "relationship_label": _most_common([signal["relationship_scope"] for signal in counterpart_signals], "general"),
            "relationship_concentration": recurrence["relationship_concentration"],
            "recurrence_score": recurrence["recurrence_score"],
            "stability_score": recurrence["stability_score"],
            "dominant_conflict_patterns": _dominant_conflict_patterns(counterpart_signals),
            "narrative_moments": _overlay_narrative_moments(counterpart_signals),
            "highlighted_scripts": [
                {
                    "name": script["name"],
                    "moves": script["moves"],
                    "relationship_scope": script["relationship_scope"],
                    "recurrence_score": script["recurrence_score"],
                    "observations": script["observations"],
                }
                for script in relational_scripts[:3]
            ],
            "language_modes": _aggregate_language_modes(speech_signals),
            "speech_surface": _aggregate_speech_surface(speech_signals),
            "confidence": round(_clip(_average([float(signal["confidence"]) for signal in counterpart_signals], 0.45) + 0.08 * min(recurrence["observations"] - 1, 3)), 2),
            "source_signal_ids": source_signal_ids[:8],
        }
        overlays.append(overlay)

    overlays.sort(key=lambda item: (item["relationship_concentration"], item["recurrence_score"], item["confidence"]), reverse=True)
    relationship_scope = overlays[0].get("relationship_label", "general") if overlays else "general"
    overall_confidence = round(_average([float(item["confidence"]) for item in overlays], 0.0), 2)
    return {
        "draft_id": f"relationship_overlay_{twin_id}_{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}",
        "twin_id": twin_id,
        "anchor_person_id": anchor_person_id,
        "generated_at": _now_iso(),
        "publish_target": "review_only",
        "overall_confidence": overall_confidence,
        "relationship_scope": relationship_scope,
        "overlays": overlays,
        "provenance": _overlay_provenance(overlays),
    }


def _aggregate_soul_draft(*, twin_id: str, anchor_person_id: str, signals: list[dict[str, Any]]) -> dict[str, Any]:
    speech_signals = [signal for signal in signals if signal["signal_type"] == "speech_surface"]
    response_habits = [signal["signal_value"] for signal in signals if signal["signal_type"] == "response_habit"]
    uncertainty_styles = [signal["signal_value"]["style"] for signal in signals if signal["signal_type"] == "uncertainty_move"]
    conflict_moves = [signal["signal_value"]["label"] for signal in signals if signal["signal_type"] == "conflict_move"]
    boundary_styles = [signal["signal_value"]["style"] for signal in signals if signal["signal_type"] == "boundary_move"]
    repair_styles = [signal["signal_value"]["style"] for signal in signals if signal["signal_type"] == "repair_move"]
    affection_styles = [signal["signal_value"]["style"] for signal in signals if signal["signal_type"] == "affection_move"]
    social_svo_labels = [signal["signal_value"]["label"] for signal in signals if signal["signal_type"] == "social_value_orientation_hint"]
    self_view_statements = [signal["signal_value"]["statement"] for signal in signals if signal["signal_type"] == "self_view_hint"]
    appraisal_signals = [signal for signal in signals if signal["signal_type"] == "appraisal_hint"]
    tradeoff_signals = [signal for signal in signals if signal["signal_type"] == "value_tradeoff_hint"]

    value_scores: Counter[str] = Counter()
    provenance_by_field: defaultdict[str, list[str]] = defaultdict(list)
    for signal in signals:
        if signal["signal_type"] == "value_hint":
            value_name = signal["signal_value"]["value"]
            value_scores[value_name] += int(signal["signal_value"].get("weight", 1))
            provenance_by_field["value_order"].append(signal["signal_id"])
        if signal["signal_type"] == "conflict_move":
            provenance_by_field["conflict_policy.default"].append(signal["signal_id"])
        if signal["signal_type"] == "speech_surface":
            provenance_by_field["speech_surface"].append(signal["signal_id"])
        if signal["signal_type"] == "social_value_orientation_hint":
            provenance_by_field["social_value_orientation"].append(signal["signal_id"])
        if signal["signal_type"] == "appraisal_hint":
            provenance_by_field["appraisal_tendencies"].append(signal["signal_id"])
        if signal["signal_type"] == "value_tradeoff_hint":
            provenance_by_field["value_tradeoffs"].append(signal["signal_id"])
        if signal["signal_type"] == "conditioned_policy_hint":
            provenance_by_field["conditional_policies"].append(signal["signal_id"])
        if signal["signal_type"] == "rupture_repair_script_hint":
            provenance_by_field["relational_scripts"].append(signal["signal_id"])
        if signal["signal_type"] in {"self_view_hint", "appraisal_hint", "value_tradeoff_hint"}:
            provenance_by_field["narrative_tendencies"].append(signal["signal_id"])

    value_order = [item for item, _ in value_scores.most_common(3)] or ["truth", "care", "autonomy"]
    conflict_default = _most_common(conflict_moves, "explain_then_withdraw")
    uncertainty_style = _most_common(uncertainty_styles, "admits_uncertainty")
    conditional_policies = _aggregate_script_entries(signals=signals, signal_type="conditioned_policy_hint", include_name=False)
    relational_scripts = _aggregate_script_entries(signals=signals, signal_type="rupture_repair_script_hint", include_name=True)

    agency_scores = [signal["signal_value"].get("agency_score", 0) for signal in signals if signal["signal_type"] == "agency_communion_hint"]
    communion_scores = [signal["signal_value"].get("communion_score", 0) for signal in signals if signal["signal_type"] == "agency_communion_hint"]
    avg_agency = sum(agency_scores) / len(agency_scores) if agency_scores else 1.0
    avg_communion = sum(communion_scores) / len(communion_scores) if communion_scores else 2.0

    appraisal_tendencies = _aggregate_appraisal_tendencies(appraisal_signals)
    value_tradeoffs = _aggregate_value_tradeoffs(tradeoff_signals)
    narrative_tendencies = _aggregate_narrative_tendencies(signals)
    speech_surface = _aggregate_speech_surface(speech_signals)
    language_modes = _aggregate_language_modes(speech_signals)
    judgment_policy = {
        "fact_vs_feeling": _most_common(response_habits, "feeling_first_then_reason"),
        "certainty_style": uncertainty_style,
        "decision_style": "slow_but_clear" if _most_common(response_habits, "balanced") == "feeling_first_then_reason" else "balanced",
    }
    if appraisal_tendencies:
        judgment_policy["dominant_appraisal"] = {
            "self_state": appraisal_tendencies[0]["self_state"],
            "other_appraisal": appraisal_tendencies[0]["other_appraisal"],
            "core_need": appraisal_tendencies[0]["core_need"],
        }
    conflict_policy = {
        "default": conflict_default,
        "boundary_style": _most_common(boundary_styles, "clear_but_not_hostile"),
        "repair_style": _most_common(repair_styles, "returns_after_cooldown"),
    }
    affection_policy = {"mode": _most_common(affection_styles, "restrained_indirect")}
    identity_view = _build_identity_view(value_order, conflict_default, self_view_statements)
    anti_patterns = _derive_anti_patterns(speech_surface, uncertainty_style, conflict_default)
    social_value_orientation = _most_common(social_svo_labels, "prosocial_but_bounded")

    provenance: list[dict[str, Any]] = []
    for field_path, signal_ids in provenance_by_field.items():
        if not signal_ids:
            continue
        supporting = signal_ids[:6]
        confidence = round(sum(signal["confidence"] for signal in signals if signal["signal_id"] in supporting) / len(supporting), 2)
        provenance.append({
            "field_path": field_path,
            "confidence": confidence,
            "supporting_signal_ids": supporting,
            "notes": ["Heuristic import-time aggregation. Review before publish."],
        })

    return {
        "draft_id": f"base_soul_{twin_id}_{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}",
        "twin_id": twin_id,
        "anchor_person_id": anchor_person_id,
        "generated_at": _now_iso(),
        "publish_target": "review_only",
        "overall_confidence": round(_average([float(signal["confidence"]) for signal in signals], 0.0), 2),
        "identity_view": identity_view,
        "language_modes": language_modes,
        "narrative_tendencies": narrative_tendencies,
        "speech_surface": speech_surface,
        "judgment_policy": judgment_policy,
        "interpersonal_stance": {"agency": _bucket_three(avg_agency), "communion": _bucket_three(avg_communion)},
        "conflict_policy": conflict_policy,
        "affection_policy": affection_policy,
        "value_order": value_order,
        "social_value_orientation": social_value_orientation,
        "conditional_policies": conditional_policies,
        "relational_scripts": relational_scripts,
        "anti_patterns": anti_patterns,
        "provenance": provenance,
        "value_tradeoffs": value_tradeoffs,
        "appraisal_tendencies": appraisal_tendencies,
    }


def _draft_paths(service_root: Path, twin_id: str, output_name: str | None) -> tuple[Path, Path, Path]:
    stem = output_name or datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    base_dir = _soul_dir(service_root, twin_id)
    return (
        base_dir / f"{stem}.signals.json",
        base_dir / f"{stem}.base_soul_draft.json",
        base_dir / f"{stem}.relationship_expression_overlay_draft.json",
    )


def extract_chat_soul(*, service_root: str | None, twin_id: str, source: str, anchor_person_id: str | None = None, output_name: str | None = None) -> dict[str, Any]:
    root = _service_root(service_root)
    _ensure_service_dirs(root, twin_id)
    payload = _load_json(Path(source))
    if not isinstance(payload, dict):
        raise ServiceError("Chat corpus JSON must be an object.")
    anchor_id, anchor_name, participants_count, units = _chat_units(payload, anchor_person_id)
    signals = _extract_signals_from_units(units=units, anchor_person_id=anchor_id, participants_count=participants_count)
    signals.extend(_mine_script_signals(units=units, anchor_person_id=anchor_id, participants_count=participants_count))
    draft = _aggregate_soul_draft(twin_id=twin_id, anchor_person_id=anchor_id, signals=signals)
    overlay_draft = _aggregate_relationship_overlay_draft(twin_id=twin_id, anchor_person_id=anchor_id, signals=signals)
    signals_path, draft_path, relationship_overlay_path = _draft_paths(root, twin_id, output_name)
    _write_json(signals_path, signals)
    _write_json(draft_path, draft)
    _write_json(relationship_overlay_path, overlay_draft)
    return {
        "status": "ok",
        "mode": "extract_chat_soul",
        "twin_id": twin_id,
        "anchor_person_id": anchor_id,
        "anchor_display_name": anchor_name,
        "source_id": payload.get("source_id"),
        "signal_count": len(signals),
        "signals_path": str(signals_path),
        "base_draft_path": str(draft_path),
        "draft_path": str(draft_path),
        "relationship_overlay_path": str(relationship_overlay_path),
        "notes": [
            "This is an import-time initialization draft, not a runtime soul rewrite.",
            "Outputs are heuristic and should be reviewed before publish.",
        ],
    }


def extract_work_soul(*, service_root: str | None, twin_id: str, source: str, anchor_person_id: str | None = None, output_name: str | None = None) -> dict[str, Any]:
    root = _service_root(service_root)
    _ensure_service_dirs(root, twin_id)
    payload = _load_json(Path(source))
    if not isinstance(payload, dict):
        raise ServiceError("Work corpus JSON must be an object.")
    anchor_id, anchor_name, units = _work_units(payload, anchor_person_id)
    signals = _extract_signals_from_units(units=units, anchor_person_id=anchor_id, participants_count=2)
    signals.extend(_mine_script_signals(units=units, anchor_person_id=anchor_id, participants_count=2))
    draft = _aggregate_soul_draft(twin_id=twin_id, anchor_person_id=anchor_id, signals=signals)
    overlay_draft = _aggregate_relationship_overlay_draft(twin_id=twin_id, anchor_person_id=anchor_id, signals=signals)
    signals_path, draft_path, relationship_overlay_path = _draft_paths(root, twin_id, output_name)
    _write_json(signals_path, signals)
    _write_json(draft_path, draft)
    _write_json(relationship_overlay_path, overlay_draft)
    return {
        "status": "ok",
        "mode": "extract_work_soul",
        "twin_id": twin_id,
        "anchor_person_id": anchor_id,
        "anchor_display_name": anchor_name,
        "source_id": payload.get("source_id"),
        "signal_count": len(signals),
        "signals_path": str(signals_path),
        "base_draft_path": str(draft_path),
        "draft_path": str(draft_path),
        "relationship_overlay_path": str(relationship_overlay_path),
        "notes": [
            "Fiction-derived soul evidence stays hypothesis-like until corroborated.",
            "This is an import-time initialization draft, not a runtime soul rewrite.",
        ],
    }


def _anti_pattern_risk(text: str, anti_patterns: list[str]) -> float:
    risk = 0.02
    metrics = _structural_style_metrics(text)
    if "generic_therapy_tone" in anti_patterns and _count_markers(text, THERAPY_STYLE_KEYWORDS) > 0:
        risk += 0.28
    if "salesy_encouragement" in anti_patterns and _count_markers(text, SALESY_KEYWORDS) > 0:
        risk += 0.28
    if "overly_formal_ai_style" in anti_patterns and _count_markers(text, FORMAL_AI_KEYWORDS) > 0:
        risk += 0.3
    if "high_emoji_gush" in anti_patterns and _emoji_density(text) == "high":
        risk += 0.2
    if "aggressive_personal_attack" in anti_patterns and _count_markers(text, AGGRESSIVE_KEYWORDS) > 0:
        risk += 0.35
    if "long_motivational_monologue" in anti_patterns and _sentence_length_bucket(text) in {"medium", "long"} and len(text) > 120:
        risk += 0.15
    if "overpunctuated_exclamation_style" in anti_patterns and metrics["repeated_exclamation_groups"] >= 1:
        risk += 0.18
    if "imperative_pressure_spiral" in anti_patterns and metrics["imperative_density"] >= 1.0:
        risk += 0.14
    if "absolute_hostility" in anti_patterns and metrics["absolute_hits"] >= 1 and _count_markers(text, AGGRESSIVE_KEYWORDS) > 0:
        risk += 0.16
    if "style_drift_monologue" in anti_patterns and metrics["sentence_count"] >= 4:
        risk += 0.14
    if metrics["repeated_exclamation_groups"] >= 2:
        risk += 0.08
    if metrics["absolute_hits"] >= 2:
        risk += 0.08
    return _clip(risk)


def _voice_similarity(text: str, draft: dict[str, Any]) -> float:
    speech = draft.get("speech_surface") or {}
    language_modes = draft.get("language_modes") or {}
    candidate_language = _infer_language(text)
    language_profile = language_modes.get(candidate_language) or {}
    score = 0.32
    if _sentence_length_bucket(text) == speech.get("sentence_length"):
        score += 0.1
    if _punctuation_style(text) == speech.get("punctuation_style"):
        score += 0.08
    if _emoji_density(text) == speech.get("emoji_density"):
        score += 0.06
    if _directness_level(text) == speech.get("directness_level"):
        score += 0.08
    if language_profile:
        if _sentence_length_bucket(text) == language_profile.get("sentence_length"):
            score += 0.05
        if _punctuation_style(text) == language_profile.get("punctuation_style"):
            score += 0.04
        if _directness_level(text) == language_profile.get("directness_shift"):
            score += 0.06
        if _emoji_density(text) == language_profile.get("emoji_density"):
            score += 0.04
    elif language_modes:
        score -= 0.06 if len(language_modes) == 1 else 0.1
    patterns = list(language_profile.get("signature_patterns") or []) + list(speech.get("signature_patterns") or [])
    for pattern in _ordered_unique([item for item in patterns if isinstance(item, str) and item]):
        if pattern == "不是...是..." and "不是" in text:
            score += 0.07
        elif pattern == "not...just..." and "not" in text.lower() and "just" in text.lower():
            score += 0.07
        elif pattern in text:
            score += 0.04
    return _clip(score)


def _decision_similarity(text: str, draft: dict[str, Any], scene_labels: list[str]) -> float:
    score = 0.5
    conflict_default = ((draft.get("conflict_policy") or {}).get("default")) or "explain_then_withdraw"
    fact_vs_feeling = ((draft.get("judgment_policy") or {}).get("fact_vs_feeling")) or "balanced"
    if "conflict" in scene_labels:
        if conflict_default == "explain_then_withdraw" and _count_markers(text, EXPLANATION_KEYWORDS) > 0:
            score += 0.18
        if conflict_default == "explain_then_withdraw" and _count_markers(text, WITHDRAW_KEYWORDS) > 0:
            score += 0.1
        if _count_markers(text, AGGRESSIVE_KEYWORDS) > 0:
            score -= 0.3
    if fact_vs_feeling == "feeling_first_then_reason" and _infer_response_habit(text) == fact_vs_feeling:
        score += 0.12
    return _clip(score)


def _relationship_fit(text: str, relationship_scope: str, draft: dict[str, Any], scene_labels: list[str]) -> tuple[float, float]:
    features = _relational_features(text)
    affection_mode = ((draft.get("affection_policy") or {}).get("mode")) or "restrained_indirect"
    conflict_default = ((draft.get("conflict_policy") or {}).get("default")) or "explain_then_withdraw"
    score = 0.52
    intimacy = 0.5

    if relationship_scope == "close_relationship":
        if features["warmth"] >= 0.18:
            score += 0.12
            intimacy += 0.08
        else:
            intimacy -= 0.08
        if affection_mode == "restrained_indirect" and features["intimacy"] > 0.35:
            score -= 0.12
            intimacy -= 0.14
        elif features["intimacy"] > 0.05:
            intimacy += 0.1
        if "conflict" in scene_labels and conflict_default == "explain_then_withdraw" and 0.08 <= features["distance"] <= 0.45:
            score += 0.08
    elif relationship_scope == "personal_relationship":
        if features["warmth"] >= 0.1:
            score += 0.08
        if features["intimacy"] > 0.28:
            score -= 0.12
            intimacy -= 0.1
    else:
        if features["intimacy"] > 0.05:
            score -= 0.26
            intimacy -= 0.24
        if features["warmth"] > 0.25:
            score -= 0.08

    if features["pressure"] >= 0.45:
        score -= 0.22
    elif features["pressure"] >= 0.25:
        score -= 0.1
    if "comfort" in scene_labels and features["warmth"] >= 0.14:
        score += 0.08
    return _clip(score), _clip(intimacy)


def _scene_fit(text: str, scene_labels: list[str]) -> tuple[float, float, float]:
    _, primary_act = _infer_speech_acts(text, scene_labels)
    score = 0.55
    opening = 0.6
    closure = 0.6
    if "conflict" in scene_labels:
        if primary_act in {"boundary_set", "confess"}:
            score += 0.18
        if _infer_response_habit(text) == "feeling_first_then_reason":
            opening += 0.2
        if _count_markers(text, WITHDRAW_KEYWORDS) > 0 or _count_markers(text, ("先这样", "later", "today first")) > 0:
            closure += 0.18
    if "repair" in scene_labels and primary_act in {"apologize", "reassure", "promise"}:
        score += 0.18
    if "comfort" in scene_labels and primary_act == "reassure":
        score += 0.2
    return _clip(score), _clip(opening), _clip(closure)


def _memory_consistency(text: str, memory_hints: list[str]) -> float:
    if not memory_hints:
        return 0.9
    joined = " ".join(memory_hints)
    score = 0.88
    if re.search(r"\b\d{4}\b", text) and not re.search(r"\b\d{4}\b", joined):
        score -= 0.12
    return _clip(score)


def _value_order_fit(text: str, draft: dict[str, Any], scene_labels: list[str]) -> float:
    values = _value_scores(text)
    appraisal = _infer_appraisal_hint(text, scene_labels)
    clause_moves = _extract_clause_moves(text, scene_labels)
    score = 0.4
    top_values = list(draft.get("value_order") or [])[:3]
    for index, value_name in enumerate(top_values):
        if values.get(value_name, 0) > 0:
            score += 0.16 - index * 0.03
    for tradeoff in draft.get("value_tradeoffs") or []:
        pair = {str(item) for item in (tradeoff.get("pair") or [])}
        if not pair:
            continue
        hits = len(pair.intersection(values.keys()))
        if hits:
            score += 0.04 * hits
        favored = str(tradeoff.get("favored") or "")
        if favored and values.get(favored, 0) > 0:
            score += 0.06
        resolution = str(tradeoff.get("resolution") or "")
        if resolution == "soft_boundary_preserving_bond" and any(move in clause_moves for move in ("state_limit", "withdraw")) and _relational_features(text)["warmth"] > 0:
            score += 0.05
        if resolution == "truth_softened_for_peace" and _count_markers(text, EXPLANATION_KEYWORDS) > 0 and _directness_level(text) == "softened_direct":
            score += 0.05
        if resolution == "keep_accountability_without_escalation" and appraisal.get("other_appraisal") == "unreliable_or_inconsistent" and _punctuation_style(text) == "light":
            score += 0.05
    return _clip(score)


def _interpersonal_stance_fit(text: str, draft: dict[str, Any]) -> float:
    expected = draft.get("interpersonal_stance") or {}
    agency_score, communion_score = _stance_scores(text)
    actual_agency = _bucket_three(float(agency_score))
    actual_communion = _bucket_three(float(communion_score))
    score = 0.5
    if actual_agency == expected.get("agency"):
        score += 0.18
    if actual_communion == expected.get("communion"):
        score += 0.18
    return _clip(score)


def _speech_act_fit(text: str, scene_labels: list[str]) -> float:
    _, primary_act = _infer_speech_acts(text, scene_labels)
    score = 0.55
    if "conflict" in scene_labels and primary_act in {"boundary_set", "confess"}:
        score += 0.24
    if "repair" in scene_labels and primary_act in {"apologize", "promise", "reassure"}:
        score += 0.24
    if "comfort" in scene_labels and primary_act == "reassure":
        score += 0.24
    return _clip(score)


def _regulation_fit(text: str, draft: dict[str, Any]) -> float:
    conflict_default = ((draft.get("conflict_policy") or {}).get("default")) or "explain_then_withdraw"
    score = 0.6
    if conflict_default == "explain_then_withdraw" and _count_markers(text, AGGRESSIVE_KEYWORDS) > 0:
        score -= 0.4
    if _punctuation_style(text) == "light":
        score += 0.12
    return _clip(score)


def _ordered_sequence_match(observed: list[str], expected: list[str]) -> tuple[float, float, bool]:
    if not observed or not expected:
        return 0.0, 0.0, False
    ordered_hits = 0
    cursor = 0
    for move in expected:
        try:
            found_index = observed.index(move, cursor)
        except ValueError:
            continue
        ordered_hits += 1
        cursor = found_index + 1
    prefix_hits = 0
    for actual, wanted in zip(observed, expected):
        if actual != wanted:
            break
        prefix_hits += 1
    expected_index = {move: index for index, move in enumerate(expected)}
    reversed_penalty = 0.0
    for index in range(len(observed) - 1):
        left = expected_index.get(observed[index])
        right = expected_index.get(observed[index + 1])
        if left is not None and right is not None and left > right:
            reversed_penalty += 0.08
    terminal_match = observed[-1] == expected[-1]
    raw = 0.48 * (ordered_hits / len(expected)) + 0.32 * (prefix_hits / len(expected)) + (0.12 if terminal_match else 0.0) - reversed_penalty
    return _clip(raw), prefix_hits / len(expected), terminal_match


def _boundary_calibration_fit(text: str, draft: dict[str, Any], scene_labels: list[str], anti_pattern_risk: float) -> float:
    if "conflict" not in scene_labels and "boundary" not in scene_labels:
        return 0.72 if anti_pattern_risk < 0.45 else 0.58
    features = _relational_features(text)
    expected = ((draft.get("conflict_policy") or {}).get("boundary_style")) or "clear_but_not_hostile"
    score = 0.46
    if features["distance"] > 0.08 or _count_markers(text, ("不要", "别", "stop", "don't", "先这样")) > 0:
        score += 0.16
    if expected == "clear_but_not_hostile" and features["pressure"] < 0.32:
        score += 0.14
    elif expected == "firm_direct" and features["pressure"] >= 0.18:
        score += 0.1
    if anti_pattern_risk >= 0.45:
        score -= 0.16
    return _clip(score)


def _script_continuity_fit(text: str, draft: dict[str, Any], scene_labels: list[str]) -> float:
    observed = _extract_clause_moves(text, scene_labels)
    policies = list(draft.get("conditional_policies") or [])
    if not policies:
        policies = [
            {"when": script.get("trigger") or [], "move_sequence": script.get("moves") or []}
            for script in (draft.get("relational_scripts") or [])
        ]
    score = 0.32
    for policy in policies:
        when = set(policy.get("when") or [])
        if when and not when.intersection(scene_labels):
            continue
        expected = [str(move) for move in (policy.get("move_sequence") or []) if str(move)]
        if not expected:
            continue
        match_score, prefix_ratio, terminal_match = _ordered_sequence_match(observed, expected)
        candidate_score = 0.28 + match_score
        if prefix_ratio >= 0.67:
            candidate_score += 0.08
        if terminal_match:
            candidate_score += 0.06
        score = max(score, candidate_score)
    return _clip(score)


def _quote_regurgitation_risk(text: str, reference_quotes: list[str]) -> float:
    risk = 0.01
    for quote in reference_quotes:
        snippet = quote[:16]
        if snippet and snippet in text:
            risk += 0.22
    return _clip(risk)


def _fiction_contamination_risk(text: str, source_mode: str) -> float:
    risk = 0.02
    if source_mode == "chat_history" and re.search(r"\b(she|he) looked at\b", text.lower()):
        risk += 0.2
    return _clip(risk)




def _clean_hint_map(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    cleaned: dict[str, Any] = {}
    for key, item in value.items():
        cleaned_key = str(key).strip()
        if not cleaned_key:
            continue
        cleaned[cleaned_key] = item.strip() if isinstance(item, str) else item
    return cleaned



def _normalize_hint_token(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip().lower().replace("-", "_").replace(" ", "_")



def _judge_selector_context(candidate_payload: dict[str, Any]) -> JudgeSelectorContext:
    selected_relationship_binding_id = str(candidate_payload.get("selected_relationship_binding_id") or "").strip() or None
    counterpart_entity_id = str(candidate_payload.get("counterpart_entity_id") or "").strip() or None
    overlay_key = str(candidate_payload.get("overlay_key") or "").strip() or None
    relationship_state_hints = _clean_hint_map(candidate_payload.get("relationship_state_hints"))
    interaction_preference_hints = _clean_hint_map(candidate_payload.get("interaction_preference_hints"))
    recent_script_hints = [item for item in _string_list(candidate_payload.get("recent_script_hints")) if item]

    if not any((selected_relationship_binding_id, counterpart_entity_id, overlay_key)):
        return JudgeSelectorContext(
            selected_relationship_binding_id=None,
            counterpart_entity_id=None,
            overlay_key=None,
            relationship_state_hints=relationship_state_hints,
            interaction_preference_hints=interaction_preference_hints,
            recent_script_hints=recent_script_hints,
            context_quality="minimal",
            degraded_context_reason=None,
        )
    if not all((selected_relationship_binding_id, counterpart_entity_id, overlay_key)):
        return JudgeSelectorContext(
            selected_relationship_binding_id=selected_relationship_binding_id,
            counterpart_entity_id=counterpart_entity_id,
            overlay_key=overlay_key,
            relationship_state_hints=relationship_state_hints,
            interaction_preference_hints=interaction_preference_hints,
            recent_script_hints=recent_script_hints,
            context_quality="degraded",
            degraded_context_reason="Selected relationship binding fields are incomplete.",
        )
    if not (relationship_state_hints or interaction_preference_hints or recent_script_hints):
        return JudgeSelectorContext(
            selected_relationship_binding_id=selected_relationship_binding_id,
            counterpart_entity_id=counterpart_entity_id,
            overlay_key=overlay_key,
            relationship_state_hints=relationship_state_hints,
            interaction_preference_hints=interaction_preference_hints,
            recent_script_hints=recent_script_hints,
            context_quality="degraded",
            degraded_context_reason="Selected relationship binding is present but overlay hints are missing.",
        )
    return JudgeSelectorContext(
        selected_relationship_binding_id=selected_relationship_binding_id,
        counterpart_entity_id=counterpart_entity_id,
        overlay_key=overlay_key,
        relationship_state_hints=relationship_state_hints,
        interaction_preference_hints=interaction_preference_hints,
        recent_script_hints=recent_script_hints,
        context_quality="full",
        degraded_context_reason=None,
    )



def _parse_script_hint_sequence(value: str) -> list[str]:
    return [segment.strip() for segment in re.split(r"\s*(?:->|→)\s*", value) if segment.strip()]



def _script_hint_fit(text: str, scene_labels: list[str], recent_script_hints: list[str]) -> float:
    if not recent_script_hints:
        return 0.58
    observed = _extract_clause_moves(text, scene_labels)
    score = 0.34
    for hint in recent_script_hints:
        expected = _parse_script_hint_sequence(hint)
        if not expected:
            continue
        match_score, prefix_ratio, terminal_match = _ordered_sequence_match(observed, expected)
        candidate_score = 0.28 + match_score
        if prefix_ratio >= 0.67:
            candidate_score += 0.08
        if terminal_match:
            candidate_score += 0.06
        score = max(score, candidate_score)
    return _clip(score)



def _selected_counterpart_fit(text: str, relationship_scope: str, scene_labels: list[str], selector_context: JudgeSelectorContext) -> float:
    if not selector_context.counterpart_entity_id:
        return 0.5
    second_person_hits = _count_markers(text, SECOND_PERSON_KEYWORDS)
    third_party_hits = _count_markers(text, THIRD_PARTY_REFERENCE_KEYWORDS)
    observed_moves = _extract_clause_moves(text, scene_labels)
    score = 0.68 if selector_context.context_quality == "full" else 0.62
    if second_person_hits > 0:
        score += 0.12
    elif relationship_scope in {"close_relationship", "personal_relationship"} and observed_moves:
        score += 0.02
    elif relationship_scope in {"close_relationship", "personal_relationship"}:
        score -= 0.08
    if observed_moves:
        score += 0.05
    if third_party_hits > 0:
        score -= 0.12 if second_person_hits > 0 else 0.24
        score -= 0.06 * min(max(third_party_hits - 1, 0), 4)
        if third_party_hits >= 2 and second_person_hits <= third_party_hits:
            score -= 0.1
    if re.search(r"\b(she|he) looked at\b", text.lower()):
        score -= 0.12
    return _clip(score)



def _relationship_state_fit(text: str, relationship_state_hints: dict[str, Any]) -> float:
    if not relationship_state_hints:
        return 0.58
    features = _relational_features(text)
    score = 0.58
    aggressive_hits = _count_markers(text, AGGRESSIVE_KEYWORDS)
    for raw_key, raw_value in relationship_state_hints.items():
        key = _normalize_hint_token(raw_key)
        value = _normalize_hint_token(raw_value)
        if key in {"familiarity", "closeness", "closeness_level"}:
            if value == "high":
                if features["warmth"] >= 0.08:
                    score += 0.08
                if features["intimacy"] <= 0.28:
                    score += 0.04
            elif value == "medium":
                if features["warmth"] >= 0.04:
                    score += 0.05
            elif value in {"low", "distant"}:
                if features["intimacy"] <= 0.12:
                    score += 0.08
                else:
                    score -= 0.08
        elif key == "trust":
            if value == "high":
                if features["pressure"] < 0.24 and aggressive_hits == 0:
                    score += 0.08
            elif value == "medium":
                if features["pressure"] < 0.34:
                    score += 0.05
                else:
                    score -= 0.05
            elif value == "low":
                if features["pressure"] < 0.22 and features["intimacy"] <= 0.12:
                    score += 0.06
                else:
                    score -= 0.08
        elif key == "safety":
            if value in {"high", "medium"}:
                if aggressive_hits == 0:
                    score += 0.08 if value == "high" else 0.05
                else:
                    score -= 0.12
            elif value == "low" and features["distance"] > 0.08 and aggressive_hits == 0:
                score += 0.05
        elif key in {"tension", "volatility", "instability"}:
            if value == "high":
                if _count_markers(text, EXPLANATION_KEYWORDS) > 0 or features["distance"] > 0.08:
                    score += 0.06
            elif value in {"low", "stable"}:
                if features["pressure"] < 0.22:
                    score += 0.04
                else:
                    score -= 0.04
    return _clip(score)



def _interaction_preference_fit(text: str, interaction_preference_hints: dict[str, Any]) -> float:
    if not interaction_preference_hints:
        return 0.58
    features = _relational_features(text)
    teasing_hits = _count_markers(text, TEASING_KEYWORDS) + _count_markers(text, ("哈哈", "lol", "jk", "hhh"))
    actual_directness = _directness_level(text)
    score = 0.56
    for raw_key, raw_value in interaction_preference_hints.items():
        key = _normalize_hint_token(raw_key)
        value = _normalize_hint_token(raw_value)
        if key == "warmth":
            if value in {"restrained", "restrained_warmth", "soft"}:
                if 0.06 <= features["warmth"] <= 0.24 and features["intimacy"] <= 0.2:
                    score += 0.16
                elif features["warmth"] < 0.03 or features["intimacy"] > 0.32:
                    score -= 0.08
            elif value in {"high", "warm", "open"}:
                if features["warmth"] >= 0.18:
                    score += 0.12
                else:
                    score -= 0.08
            elif value in {"low", "cool", "cold"}:
                if features["warmth"] <= 0.1 and features["intimacy"] <= 0.12:
                    score += 0.12
                else:
                    score -= 0.06
        elif key == "formality":
            if value == "low":
                if _count_markers(text, FORMAL_AI_KEYWORDS) == 0:
                    score += 0.1
                else:
                    score -= 0.12
            elif value == "high" and (_count_markers(text, FORMAL_AI_KEYWORDS) > 0 or _punctuation_style(text) == "heavy"):
                score += 0.04
        elif key == "directness":
            if value == actual_directness:
                score += 0.12
            elif value == "restrained" and actual_directness in {"softened_direct", "indirect"}:
                score += 0.08
            elif value == "softened_direct" and actual_directness == "direct":
                score -= 0.08
        elif key in {"humor", "playfulness"}:
            if value in {"low", "restrained"} and teasing_hits == 0:
                score += 0.05
            elif value in {"high", "playful"} and teasing_hits > 0:
                score += 0.06
            elif value in {"low", "restrained"} and teasing_hits > 0:
                score -= 0.05
        elif key in {"reassurance", "comfort"}:
            if value in {"restrained", "low"} and features["warmth"] <= 0.24:
                score += 0.06
            elif value in {"high", "warm"} and features["warmth"] >= 0.18:
                score += 0.08
    return _clip(score)



def _overlay_activation_fit(text: str, scene_labels: list[str], selector_context: JudgeSelectorContext) -> float:
    if not selector_context.overlay_key:
        return 0.5
    interaction_fit = _interaction_preference_fit(text, selector_context.interaction_preference_hints)
    script_fit = _script_hint_fit(text, scene_labels, selector_context.recent_script_hints)
    score = 0.2
    score += 0.12 if selector_context.context_quality == "full" else 0.04
    score += 0.34 * interaction_fit if selector_context.interaction_preference_hints else 0.14
    score += 0.34 * script_fit if selector_context.recent_script_hints else 0.14
    if selector_context.context_quality == "degraded" and selector_context.degraded_context_reason:
        score -= 0.06
    return _clip(score)



def _rebind_contamination_risk(
    text: str,
    source_mode: str,
    selector_context: JudgeSelectorContext,
    selected_counterpart_fit: float,
    overlay_activation_fit: float,
    relationship_state_fit: float,
) -> float:
    if selector_context.context_quality == "minimal":
        return 0.08
    third_party_hits = _count_markers(text, THIRD_PARTY_REFERENCE_KEYWORDS)
    second_person_hits = _count_markers(text, SECOND_PERSON_KEYWORDS)
    risk = 0.04
    if second_person_hits > 0 and third_party_hits == 0:
        risk -= 0.04
    if third_party_hits > 0:
        risk += 0.14 + 0.05 * min(third_party_hits, 3)
        if third_party_hits >= 2 and second_person_hits <= third_party_hits:
            risk += 0.12
    if selected_counterpart_fit < 0.5:
        risk += 0.18
    if overlay_activation_fit < 0.5:
        risk += 0.14
    if relationship_state_fit < 0.5 and selector_context.relationship_state_hints:
        risk += 0.1
    if source_mode == "chat_history" and re.search(r"\b(she|he) looked at\b", text.lower()):
        risk += 0.12
    return _clip(risk)



def _judge_one_candidate(
    *,
    candidate_id: str,
    text: str,
    draft: dict[str, Any],
    scene_labels: list[str],
    relationship_scope: str,
    memory_hints: list[str],
    reference_quotes: list[str],
    source_mode: str,
    selector_context: JudgeSelectorContext,
) -> dict[str, Any]:
    relationship_fit, intimacy_fit = _relationship_fit(text, relationship_scope, draft, scene_labels)
    scene_fit, opening_move_fit, closure_fit = _scene_fit(text, scene_labels)
    anti_pattern_risk = _anti_pattern_risk(text, list(draft.get("anti_patterns") or []))
    voice_similarity = _voice_similarity(text, draft)
    decision_similarity = _decision_similarity(text, draft, scene_labels)
    memory_consistency = _memory_consistency(text, memory_hints)
    value_order_fit = _value_order_fit(text, draft, scene_labels)
    interpersonal_stance_fit = _interpersonal_stance_fit(text, draft)
    speech_act_fit = _speech_act_fit(text, scene_labels)
    regulation_fit = _regulation_fit(text, draft)
    script_continuity_fit = _script_continuity_fit(text, draft, scene_labels)
    boundary_calibration_fit = _boundary_calibration_fit(text, draft, scene_labels, anti_pattern_risk)
    quote_regurgitation_risk = _quote_regurgitation_risk(text, reference_quotes)
    fiction_contamination_risk = _fiction_contamination_risk(text, source_mode)
    selected_counterpart_fit = _selected_counterpart_fit(text, relationship_scope, scene_labels, selector_context)
    overlay_activation_fit = _overlay_activation_fit(text, scene_labels, selector_context)
    relationship_state_fit = _relationship_state_fit(text, selector_context.relationship_state_hints)
    rebind_contamination_risk = _rebind_contamination_risk(
        text,
        source_mode,
        selector_context,
        selected_counterpart_fit,
        overlay_activation_fit,
        relationship_state_fit,
    )

    base_score = (
        0.17 * voice_similarity + 0.15 * decision_similarity + 0.1 * relationship_fit + 0.08 * scene_fit +
        0.1 * memory_consistency + 0.1 * value_order_fit + 0.07 * interpersonal_stance_fit + 0.07 * speech_act_fit +
        0.07 * regulation_fit + 0.09 * script_continuity_fit + 0.05 * boundary_calibration_fit + 0.05 * intimacy_fit +
        0.06 * selected_counterpart_fit + 0.05 * overlay_activation_fit + 0.05 * relationship_state_fit
    )
    penalty = 0.17 * anti_pattern_risk + 0.05 * quote_regurgitation_risk + 0.05 * fiction_contamination_risk + 0.08 * rebind_contamination_risk
    if relationship_fit < 0.42 or intimacy_fit < 0.3:
        penalty += 0.08
    if script_continuity_fit < 0.42 and {"conflict", "repair", "boundary"}.intersection(scene_labels):
        penalty += 0.06
    if voice_similarity < 0.45 and draft.get("language_modes"):
        penalty += 0.05
    if anti_pattern_risk > 0.58:
        penalty += 0.1
    if selector_context.context_quality == "full" and selected_counterpart_fit < 0.46:
        penalty += 0.06
    if selector_context.context_quality == "full" and overlay_activation_fit < 0.44:
        penalty += 0.06
    in_character = _clip(base_score - penalty)

    veto_reasons: list[str] = []
    if anti_pattern_risk >= 0.72:
        veto_reasons.append("anti_pattern_overflow")
    if fiction_contamination_risk >= 0.55:
        veto_reasons.append("fiction_contamination")
    if quote_regurgitation_risk >= 0.55:
        veto_reasons.append("quote_regurgitation")
    if relationship_fit < 0.28 or intimacy_fit < 0.2:
        veto_reasons.append("relationship_miscalibration")
    if voice_similarity < 0.4 and anti_pattern_risk >= 0.5:
        veto_reasons.append("voice_collapse")
    if selector_context.context_quality == "full" and selected_counterpart_fit < 0.26:
        veto_reasons.append("selected_counterpart_drift")
    if selector_context.context_quality == "full" and rebind_contamination_risk >= 0.58:
        veto_reasons.append("rebind_contamination")
    hard_veto = bool(veto_reasons)
    if hard_veto:
        in_character = min(in_character, 0.41)

    reasons: list[str] = []
    problems: list[str] = []
    if voice_similarity >= 0.72:
        reasons.append("Surface rhythm, directness, and language mode are close to the imported profile.")
    if decision_similarity >= 0.72:
        reasons.append("The reply makes a familiar decision move for this person in the current scene.")
    if script_continuity_fit >= 0.7:
        reasons.append("The reply preserves the expected move order for this person's conflict or repair script.")
    if relationship_fit >= 0.72 and intimacy_fit >= 0.62:
        reasons.append("The closeness calibration stays within this person's usual warmth and distance band.")
    if selected_counterpart_fit >= 0.72 and selector_context.counterpart_entity_id:
        reasons.append("The reply stays focused on the selected counterpart lens instead of drifting into background relationships.")
    if overlay_activation_fit >= 0.72 and selector_context.overlay_key:
        reasons.append("The reply activates the counterpart-specific overlay instead of falling back to the generic base profile.")
    if relationship_state_fit >= 0.7 and selector_context.relationship_state_hints:
        reasons.append("Warmth, pressure, and distance line up with the supplied relationship-state hints.")
    if anti_pattern_risk >= 0.35:
        problems.append("The reply triggers one or more known anti-patterns.")
    if relationship_fit < 0.5:
        problems.append("The intimacy or pressure level is miscalibrated for this relationship scope.")
    if memory_consistency < 0.7:
        problems.append("The reply risks introducing unsupported detail.")
    if voice_similarity < 0.48 and draft.get("language_modes") and _infer_language(text) not in draft.get("language_modes"):
        problems.append("The reply drifts into an unsupported language mode for the imported voice.")
    if selector_context.context_quality == "degraded" and selector_context.degraded_context_reason:
        problems.append(f"Selector context is degraded: {selector_context.degraded_context_reason}")
    if relationship_state_fit < 0.5 and selector_context.relationship_state_hints:
        problems.append("The reply does not match the supplied relationship-state hints closely enough.")
    if rebind_contamination_risk >= 0.35 and selector_context.counterpart_entity_id:
        problems.append("The reply may be pulling tone or focus from another relationship lens.")
    if hard_veto:
        problems.append("The candidate hits a hard veto condition and should not be surfaced as-is.")
    if not reasons:
        reasons.append("The reply is broadly plausible but not yet strongly distinctive.")

    passes_identity_gate = in_character >= 0.62 and anti_pattern_risk < 0.45 and not hard_veto
    passes_relationship_gate = relationship_fit >= 0.48 and intimacy_fit >= 0.38 and boundary_calibration_fit >= 0.48
    if selector_context.context_quality == "full":
        passes_relationship_gate = (
            passes_relationship_gate and selected_counterpart_fit >= 0.54 and overlay_activation_fit >= 0.5 and
            relationship_state_fit >= 0.48 and rebind_contamination_risk < 0.45
        )
    elif selector_context.context_quality == "degraded":
        passes_relationship_gate = passes_relationship_gate and selected_counterpart_fit >= 0.46 and rebind_contamination_risk < 0.55
    passes_consistency_gate = memory_consistency >= 0.65 and fiction_contamination_risk < 0.45 and quote_regurgitation_risk < 0.4
    passes_style_gate = voice_similarity >= 0.48 and anti_pattern_risk < 0.48 and script_continuity_fit >= 0.4

    if hard_veto:
        recommended_action = "reject"
    elif passes_identity_gate and passes_relationship_gate and passes_consistency_gate and passes_style_gate and in_character >= 0.7:
        recommended_action = "accept"
    elif in_character >= 0.52 and anti_pattern_risk < 0.62:
        recommended_action = "rewrite"
    else:
        recommended_action = "reject"

    rewrite_hint = "Tighten the move order, reduce generic reassurance, and keep the boundary cleaner."
    if anti_pattern_risk < 0.3 and decision_similarity >= 0.75 and script_continuity_fit >= 0.72:
        rewrite_hint = "Only minor trimming is needed if you want it even closer to the imported voice."
    elif voice_similarity < 0.5 and draft.get("language_modes"):
        rewrite_hint = "Bring the reply back to the imported language mode and signature patterns before adjusting content."
    elif rebind_contamination_risk >= 0.35 and selector_context.counterpart_entity_id:
        rewrite_hint = "Refocus on the selected counterpart, remove third-party drift, and reactivate the current relationship overlay."

    result = {
        "judge_mode": "generation",
        "candidate_id": candidate_id,
        "in_character": round(in_character, 2),
        "voice_similarity": round(voice_similarity, 2),
        "decision_similarity": round(decision_similarity, 2),
        "relationship_fit": round(relationship_fit, 2),
        "scene_fit": round(scene_fit, 2),
        "memory_consistency": round(memory_consistency, 2),
        "value_order_fit": round(value_order_fit, 2),
        "interpersonal_stance_fit": round(interpersonal_stance_fit, 2),
        "speech_act_fit": round(speech_act_fit, 2),
        "regulation_fit": round(regulation_fit, 2),
        "script_continuity_fit": round(script_continuity_fit, 2),
        "opening_move_fit": round(opening_move_fit, 2),
        "closure_fit": round(closure_fit, 2),
        "boundary_calibration_fit": round(boundary_calibration_fit, 2),
        "intimacy_calibration_fit": round(intimacy_fit, 2),
        "selected_counterpart_fit": round(selected_counterpart_fit, 2),
        "overlay_activation_fit": round(overlay_activation_fit, 2),
        "relationship_state_fit": round(relationship_state_fit, 2),
        "rebind_contamination_risk": round(rebind_contamination_risk, 2),
        "context_quality": selector_context.context_quality,
        "anti_pattern_risk": round(anti_pattern_risk, 2),
        "quote_regurgitation_risk": round(quote_regurgitation_risk, 2),
        "fiction_contamination_risk": round(fiction_contamination_risk, 2),
        "passes_identity_gate": passes_identity_gate,
        "passes_relationship_gate": passes_relationship_gate,
        "passes_consistency_gate": passes_consistency_gate,
        "passes_style_gate": passes_style_gate,
        "hard_veto": hard_veto,
        "veto_reasons": veto_reasons,
        "recommended_action": recommended_action,
        "reasons": reasons,
        "problems": problems,
        "rewrite_hint": rewrite_hint,
    }
    if selector_context.degraded_context_reason:
        result["degraded_context_reason"] = selector_context.degraded_context_reason
    return result



def judge_candidates(*, service_root: str | None, draft: str, candidates: str, output_name: str | None = None) -> dict[str, Any]:
    root = _service_root(service_root)
    draft_payload = _load_json(Path(draft))
    candidate_payload = _load_json(Path(candidates))
    if not isinstance(draft_payload, dict):
        raise ServiceError("Base soul draft JSON must be an object.")
    if not isinstance(candidate_payload, dict):
        raise ServiceError("Candidate batch JSON must be an object.")
    candidate_rows = _require_list(candidate_payload, "candidates")
    scene_labels = [str(label) for label in (candidate_payload.get("scene_labels") or [])]
    relationship_scope = str(candidate_payload.get("relationship_scope") or "general")
    memory_hints = [str(item) for item in (candidate_payload.get("memory_hints") or [])]
    reference_quotes = [str(item) for item in (candidate_payload.get("reference_quotes") or [])]
    source_mode = str(candidate_payload.get("source_mode") or "chat_history")
    selector_context = _judge_selector_context(candidate_payload)

    results: list[dict[str, Any]] = []
    for row in candidate_rows:
        if not isinstance(row, dict):
            raise ServiceError("Each candidate entry must be an object.")
        results.append(_judge_one_candidate(
            candidate_id=_require_string(row, "candidate_id"),
            text=_require_string(row, "text"),
            draft=draft_payload,
            scene_labels=scene_labels,
            relationship_scope=relationship_scope,
            memory_hints=memory_hints,
            reference_quotes=reference_quotes,
            source_mode=source_mode,
            selector_context=selector_context,
        ))
    results.sort(key=lambda item: item["in_character"], reverse=True)

    twin_id = str(draft_payload.get("twin_id") or "ad_hoc")
    _ensure_service_dirs(root, twin_id)
    output_path = _soul_dir(root, twin_id) / f"{output_name or datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}.judge_results.json"
    payload = {
        "status": "ok",
        "mode": "judge_candidates",
        "twin_id": twin_id,
        "best_candidate_id": results[0]["candidate_id"] if results else None,
        "results": results,
    }
    _write_json(output_path, payload)
    payload["result_path"] = str(output_path)
    return payload

def run_request_envelope(*, request_payload: dict[str, Any], service_root_override: str | None = None) -> dict[str, Any]:
    operation = _require_string(request_payload, "operation")
    params = request_payload.get("params") or {}
    if not isinstance(params, dict):
        raise ServiceError("Request envelope 'params' must be an object.")
    service_root = service_root_override or params.get("service_root")

    if operation == "extract_chat_soul":
        return extract_chat_soul(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            source=_require_string(params, "source"),
            anchor_person_id=params.get("anchor_person_id"),
            output_name=params.get("output_name"),
        )
    if operation == "extract_work_soul":
        return extract_work_soul(
            service_root=service_root,
            twin_id=_require_string(params, "twin_id"),
            source=_require_string(params, "source"),
            anchor_person_id=params.get("anchor_person_id"),
            output_name=params.get("output_name"),
        )
    if operation == "judge_candidates":
        return judge_candidates(
            service_root=service_root,
            draft=_require_string(params, "draft"),
            candidates=_require_string(params, "candidates"),
            output_name=params.get("output_name"),
        )
    raise ServiceError(f"Unsupported operation: {operation}")


def cmd_extract_chat_soul(args: argparse.Namespace) -> int:
    _print_json(extract_chat_soul(
        service_root=args.service_root,
        twin_id=args.twin_id,
        source=args.source,
        anchor_person_id=args.anchor_person_id,
        output_name=args.output_name,
    ))
    return 0


def cmd_extract_work_soul(args: argparse.Namespace) -> int:
    _print_json(extract_work_soul(
        service_root=args.service_root,
        twin_id=args.twin_id,
        source=args.source,
        anchor_person_id=args.anchor_person_id,
        output_name=args.output_name,
    ))
    return 0


def cmd_judge_candidates(args: argparse.Namespace) -> int:
    _print_json(judge_candidates(
        service_root=args.service_root,
        draft=args.draft,
        candidates=args.candidates,
        output_name=args.output_name,
    ))
    return 0


def cmd_run_request(args: argparse.Namespace) -> int:
    payload = _load_json(Path(args.request))
    if not isinstance(payload, dict):
        raise ServiceError("Request envelope JSON must be an object.")
    result = run_request_envelope(request_payload=payload, service_root_override=args.service_root)
    if args.response:
        _write_json(Path(args.response), result)
    else:
        _print_json(result)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="soul_extractor", description="Standalone soul initialization and judging module for digital-twin imports.")
    parser.add_argument("--service-root", default=None, help="Override the service root directory. Defaults to .opencray/personality_service.")
    sub = parser.add_subparsers(dest="command", required=True)

    chat_p = sub.add_parser("extract_chat_soul", help="Extract soul signals and a base soul draft from chat corpus.")
    chat_p.add_argument("--twin-id", required=True)
    chat_p.add_argument("--source", required=True)
    chat_p.add_argument("--anchor-person-id")
    chat_p.add_argument("--output-name")
    chat_p.set_defaults(func=cmd_extract_chat_soul)

    work_p = sub.add_parser("extract_work_soul", help="Extract soul signals and a base soul draft from work corpus.")
    work_p.add_argument("--twin-id", required=True)
    work_p.add_argument("--source", required=True)
    work_p.add_argument("--anchor-person-id")
    work_p.add_argument("--output-name")
    work_p.set_defaults(func=cmd_extract_work_soul)

    judge_p = sub.add_parser("judge_candidates", help="Judge candidate replies against an imported base soul draft.")
    judge_p.add_argument("--draft", required=True)
    judge_p.add_argument("--candidates", required=True)
    judge_p.add_argument("--output-name")
    judge_p.set_defaults(func=cmd_judge_candidates)

    request_p = sub.add_parser("run_request", help="Execute one request envelope for local file-bridge integration.")
    request_p.add_argument("--request", required=True)
    request_p.add_argument("--response")
    request_p.set_defaults(func=cmd_run_request)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))
    try:
        return args.func(args)
    except ServiceError as exc:
        print(json.dumps({"status": "error", "error": str(exc)}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
