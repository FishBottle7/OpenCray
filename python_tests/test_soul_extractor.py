import importlib.util
import json
import shutil
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any
from uuid import uuid4

import pytest


def _load_module():
    repo_root = Path(__file__).resolve().parents[1]
    module_path = repo_root / "service" / "soul_extractor.py"
    spec = importlib.util.spec_from_file_location("soul_extractor", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def local_workspace() -> Path:
    repo_root = Path(__file__).resolve().parents[1]
    base_dir = repo_root / ".pytest_local"
    base_dir.mkdir(exist_ok=True)
    path = base_dir / f"workspace_{uuid4().hex}"
    path.mkdir()
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def _group_signals_by_turn(signals: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for signal in signals:
        turn_id = signal.get("source_ref", {}).get("turn_id")
        if turn_id:
            grouped[turn_id].append(signal)
    return grouped


def _contains_counterpart_metadata(value: Any) -> bool:
    if isinstance(value, dict):
        if "counterpart_id" in value or "counterpart_name" in value:
            return True
        return any(_contains_counterpart_metadata(child) for child in value.values())
    if isinstance(value, list):
        return any(_contains_counterpart_metadata(item) for item in value)
    return False


def test_extract_chat_soul_writes_signal_and_draft_files(local_workspace):
    soul_extractor = _load_module()
    source_path = local_workspace / "chat.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_alpha",
                "title": "sample chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "对不起，是我答应了你又没做到。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "text": "我不是在生气，只是你答应的事又没做到，我有点累，今天先这样吧。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "boundary"],
                        "reply_to_turn_id": "turn_01",
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "好，那你先休息，明天我补上。",
                        "timestamp": "2025-01-03T21:12:38+08:00",
                        "labels": ["repair"],
                        "reply_to_turn_id": "turn_02",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.extract_chat_soul(
        service_root=str(service_root),
        twin_id="twin_lin_01",
        source=str(source_path),
        anchor_person_id="actor_lin",
        output_name="unit_test",
    )

    assert result["status"] == "ok"
    draft_path = Path(result["draft_path"])
    signals_path = Path(result["signals_path"])
    assert draft_path.exists()
    assert signals_path.exists()

    draft = json.loads(draft_path.read_text(encoding="utf-8"))
    signals = json.loads(signals_path.read_text(encoding="utf-8"))
    signal_types = {signal["signal_type"] for signal in signals}

    assert draft["anchor_person_id"] == "actor_lin"
    assert draft["conflict_policy"]["default"] == "explain_then_withdraw"
    assert draft["value_order"]
    assert draft["anti_patterns"]
    assert draft["appraisal_tendencies"]
    assert draft["value_tradeoffs"]
    assert draft["worldview_stack"]["schwartz_value_order"]
    assert draft["worldview_stack"]["truth_vs_face_saving_bias"]
    assert draft["idiolect"]["punctuation_rhythm"]["dominant_style"]
    assert draft["idiolect"]["marker_clusters"]
    assert draft["conditional_policies"]
    assert any(policy["opening_context"] == "repair_offer" for policy in draft["conditional_policies"])
    assert {"event_semantics", "appraisal_hint", "value_tradeoff_hint", "opening_move_hint", "closure_move_hint"}.issubset(signal_types)


def test_extract_chat_soul_accepts_chatlab_jsonl_and_skips_non_text_messages(local_workspace):
    soul_extractor = _load_module()
    source_path = local_workspace / "chatlab.jsonl"
    source_path.write_text(
        "\n".join(
            [
                json.dumps(
                    {
                        "_type": "header",
                        "chatlab": {"version": "1"},
                        "meta": {"name": "Lin x User", "groupId": "chatlab_lin_user", "type": "private"},
                    },
                    ensure_ascii=False,
                ),
                json.dumps({"_type": "member", "platformId": "actor_lin", "accountName": "Lin"}, ensure_ascii=False),
                json.dumps({"_type": "member", "platformId": "actor_user", "accountName": "User"}, ensure_ascii=False),
                json.dumps(
                    {
                        "_type": "message",
                        "sender": "actor_user",
                        "accountName": "User",
                        "timestamp": 1735910400,
                        "type": 0,
                        "content": "我会补上。",
                    },
                    ensure_ascii=False,
                ),
                json.dumps(
                    {
                        "_type": "message",
                        "sender": "actor_lin",
                        "accountName": "Lin",
                        "timestamp": 1735910414,
                        "type": 0,
                        "content": "我知道，但我现在有点累。",
                    },
                    ensure_ascii=False,
                ),
                json.dumps(
                    {
                        "_type": "message",
                        "sender": "actor_user",
                        "accountName": "User",
                        "timestamp": 1735910420,
                        "type": 1,
                        "content": None,
                    },
                    ensure_ascii=False,
                ),
            ]
        ) + "\n",
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.extract_chat_soul(
        service_root=str(service_root),
        twin_id="twin_chatlab_01",
        source=str(source_path),
        anchor_person_id="actor_lin",
        output_name="chatlab_jsonl",
    )

    assert result["status"] == "ok"
    assert result["source_id"] == "chatlab_lin_user"
    signals = json.loads(Path(result["signals_path"]).read_text(encoding="utf-8"))
    grouped = _group_signals_by_turn(signals)
    assert "turn_000002" in grouped
    assert "turn_000003" not in grouped
    assert all(signal["source_ref"]["turn_id"] != "turn_000003" for signal in signals)

def test_extract_chat_soul_infers_reply_link_when_missing(local_workspace):
    soul_extractor = _load_module()
    source_path = local_workspace / "chat_reply_infer.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_reply_infer",
                "title": "reply inference",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "我会尽快把事情做完。",
                        "timestamp": "2025-01-04T12:00:00+08:00",
                        "labels": ["commitment"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "text": "好的，希望你能守信。",
                        "timestamp": "2025-01-04T12:00:20+08:00",
                        "labels": ["boundary"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "谢谢你提醒，今天一定完成。",
                        "timestamp": "2025-01-04T12:00:40+08:00",
                        "labels": ["repair"],
                        "reply_to_turn_id": "turn_02",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.extract_chat_soul(
        service_root=str(service_root),
        twin_id="twin_lin_reply",
        source=str(source_path),
        anchor_person_id="actor_lin",
        output_name="reply_infer",
    )

    signals = json.loads(Path(result["signals_path"]).read_text(encoding="utf-8"))
    grouped = _group_signals_by_turn(signals)
    assert "turn_02" in grouped, "turn_02 signals should be present"
    inferred_ref = grouped["turn_02"][0]["source_ref"]
    assert inferred_ref["reply_to_turn_id"] == "turn_01"
    assert inferred_ref["reply_link_inferred"] is True


def test_extract_chat_soul_turn_window_signals_include_window_context(local_workspace):
    soul_extractor = _load_module()
    source_path = local_workspace / "chat_window.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_beta",
                "title": "window chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "对不起，你别不理我，我明天补上。",
                        "timestamp": "2025-01-03T21:10:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "text": "我不是在生气，只是很累，今天先这样吧。",
                        "timestamp": "2025-01-03T21:10:20+08:00",
                        "labels": ["conflict", "boundary"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "好，那你先休息。",
                        "timestamp": "2025-01-03T21:10:32+08:00",
                        "labels": ["repair"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.extract_chat_soul(
        service_root=str(service_root),
        twin_id="twin_lin_02",
        source=str(source_path),
        anchor_person_id="actor_lin",
        output_name="window_test",
    )

    signals = json.loads(Path(result["signals_path"]).read_text(encoding="utf-8"))
    window_signals = [signal for signal in signals if signal["signal_type"] in {"opening_move_hint", "follow_up_move_hint", "closure_move_hint"}]
    assert window_signals
    assert all(signal["source_ref"].get("window_id") for signal in window_signals)
    assert any(signal["signal_value"]["opening_context"] == "repair_offer" for signal in window_signals)


def test_extract_chat_soul_tracks_recurrence_and_overlay(local_workspace):
    soul_extractor = _load_module()
    source_path = local_workspace / "chat_recurrence.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_recurrence",
                "title": "recurrence check",
                "conversation_id": "shared",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_a1",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "这周还没有完成，我会加紧处理。",
                        "timestamp": "2025-01-05T18:00:00+08:00",
                        "labels": ["conflict", "boundary"],
                        "conversation_id": "conv_a",
                    },
                    {
                        "turn_id": "turn_a2",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "text": "别着急，但是请早点给我回音。",
                        "timestamp": "2025-01-05T18:00:20+08:00",
                        "labels": ["boundary"],
                        "conversation_id": "conv_a",
                        "reply_to_turn_id": "turn_a1",
                    },
                    {
                        "turn_id": "turn_b1",
                        "speaker": "User",
                        "speaker_id": "actor_user",
                        "text": "我已经调整好了日程，后天再确认。",
                        "timestamp": "2025-01-06T09:00:00+08:00",
                        "labels": ["conflict", "repair"],
                        "conversation_id": "conv_b",
                    },
                    {
                        "turn_id": "turn_b2",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "text": "这样我才安心，记得按时回复。",
                        "timestamp": "2025-01-06T09:00:15+08:00",
                        "labels": ["repair"],
                        "conversation_id": "conv_b",
                        "reply_to_turn_id": "turn_b1",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.extract_chat_soul(
        service_root=str(service_root),
        twin_id="twin_lin_recurrence",
        source=str(source_path),
        anchor_person_id="actor_lin",
        output_name="recurrence_test",
    )

    draft = json.loads(Path(result["draft_path"]).read_text(encoding="utf-8"))
    assert "narrative_tendencies" in draft and draft["narrative_tendencies"], "Narrative tendencies should be populated"
    recurrence_keys = {
        "distinct_day_count",
        "distinct_conversation_count",
        "distinct_counterpart_count",
        "recurrence_score",
        "relationship_concentration",
        "stability_score",
    }
    assert draft["conditional_policies"], "Conditional policies should exist to measure recurrence"
    conditional = draft["conditional_policies"][0]
    assert recurrence_keys.issubset(conditional.keys()), "Conditional policies should include recurrence metrics"
    assert conditional["distinct_day_count"] >= 2
    assert draft["relational_scripts"], "Relational scripts should exist to measure recurrence"
    relational = draft["relational_scripts"][0]
    assert recurrence_keys.issubset(relational.keys()), "Relational scripts should include recurrence metrics"
    assert relational["distinct_conversation_count"] >= 2

    overlay_path = result.get("relationship_overlay_path")
    assert overlay_path, "Extracted soul should emit a relationship overlay draft path"
    overlay = json.loads(Path(overlay_path).read_text(encoding="utf-8"))
    assert _contains_counterpart_metadata(overlay), "Overlay should record counterpart-specific metadata"


def test_judge_candidates_prefers_in_character_reply(local_workspace):
    soul_extractor = _load_module()
    draft_path = local_workspace / "draft.json"
    draft_path.write_text(
        json.dumps(
            {
                "twin_id": "twin_lin_01",
                "language_modes": {
                    "zh-CN": {
                        "surface_confidence": 0.84,
                        "directness_shift": "softened_direct",
                        "emoji_density": "low",
                        "sentence_length": "short_medium",
                        "punctuation_style": "light",
                        "signature_patterns": ["不是...是..."],
                    }
                },
                "speech_surface": {
                    "sentence_length": "short_medium",
                    "punctuation_style": "light",
                    "emoji_density": "low",
                    "directness_level": "softened_direct",
                    "signature_patterns": ["不是...是..."],
                },
                "judgment_policy": {
                    "fact_vs_feeling": "feeling_first_then_reason",
                    "certainty_style": "admits_uncertainty",
                    "dominant_appraisal": {
                        "self_state": "tired_overloaded",
                        "other_appraisal": "unreliable_or_inconsistent",
                        "core_need": "space_and_regulation",
                    },
                },
                "interpersonal_stance": {"agency": "medium", "communion": "high"},
                "conflict_policy": {
                    "default": "explain_then_withdraw",
                    "boundary_style": "clear_but_not_hostile",
                },
                "affection_policy": {"mode": "restrained_indirect"},
                "conditional_policies": [
                    {
                        "when": ["conflict", "boundary", "repair_offer"],
                        "move_sequence": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "confidence": 0.82,
                        "opening_context": "repair_offer",
                        "outcome_tendency": "returns_after_cooldown",
                        "observations": 2,
                    }
                ],
                "relational_scripts": [
                    {
                        "name": "script_1",
                        "trigger": ["conflict", "boundary", "repair_offer"],
                        "moves": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "scene_scope": "conflict",
                        "confidence": 0.82,
                        "opening_context": "repair_offer",
                        "outcome_tendency": "returns_after_cooldown",
                        "observations": 2,
                    }
                ],
                "value_order": ["truth", "fairness", "autonomy"],
                "value_tradeoffs": [
                    {
                        "pair": ["fairness", "autonomy"],
                        "favored": "fairness",
                        "resolution": "keep_accountability_without_escalation",
                        "confidence": 0.81,
                        "observations": 2,
                    }
                ],
                "anti_patterns": [
                    "high_emoji_gush",
                    "generic_therapy_tone",
                    "salesy_encouragement",
                    "overly_formal_ai_style",
                    "aggressive_personal_attack",
                    "overpunctuated_exclamation_style",
                    "imperative_pressure_spiral",
                    "absolute_hostility",
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    candidates_path = local_workspace / "candidates.json"
    candidates_path.write_text(
        json.dumps(
            {
                "scene_labels": ["conflict", "boundary"],
                "relationship_scope": "close_relationship",
                "source_mode": "chat_history",
                "candidates": [
                    {"candidate_id": "cand_01", "text": "我不是在生气，只是有点累。今天先到这里吧。"},
                    {"candidate_id": "cand_02", "text": "你真的太过分了！！！我再也不想理你了！！！"},
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.judge_candidates(
        service_root=str(service_root),
        draft=str(draft_path),
        candidates=str(candidates_path),
        output_name="judge_test",
    )

    assert result["status"] == "ok"
    assert result["best_candidate_id"] == "cand_01"
    assert result["results"][0]["candidate_id"] == "cand_01"
    assert result["results"][0]["in_character"] > result["results"][1]["in_character"]
    assert result["results"][0]["recommended_action"] == "accept"
    assert result["results"][1]["recommended_action"] == "reject"
    assert result["results"][1]["hard_veto"] is True


def test_judge_candidates_penalizes_order_reversal_and_language_drift(local_workspace):
    soul_extractor = _load_module()
    draft_path = local_workspace / "draft_order.json"
    draft_path.write_text(
        json.dumps(
            {
                "twin_id": "twin_lin_02",
                "language_modes": {
                    "zh-CN": {
                        "surface_confidence": 0.9,
                        "directness_shift": "softened_direct",
                        "emoji_density": "low",
                        "sentence_length": "short_medium",
                        "punctuation_style": "light",
                        "signature_patterns": ["不是...是..."],
                    }
                },
                "speech_surface": {
                    "sentence_length": "short_medium",
                    "punctuation_style": "light",
                    "emoji_density": "low",
                    "directness_level": "softened_direct",
                    "signature_patterns": ["不是...是..."],
                },
                "judgment_policy": {
                    "fact_vs_feeling": "feeling_first_then_reason",
                    "certainty_style": "admits_uncertainty",
                },
                "interpersonal_stance": {"agency": "medium", "communion": "high"},
                "conflict_policy": {
                    "default": "explain_then_withdraw",
                    "boundary_style": "clear_but_not_hostile",
                },
                "affection_policy": {"mode": "restrained_indirect"},
                "conditional_policies": [
                    {
                        "when": ["conflict", "boundary"],
                        "move_sequence": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "confidence": 0.84,
                    }
                ],
                "relational_scripts": [
                    {
                        "name": "script_1",
                        "trigger": ["conflict", "boundary"],
                        "moves": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "scene_scope": "conflict",
                        "confidence": 0.84,
                    }
                ],
                "value_order": ["truth", "autonomy", "care"],
                "value_tradeoffs": [
                    {
                        "pair": ["care", "autonomy"],
                        "favored": "autonomy",
                        "resolution": "soft_boundary_preserving_bond",
                        "confidence": 0.78,
                        "observations": 2,
                    }
                ],
                "anti_patterns": [
                    "generic_therapy_tone",
                    "salesy_encouragement",
                    "overly_formal_ai_style",
                    "overpunctuated_exclamation_style",
                    "imperative_pressure_spiral",
                    "absolute_hostility",
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    candidates_path = local_workspace / "candidates_order.json"
    candidates_path.write_text(
        json.dumps(
            {
                "scene_labels": ["conflict", "boundary"],
                "relationship_scope": "close_relationship",
                "source_mode": "chat_history",
                "candidates": [
                    {"candidate_id": "cand_good", "text": "我不是在生气，只是有点累。今天先这样吧。"},
                    {"candidate_id": "cand_reversed", "text": "今天先这样吧，我不是在生气，只是有点累。"},
                    {"candidate_id": "cand_drift", "text": "Based on your description, I suggest you allow yourself to heal forever!!! You deserve the best version of yourself forever!!!"},
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.judge_candidates(
        service_root=str(service_root),
        draft=str(draft_path),
        candidates=str(candidates_path),
        output_name="judge_order_test",
    )

    by_id = {row["candidate_id"]: row for row in result["results"]}
    assert by_id["cand_good"]["script_continuity_fit"] > by_id["cand_reversed"]["script_continuity_fit"]
    assert by_id["cand_good"]["in_character"] > by_id["cand_reversed"]["in_character"]
    assert by_id["cand_drift"]["recommended_action"] == "reject"
    assert by_id["cand_drift"]["hard_veto"] is True
    assert "veto_reasons" in by_id["cand_drift"]


def test_judge_candidates_selector_context_prioritizes_selected_relationship(local_workspace):
    soul_extractor = _load_module()
    draft_path = local_workspace / "draft_selector.json"
    draft_path.write_text(
        json.dumps(
            {
                "twin_id": "twin_lin_selector",
                "language_modes": {
                    "zh-CN": {"surface_confidence": 0.88, "directness_shift": "softened_direct"}
                },
                "speech_surface": {"punctuation_style": "light", "directness_level": "softened_direct"},
                "judgment_policy": {"fact_vs_feeling": "feeling_first_then_reason"},
                "interpersonal_stance": {"agency": "medium", "communion": "high"},
                "conflict_policy": {"default": "explain_then_withdraw", "boundary_style": "clear_but_not_hostile"},
                "conditional_policies": [
                    {
                        "when": ["conflict", "boundary"],
                        "move_sequence": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                    }
                ],
                "relational_scripts": [
                    {
                        "trigger": ["conflict", "boundary"],
                        "moves": ["clarify_feeling", "withdraw"],
                        "relationship_scope": "close_relationship",
                    }
                ],
                "value_order": ["truth", "care", "autonomy"],
                "anti_patterns": ["third_person_drift", "high_emoji_gush"],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    candidates_path = local_workspace / "candidates_selector.json"
    candidates_path.write_text(
        json.dumps(
            {
                "scene_labels": ["conflict", "boundary"],
                "relationship_scope": "close_relationship",
                "source_mode": "chat_history",
                "selected_relationship_binding_id": "binding_lin_hero",
                "counterpart_entity_id": "actor_lin",
                "overlay_key": "lin_overlay",
                "relationship_state_hints": {"pressure": "moderate", "warmth": "calm", "distance": "close"},
                "interaction_preference_hints": {"tone": "calm", "pace": "measured"},
                "recent_script_hints": ["clarify_feeling", "withdraw"],
                "candidates": [
                    {
                        "candidate_id": "cand_lens",
                        "text": "我知道你最近把所有压力都揽在自己身上，我先退一步，让你有空间调整，等你准备好了我们再聊清楚，早点告诉我你的想法。",
                    },
                    {
                        "candidate_id": "cand_drift",
                        "text": "她昨天说你又找别人帮忙了，让她来收尾这件事吧，等她回头再说，再叫他把别的人都安抚好我再告诉你最新情况，她说他们都等她通知你，she asked him to finish it before talking to you.",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.judge_candidates(
        service_root=str(service_root),
        draft=str(draft_path),
        candidates=str(candidates_path),
        output_name="judge_selector_test",
    )

    assert result["status"] == "ok"
    assert result["best_candidate_id"] == "cand_lens"
    by_id = {row["candidate_id"]: row for row in result["results"]}
    assert by_id["cand_lens"]["context_quality"] == "full"
    assert by_id["cand_lens"]["selected_counterpart_fit"] > by_id["cand_drift"]["selected_counterpart_fit"]
    assert by_id["cand_lens"]["overlay_activation_fit"] >= 0.5
    assert by_id["cand_lens"]["relationship_state_fit"] >= 0.5
    assert by_id["cand_drift"]["rebind_contamination_risk"] >= 0.32
    assert "rebind_contamination" in by_id["cand_drift"]["veto_reasons"]
    assert by_id["cand_lens"]["recommended_action"] in {"accept", "rewrite"}
    assert by_id["cand_drift"]["recommended_action"] == "reject"



def test_judge_candidates_selector_context_prefers_selected_relationship(local_workspace):
    soul_extractor = _load_module()
    draft_path = local_workspace / "draft_selector.json"
    draft_path.write_text(
        json.dumps(
            {
                "twin_id": "twin_lin_selector",
                "language_modes": {
                    "zh-CN": {
                        "surface_confidence": 0.9,
                        "directness_shift": "softened_direct",
                        "emoji_density": "low",
                        "sentence_length": "short_medium",
                        "punctuation_style": "light",
                        "signature_patterns": ["不是...是..."],
                    }
                },
                "speech_surface": {
                    "sentence_length": "short_medium",
                    "punctuation_style": "light",
                    "emoji_density": "low",
                    "directness_level": "softened_direct",
                    "signature_patterns": ["不是...是..."],
                },
                "judgment_policy": {
                    "fact_vs_feeling": "feeling_first_then_reason",
                    "certainty_style": "admits_uncertainty",
                },
                "interpersonal_stance": {"agency": "medium", "communion": "high"},
                "conflict_policy": {
                    "default": "explain_then_withdraw",
                    "boundary_style": "clear_but_not_hostile",
                },
                "affection_policy": {"mode": "restrained_indirect"},
                "conditional_policies": [
                    {
                        "when": ["conflict", "boundary"],
                        "move_sequence": ["clarify_feeling", "state_limit", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "confidence": 0.87,
                    }
                ],
                "relational_scripts": [
                    {
                        "name": "script_selector",
                        "trigger": ["conflict", "boundary"],
                        "moves": ["clarify_feeling", "state_limit", "withdraw"],
                        "relationship_scope": "close_relationship",
                        "scene_scope": "conflict",
                        "confidence": 0.87,
                    }
                ],
                "value_order": ["truth", "autonomy", "care"],
                "value_tradeoffs": [
                    {
                        "pair": ["care", "autonomy"],
                        "favored": "autonomy",
                        "resolution": "soft_boundary_preserving_bond",
                        "confidence": 0.81,
                        "observations": 3,
                    }
                ],
                "anti_patterns": [
                    "generic_therapy_tone",
                    "salesy_encouragement",
                    "overly_formal_ai_style",
                    "aggressive_personal_attack",
                    "overpunctuated_exclamation_style",
                    "imperative_pressure_spiral",
                    "absolute_hostility",
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    candidates_path = local_workspace / "candidates_selector.json"
    candidates_path.write_text(
        json.dumps(
            {
                "scene_labels": ["conflict", "boundary"],
                "relationship_scope": "close_relationship",
                "selected_relationship_binding_id": "relbind_twin_lin_selector_actor_user_01",
                "counterpart_entity_id": "actor_user",
                "overlay_key": "counterpart:actor_user",
                "relationship_state_hints": {
                    "familiarity": "high",
                    "trust": "medium",
                    "safety": "medium",
                },
                "interaction_preference_hints": {
                    "warmth": "restrained",
                    "formality": "low",
                },
                "recent_script_hints": ["clarify_feeling -> state_limit -> withdraw"],
                "source_mode": "chat_history",
                "candidates": [
                    {
                        "candidate_id": "cand_focus",
                        "text": "我不是在生气，只是有点累。今天别再聊这个了，先这样吧。",
                    },
                    {
                        "candidate_id": "cand_drift",
                        "text": "She looked at him for a long time. I don't want to deal with that again.",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    service_root = local_workspace / ".opencray" / "personality_service"
    result = soul_extractor.judge_candidates(
        service_root=str(service_root),
        draft=str(draft_path),
        candidates=str(candidates_path),
        output_name="judge_selector_test",
    )

    assert result["status"] == "ok"
    assert result["best_candidate_id"] == "cand_focus"

    by_id = {row["candidate_id"]: row for row in result["results"]}
    focus = by_id["cand_focus"]
    drift = by_id["cand_drift"]

    assert focus["context_quality"] == "full"
    assert drift["context_quality"] == "full"
    assert focus["selected_counterpart_fit"] > drift["selected_counterpart_fit"]
    assert focus["overlay_activation_fit"] > drift["overlay_activation_fit"]
    assert focus["relationship_state_fit"] >= drift["relationship_state_fit"]
    assert focus["rebind_contamination_risk"] < drift["rebind_contamination_risk"]
    assert focus["recommended_action"] in {"accept", "rewrite"}
    assert drift["recommended_action"] == "reject"
    assert any("relationship lens" in problem for problem in drift["problems"]) or "rebind_contamination" in drift["veto_reasons"]


def _feedback_draft_payload() -> dict[str, Any]:
    return {
        "twin_id": "twin_feedback_lin",
        "language_modes": {
            "zh-CN": {
                "surface_confidence": 0.88,
                "directness_shift": "softened_direct",
                "emoji_density": "low",
                "sentence_length": "short_medium",
                "punctuation_style": "light",
                "signature_patterns": ["不是...是..."],
            }
        },
        "speech_surface": {
            "sentence_length": "short_medium",
            "punctuation_style": "light",
            "emoji_density": "low",
            "directness_level": "softened_direct",
            "signature_patterns": ["不是...是..."],
        },
        "judgment_policy": {
            "fact_vs_feeling": "feeling_first_then_reason",
            "certainty_style": "admits_uncertainty",
            "dominant_appraisal": {
                "self_state": "tired_overloaded",
                "other_appraisal": "unreliable_or_inconsistent",
                "core_need": "space_and_regulation",
            },
        },
        "interpersonal_stance": {"agency": "medium", "communion": "high"},
        "conflict_policy": {
            "default": "explain_then_withdraw",
            "boundary_style": "clear_but_not_hostile",
            "repair_style": "returns_after_cooldown",
        },
        "affection_policy": {"mode": "restrained_indirect"},
        "value_order": ["truth", "fairness", "autonomy"],
        "social_value_orientation": "prosocial_but_bounded",
        "conditional_policies": [
            {
                "when": ["conflict", "boundary", "repair_offer"],
                "move_sequence": ["clarify_feeling", "withdraw"],
                "relationship_scope": "close_relationship",
                "confidence": 0.82,
            }
        ],
        "relational_scripts": [
            {
                "name": "script_feedback",
                "trigger": ["conflict", "boundary", "repair_offer"],
                "moves": ["clarify_feeling", "withdraw"],
                "relationship_scope": "close_relationship",
                "scene_scope": "conflict",
                "confidence": 0.82,
            }
        ],
        "anti_patterns": [
            "generic_therapy_tone",
            "salesy_encouragement",
            "overly_formal_ai_style",
            "aggressive_personal_attack",
        ],
    }


def test_feedback_variation_ops_accept_inline_payloads(local_workspace):
    soul_extractor = _load_module()
    service_root = local_workspace / ".opencray" / "personality_service"
    draft_payload = _feedback_draft_payload()
    context_payload = {
        "scene_labels": ["conflict", "boundary"],
        "relationship_scope": "close_relationship",
        "source_mode": "chat_history",
        "draft_text": "我不是在生气，只是现在有点累。今天先这样吧。",
        "selected_relationship_binding_id": "binding_lin_user",
        "counterpart_entity_id": "actor_user",
        "overlay_key": "counterpart:actor_user",
        "relationship_state_hints": {"warmth": "calm", "distance": "close", "pressure": "moderate"},
        "interaction_preference_hints": {"tone": "calm", "pace": "measured"},
        "recent_script_hints": ["clarify_feeling", "withdraw"],
    }

    planned = soul_extractor.run_request_envelope(
        request_payload={
            "operation": "plan_feedback_variations",
            "params": {
                "service_root": str(service_root),
                "draft_payload": draft_payload,
                "context_payload": context_payload,
                "output_name": "feedback_plan_inline",
            },
        }
    )

    assert planned["status"] == "ok"
    assert Path(planned["result_path"]).exists()
    variation_context = planned["variation_context"]
    assert variation_context["candidate_specs"]
    assert variation_context["base_axes"]["closure_softness"] in {"balanced", "soft"}

    sampled = soul_extractor.run_request_envelope(
        request_payload={
            "operation": "sample_feedback_candidates",
            "params": {
                "service_root": str(service_root),
                "draft_payload": draft_payload,
                "context_payload": variation_context,
                "output_name": "feedback_sample_inline",
            },
        }
    )

    assert sampled["status"] == "ok"
    assert Path(sampled["result_path"]).exists()
    assert len(sampled["candidates"]) >= 2
    assert all(candidate["text"] for candidate in sampled["candidates"])
    assert sampled["candidate_batch"]["candidates"][0]["candidate_id"] == sampled["candidates"][0]["candidate_id"]


def test_prepare_feedback_review_candidates_returns_ui_ready_cards(local_workspace):
    soul_extractor = _load_module()
    service_root = local_workspace / ".opencray" / "personality_service"
    draft_payload = _feedback_draft_payload()
    context_payload = {
        "scene_labels": ["conflict", "boundary"],
        "relationship_scope": "close_relationship",
        "source_mode": "chat_history",
        "draft_text": "我不是在生气，只是现在有点累。今天先这样吧。",
        "variation_axes": ["closure_softness", "directness", "warmth"],
        "selected_relationship_binding_id": "binding_lin_user",
        "counterpart_entity_id": "actor_user",
        "overlay_key": "counterpart:actor_user",
        "relationship_state_hints": {"warmth": "calm", "distance": "close", "pressure": "moderate"},
        "interaction_preference_hints": {"tone": "calm", "pace": "measured"},
        "recent_script_hints": ["clarify_feeling", "withdraw"],
        "memory_hints": ["最近对失约比较敏感"],
        "reference_quotes": ["我不是在生气，只是现在有点累。"],
    }

    result = soul_extractor.run_request_envelope(
        request_payload={
            "operation": "prepare_feedback_review_candidates",
            "params": {
                "service_root": str(service_root),
                "draft_payload": draft_payload,
                "context_payload": context_payload,
                "output_name": "feedback_review_inline",
            },
        }
    )

    assert result["status"] == "ok"
    assert Path(result["result_path"]).exists()
    assert result["best_candidate_id"]
    assert result["candidates"]
    assert all(card["text"] for card in result["candidates"])
    assert all(card["judge"] for card in result["candidates"])
    best_card = next(card for card in result["candidates"] if card["candidate_id"] == result["best_candidate_id"])
    assert best_card["judge"]["recommended_action"] in {"accept", "rewrite"}
    assert any(card["focus_axis"] for card in result["candidates"][1:])
