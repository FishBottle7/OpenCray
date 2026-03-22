import asyncio
import importlib.util
import json
import shutil
import sys
from pathlib import Path
from uuid import uuid4

import pytest


def _load_module():
    repo_root = Path(__file__).resolve().parents[1]
    module_path = repo_root / "service" / "graphiti_adapter.py"
    spec = importlib.util.spec_from_file_location("graphiti_adapter", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _service_root(workspace: Path) -> Path:
    return workspace / ".opencray" / "personality_service"


class _FakeGraphitiNode:
    def __init__(self, *, uuid: str, entity_id: str, display_name: str):
        self.uuid = uuid
        self.entity_id = entity_id
        self.display_name = display_name
        self.name = display_name


class _FakeGraphitiEdge:
    def __init__(
        self,
        *,
        source_node_uuid: str,
        target_node_uuid: str,
        source_entity_id: str,
        target_entity_id: str,
        source_node_name: str,
        target_node_name: str,
        fact: str,
    ):
        self.source_node_uuid = source_node_uuid
        self.target_node_uuid = target_node_uuid
        self.source_entity_id = source_entity_id
        self.target_entity_id = target_entity_id
        self.source_node_name = source_node_name
        self.target_node_name = target_node_name
        self.fact = fact


class _FakeGraphiti:
    def __init__(
        self,
        *,
        search_nodes_by_query: dict[str, list[object]] | None = None,
        neighbors: list[object] | None = None,
        search_results: list[object] | None = None,
    ):
        self.search_nodes_by_query = search_nodes_by_query or {}
        self.neighbors = neighbors or []
        self.search_results = search_results or []
        self.search_node_calls: list[dict[str, object]] = []
        self.neighbor_calls: list[dict[str, object]] = []
        self.search_calls: list[dict[str, object]] = []

    async def search_nodes(self, query: str, group_id: str, limit: int = 8):
        self.search_node_calls.append({"query": query, "group_id": group_id, "limit": limit})
        return list(self.search_nodes_by_query.get(query, []))

    async def get_neighbors(self, focal_node_uuid: str, group_id: str, max_depth: int = 2, limit: int = 32):
        self.neighbor_calls.append(
            {
                "focal_node_uuid": focal_node_uuid,
                "group_id": group_id,
                "max_depth": max_depth,
                "limit": limit,
            }
        )
        return {"edges": list(self.neighbors)}

    async def search(self, query: str, group_id: str, focal_node_uuid: str | None = None):
        self.search_calls.append(
            {
                "query": query,
                "group_id": group_id,
                "focal_node_uuid": focal_node_uuid,
            }
        )
        return list(self.search_results)


@pytest.fixture
def workspace() -> Path:
    repo_root = Path(__file__).resolve().parents[1]
    base_dir = repo_root / ".pytest_local"
    base_dir.mkdir(exist_ok=True)
    path = base_dir / f"workspace_{uuid4().hex}"
    path.mkdir()
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def test_preflight_scan_chat_enumerates_anchor_and_counterpart_candidates(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_alpha",
                "title": "sample chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                    {"entity_id": "actor_mei", "display_name": "Mei", "role": "friend"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "对不起，是我答应了你又没做到。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我不是在生气，只是你答应的事又没做到，我有点累。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "boundary"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "Mei",
                        "speaker_id": "actor_mei",
                        "addressed_to": ["actor_lin"],
                        "text": "她只是最近太累了。",
                        "timestamp": "2025-01-04T09:00:00+08:00",
                        "labels": ["comfort"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    result = asyncio.run(
        module.preflight_scan(
            service_root=None,
            source_mode="chat_history",
            source_refs=[str(source_path)],
        )
    )

    assert result["status"] == "ok"
    assert result["resolved_anchor_person_id"] == "actor_lin"
    assert result["source_hash"].startswith("sha256:")
    ranked = result["counterpart_candidates"]
    assert ranked[0]["entity_id"] == "actor_current_user"
    assert ranked[0]["graph_neighbor_rank"] == 1
    assert ranked[0]["graph_distance"] == 1
    assert ranked[0]["ranking_source"] == "relationship_graph"
    counterparts = {item["entity_id"]: item for item in ranked}
    assert "actor_current_user" in counterparts
    assert counterparts["actor_current_user"]["direct_interaction_count"] >= 2
    assert counterparts["actor_current_user"]["is_current_user_binding_candidate"] is True
    assert counterparts["actor_current_user"]["graph_proximity_score"] > counterparts["actor_mei"]["graph_proximity_score"]
    assert counterparts["actor_current_user"]["direct_anchor_weight"] > counterparts["actor_mei"]["direct_anchor_weight"]


def test_preflight_scan_chatlab_json_normalizes_members_and_messages(workspace: Path):
    module = _load_module()
    source_path = workspace / "chatlab.json"
    source_path.write_text(
        json.dumps(
            {
                "chatlab": {"version": "1"},
                "meta": {"name": "Lin x User", "groupId": "chatlab_lin_user", "type": "private"},
                "members": [
                    {"platformId": "actor_lin", "accountName": "Lin"},
                    {"platformId": "actor_user", "accountName": "User"},
                ],
                "messages": [
                    {
                        "sender": "actor_user",
                        "accountName": "User",
                        "timestamp": 1735910400,
                        "type": 0,
                        "content": "对不起，是我答应了你又没做到。",
                    },
                    {
                        "sender": "actor_lin",
                        "accountName": "Lin",
                        "timestamp": 1735910414,
                        "type": 0,
                        "content": "我不是在生气，只是有点累。",
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    result = asyncio.run(
        module.preflight_scan(
            service_root=None,
            source_mode="chat_history",
            source_refs=[str(source_path)],
            anchor_person_id="actor_lin",
        )
    )

    assert result["status"] == "ok"
    assert result["resolved_anchor_person_id"] == "actor_lin"
    assert len(result["anchor_candidates"]) == 2
    ranked = result["counterpart_candidates"]
    assert ranked[0]["entity_id"] == "actor_user"
    assert ranked[0]["display_name"] == "User"
    assert ranked[0]["direct_interaction_count"] == 1
    assert "对不起，是我答应了你又没做到。" in ranked[0]["sample_supporting_lines"]
    assert "我不是在生气，只是有点累。" in ranked[0]["sample_supporting_lines"]





def test_preflight_scan_work_ranks_closer_anchor_neighbor_first(workspace: Path):
    module = _load_module()
    source_path = workspace / "work_rank.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "novel_rank",
                "title": "relationship rank sample",
                "work_id": "novel_rank",
                "characters": [
                    {"entity_id": "char_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "char_chen", "display_name": "Chen", "role": "lead"},
                    {"entity_id": "char_su", "display_name": "Su", "role": "support"},
                    {"entity_id": "char_master", "display_name": "Master", "role": "mentor"},
                ],
                "scenes": [
                    {
                        "scene_id": "scene_01",
                        "heading": "Bridge",
                        "perspective_character_id": "char_lin",
                        "text": "Lin looked at Chen and admitted she was only tired.",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "repair"],
                    },
                    {
                        "scene_id": "scene_02",
                        "heading": "Courtyard",
                        "perspective_character_id": "char_lin",
                        "text": "Lin trusted Chen enough to speak plainly before asking Su to wait outside.",
                        "timestamp": "2025-01-04T21:12:14+08:00",
                        "labels": ["comfort"],
                    },
                    {
                        "scene_id": "scene_03",
                        "heading": "Hall",
                        "perspective_character_id": "char_master",
                        "text": "Master warned Lin and Su to stay careful.",
                        "timestamp": "2025-01-05T21:12:14+08:00",
                        "labels": ["planning"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    result = asyncio.run(
        module.preflight_scan(
            service_root=None,
            source_mode="fiction_work",
            source_refs=[str(source_path)],
        )
    )

    assert result["status"] == "ok"
    ranked = result["counterpart_candidates"]
    assert ranked[0]["entity_id"] == "char_chen"
    by_id = {item["entity_id"]: item for item in ranked}
    assert by_id["char_chen"]["graph_neighbor_rank"] == 1
    assert by_id["char_chen"]["graph_distance"] == 1
    assert by_id["char_chen"]["graph_proximity_score"] > by_id["char_su"]["graph_proximity_score"]
    assert by_id["char_chen"]["direct_anchor_weight"] > by_id["char_su"]["direct_anchor_weight"]
    assert by_id["char_chen"]["ranking_source"] == "relationship_graph"
def test_selector_flow_persists_binding_session_and_projection_context(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_alpha",
                "title": "sample chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "对不起，是我答应了你又没做到。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我不是在生气，只是你答应的事又没做到，我有点累。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "boundary"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_lin_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )
    assert Path(created["relationship_graph_manifest_path"]).exists()
    assert created["import_session"]["artifact_refs"]["relationship_graph_manifest_path"] == created["relationship_graph_manifest_path"]
    candidates = asyncio.run(
        module.list_relationship_candidates(
            service_root=str(service_root),
            twin_id="twin_lin_01",
        )
    )
    chosen = next(item for item in candidates["candidates"] if item["entity_id"] == "actor_current_user")
    assert chosen["selection_eligibility"] == "publish_ready"

    selected = asyncio.run(
        module.select_relationship(
            service_root=str(service_root),
            twin_id="twin_lin_01",
            anchor_person_id="actor_lin",
            counterpart_entity_id="actor_current_user",
        )
    )

    binding_payload = json.loads(Path(selected["binding_path"]).read_text(encoding="utf-8"))
    session_payload = json.loads(Path(selected["session_path"]).read_text(encoding="utf-8"))
    assert binding_payload["import_session_id"] == created["import_session"]["session_id"]
    assert binding_payload["selected_relationship_binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert session_payload["state"] == "relationship_selected"

    binding = module._load_binding(service_root, "twin_lin_01")
    bundle = module._project_drafts(
        service_root=service_root,
        binding=binding,
        query="她和我现在是什么状态",
        hits=[{"fact": "Lin still values repair after conflict."}],
    )
    assert bundle["binding_context"]["selected_relationship_binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert bundle["relationship_projection_drafts"][0]["apply_scope"] == "selected_counterpart_default"





def test_list_relationship_candidates_uses_persisted_manifest_when_source_is_missing(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_manifest.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_manifest",
                "title": "manifest chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                    {"entity_id": "actor_mei", "display_name": "Mei", "role": "friend"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "我会补上。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我知道，但我现在有点累。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "boundary"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "Mei",
                        "speaker_id": "actor_mei",
                        "addressed_to": ["actor_lin"],
                        "text": "她确实最近太累了。",
                        "timestamp": "2025-01-04T09:00:00+08:00",
                        "labels": ["comfort"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_manifest_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )

    manifest_path = Path(created["relationship_graph_manifest_path"])
    assert manifest_path.exists()
    source_path.unlink()

    candidates = asyncio.run(
        module.list_relationship_candidates(
            service_root=str(service_root),
            twin_id="twin_manifest_01",
        )
    )

    assert candidates["relationship_graph_manifest_path"] == str(manifest_path)
    assert candidates["candidates"][0]["entity_id"] == "actor_current_user"
    assert candidates["candidates"][0]["ranking_source"] == "relationship_graph"
def test_rebind_updates_role_binding_before_switching_counterpart(workspace: Path):
    module = _load_module()
    source_path = workspace / "work.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "novel_alpha",
                "title": "sample fiction",
                "work_id": "novel_alpha",
                "characters": [
                    {"entity_id": "char_female_lead", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "char_second_male_lead", "display_name": "Chen", "role": "current_user_binding"},
                    {"entity_id": "char_childhood_friend", "display_name": "Su", "role": "support"},
                ],
                "scenes": [
                    {
                        "scene_id": "scene_01",
                        "heading": "Bridge",
                        "perspective_character_id": "char_female_lead",
                        "text": "Lin looked at Chen for a long time before saying she was only tired.",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "repair"],
                    },
                    {
                        "scene_id": "scene_02",
                        "heading": "Courtyard",
                        "perspective_character_id": "char_female_lead",
                        "text": "Lin trusted Su enough to speak plainly when she was exhausted.",
                        "timestamp": "2025-01-04T21:12:14+08:00",
                        "labels": ["comfort"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_novel_01",
            anchor_person_id="char_female_lead",
            interaction_mode="work_role",
            source_mode="fiction_work",
            binding_type="fictional_character",
            binding_entity_id="char_second_male_lead",
            source_refs=[str(source_path)],
        )
    )
    first_selection = asyncio.run(
        module.select_relationship(
            service_root=str(service_root),
            twin_id="twin_novel_01",
            anchor_person_id="char_female_lead",
            counterpart_entity_id="char_second_male_lead",
        )
    )
    rebound = asyncio.run(
        module.rebind_relationship(
            service_root=str(service_root),
            twin_id="twin_novel_01",
            anchor_person_id="char_female_lead",
            to_counterpart_entity_id="char_childhood_friend",
            binding_type="fictional_character",
            binding_entity_id="char_childhood_friend",
            from_binding_id=first_selection["selected_relationship_binding"]["binding_id"],
        )
    )

    binding_payload = json.loads(Path(rebound["binding_path"]).read_text(encoding="utf-8"))
    session_payload = json.loads(Path(rebound["session_path"]).read_text(encoding="utf-8"))
    assert binding_payload["current_user_role_binding"]["entity_id"] == "char_childhood_friend"
    assert binding_payload["selected_relationship_binding_id"] == rebound["selected_relationship_binding"]["binding_id"]
    assert rebound["superseded_binding_id"] == first_selection["selected_relationship_binding"]["binding_id"]
    assert session_payload["state"] == "rebound"


def test_list_relationship_candidates_refreshes_manifest_from_graphiti_neighborhood(
    workspace: Path,
    monkeypatch: pytest.MonkeyPatch,
):
    module = _load_module()
    source_path = workspace / "chat_graphiti_refresh.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_graphiti_refresh",
                "title": "graphiti manifest refresh chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                    {"entity_id": "actor_mei", "display_name": "Mei", "role": "friend"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "这次我会补上。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我知道，但我现在有点累。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["conflict", "boundary"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "Mei",
                        "speaker_id": "actor_mei",
                        "addressed_to": ["actor_lin"],
                        "text": "她确实最近太累了。",
                        "timestamp": "2025-01-04T09:00:00+08:00",
                        "labels": ["comfort"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)
    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_graphiti_refresh_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )
    source_path.unlink()

    anchor_uuid = "node_anchor_lin"
    fake_graphiti = _FakeGraphiti(
        search_nodes_by_query={
            "actor_lin": [_FakeGraphitiNode(uuid=anchor_uuid, entity_id="actor_lin", display_name="Lin")],
            "Lin": [_FakeGraphitiNode(uuid=anchor_uuid, entity_id="actor_lin", display_name="Lin")],
        },
        neighbors=[
            _FakeGraphitiEdge(
                source_node_uuid=anchor_uuid,
                target_node_uuid="node_user",
                source_entity_id="actor_lin",
                target_entity_id="actor_current_user",
                source_node_name="Lin",
                target_node_name="User",
                fact="Lin and User are still repairing after conflict.",
            ),
            _FakeGraphitiEdge(
                source_node_uuid=anchor_uuid,
                target_node_uuid="node_user",
                source_entity_id="actor_lin",
                target_entity_id="actor_current_user",
                source_node_name="Lin",
                target_node_name="User",
                fact="Lin keeps returning to the same promise-breaking issue with User.",
            ),
            _FakeGraphitiEdge(
                source_node_uuid=anchor_uuid,
                target_node_uuid="node_mei",
                source_entity_id="actor_lin",
                target_entity_id="actor_mei",
                source_node_name="Lin",
                target_node_name="Mei",
                fact="Mei comforts Lin from the side.",
            ),
        ],
    )

    async def _fake_open_graphiti(service_root_path: Path, twin_id: str):
        assert twin_id == "twin_graphiti_refresh_01"
        return fake_graphiti

    async def _fake_close_graphiti(graphiti):
        return None

    monkeypatch.setattr(module, "_open_graphiti", _fake_open_graphiti)
    monkeypatch.setattr(module, "_close_graphiti", _fake_close_graphiti)

    candidates = asyncio.run(
        module.list_relationship_candidates(
            service_root=str(service_root),
            twin_id="twin_graphiti_refresh_01",
        )
    )

    manifest_payload = json.loads(Path(created["relationship_graph_manifest_path"]).read_text(encoding="utf-8"))
    binding_payload = json.loads(Path(created["binding_path"]).read_text(encoding="utf-8"))
    assert manifest_payload["anchor_node_binding"]["focal_node_uuid"] == anchor_uuid
    assert manifest_payload["anchor_node_binding"]["binding_status"] == "graphiti_uuid_bound"
    assert binding_payload["focal_node_uuid"] == anchor_uuid
    assert fake_graphiti.neighbor_calls[0]["focal_node_uuid"] == anchor_uuid

    ranked = candidates["candidates"]
    assert ranked[0]["entity_id"] == "actor_current_user"
    assert ranked[0]["ranking_source"] == "graphiti_neighborhood"
    assert ranked[0]["graphiti_edge_count"] == 2
    assert ranked[0]["graphiti_neighbor_score"] == 1.0
    assert ranked[0]["graphiti_counterpart_node_uuid"] == "node_user"
    assert ranked[0]["anchor_node_uuid"] == anchor_uuid
    assert "graphiti_direct_neighbor" in ranked[0]["ranking_reasons"]
    assert ranked[1]["entity_id"] == "actor_mei"
    assert ranked[1]["graphiti_edge_count"] == 1
    assert ranked[1]["graphiti_neighbor_score"] == 0.5

    fake_graphiti.neighbors = [
        _FakeGraphitiEdge(
            source_node_uuid=anchor_uuid,
            target_node_uuid="node_user",
            source_entity_id="actor_lin",
            target_entity_id="actor_current_user",
            source_node_name="Lin",
            target_node_name="User",
            fact="Lin and User are still repairing after conflict.",
        )
    ]
    refreshed_again = asyncio.run(
        module.list_relationship_candidates(
            service_root=str(service_root),
            twin_id="twin_graphiti_refresh_01",
        )
    )
    refreshed_again_by_id = {item["entity_id"]: item for item in refreshed_again["candidates"]}
    assert refreshed_again_by_id["actor_mei"]["graphiti_edge_count"] == 0
    assert refreshed_again_by_id["actor_mei"]["graphiti_neighbor_score"] == 0.0
    assert "graphiti_counterpart_node_uuid" not in refreshed_again_by_id["actor_mei"]
    assert "anchor_node_uuid" not in refreshed_again_by_id["actor_mei"]
    assert refreshed_again_by_id["actor_mei"]["ranking_source"] == "relationship_graph"
    assert "graphiti_direct_neighbor" not in refreshed_again_by_id["actor_mei"]["ranking_reasons"]


def test_search_anchor_graph_resolves_anchor_uuid_before_graph_search(
    workspace: Path,
    monkeypatch: pytest.MonkeyPatch,
):
    module = _load_module()
    source_path = workspace / "chat_graphiti_search.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_graphiti_search",
                "title": "graphiti anchor search chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "我会改。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我需要看到你真的改。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["boundary"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)
    asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_graphiti_search_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )

    anchor_uuid = "node_anchor_lin"
    fake_graphiti = _FakeGraphiti(
        search_nodes_by_query={
            "actor_lin": [_FakeGraphitiNode(uuid=anchor_uuid, entity_id="actor_lin", display_name="Lin")],
            "Lin": [_FakeGraphitiNode(uuid=anchor_uuid, entity_id="actor_lin", display_name="Lin")],
        },
        search_results=[
            _FakeGraphitiEdge(
                source_node_uuid=anchor_uuid,
                target_node_uuid="node_user",
                source_entity_id="actor_lin",
                target_entity_id="actor_current_user",
                source_node_name="Lin",
                target_node_name="User",
                fact="Lin is still waiting for User to follow through.",
            )
        ],
    )

    async def _fake_open_graphiti(service_root_path: Path, twin_id: str):
        assert twin_id == "twin_graphiti_search_01"
        return fake_graphiti

    async def _fake_close_graphiti(graphiti):
        return None

    monkeypatch.setattr(module, "_open_graphiti", _fake_open_graphiti)
    monkeypatch.setattr(module, "_close_graphiti", _fake_close_graphiti)

    result = asyncio.run(
        module.search_anchor_graph(
            service_root=str(service_root),
            twin_id="twin_graphiti_search_01",
            query="她和我最近的僵点是什么",
            limit=5,
        )
    )

    binding_payload = json.loads((service_root / "bindings" / "twin_graphiti_search_01.binding.json").read_text(encoding="utf-8"))
    manifest_payload = json.loads((service_root / "graphs" / "twin_graphiti_search_01.relationship_graph_manifest.json").read_text(encoding="utf-8"))
    assert result["focal_node_uuid"] == anchor_uuid
    assert fake_graphiti.search_calls[0]["focal_node_uuid"] == anchor_uuid
    assert result["results"][0]["fact"] == "Lin is still waiting for User to follow through."
    assert binding_payload["focal_node_uuid"] == anchor_uuid
    assert manifest_payload["anchor_node_binding"]["focal_node_uuid"] == anchor_uuid


def test_get_import_session_returns_binding_session_and_selector_snapshot(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_get_session.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_get_session",
                "title": "get session chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                    {"entity_id": "actor_mei", "display_name": "Mei", "role": "friend"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "我知道，这次我会补上。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "我在意的不是道歉，是你能不能真的做到。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["boundary", "conflict"],
                    },
                    {
                        "turn_id": "turn_03",
                        "speaker": "Mei",
                        "speaker_id": "actor_mei",
                        "addressed_to": ["actor_lin"],
                        "text": "她最近确实很累。",
                        "timestamp": "2025-01-04T09:00:00+08:00",
                        "labels": ["comfort"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_get_session_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )
    selected = asyncio.run(
        module.select_relationship(
            service_root=str(service_root),
            twin_id="twin_get_session_01",
            anchor_person_id="actor_lin",
            counterpart_entity_id="actor_current_user",
        )
    )

    snapshot = asyncio.run(
        module.get_import_session(
            service_root=str(service_root),
            twin_id="twin_get_session_01",
        )
    )

    assert snapshot["status"] == "ok"
    assert snapshot["binding"]["twin_id"] == "twin_get_session_01"
    assert snapshot["import_session"]["session_id"] == created["import_session"]["session_id"]
    assert snapshot["import_session"]["selected_relationship_binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert snapshot["active_selected_relationship_binding"]["binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert snapshot["selected_candidate_card"]["entity_id"] == "actor_current_user"
    assert snapshot["selector_snapshot"]["selected_relationship_binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert snapshot["selector_snapshot"]["candidates"][0]["entity_id"] == "actor_current_user"
    assert snapshot["relationship_graph"]["manifest_path"] == created["relationship_graph_manifest_path"]
    assert snapshot["relationship_graph"]["candidate_count"] == len(snapshot["selector_snapshot"]["candidates"])


def test_run_request_envelope_supports_get_import_session(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_get_session_request.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_get_session_request",
                "title": "get session request chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "这次我会做到。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "那就先看行动。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["boundary"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_get_session_request_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )

    response = asyncio.run(
        module.run_request_envelope(
            request_payload={
                "operation": "get_import_session",
                "params": {
                    "service_root": str(service_root),
                    "twin_id": "twin_get_session_request_01",
                },
            }
        )
    )

    assert response["status"] == "ok"
    assert response["binding"]["twin_id"] == "twin_get_session_request_01"
    assert response["selector_snapshot"]["candidates"][0]["entity_id"] == "actor_current_user"

def test_withdraw_import_cleans_draft_artifacts_and_keeps_withdrawn_tombstone(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_withdraw.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_withdraw",
                "title": "withdraw chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "我会补上。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    },
                    {
                        "turn_id": "turn_02",
                        "speaker": "Lin",
                        "speaker_id": "actor_lin",
                        "addressed_to": ["actor_current_user"],
                        "text": "先看你能不能做到。",
                        "timestamp": "2025-01-03T21:12:14+08:00",
                        "labels": ["boundary"],
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)
    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_withdraw_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )
    selected = asyncio.run(
        module.select_relationship(
            service_root=str(service_root),
            twin_id="twin_withdraw_01",
            anchor_person_id="actor_lin",
            counterpart_entity_id="actor_current_user",
        )
    )

    graph_path = Path(created["graph_path"])
    graph_path.write_text("stub graph", encoding="utf-8")
    export_file = service_root / "exports" / "twin_withdraw_01" / "review.json"
    export_file.parent.mkdir(parents=True, exist_ok=True)
    export_file.write_text("{}", encoding="utf-8")
    soul_file = service_root / "soul" / "twin_withdraw_01" / "draft.json"
    soul_file.parent.mkdir(parents=True, exist_ok=True)
    soul_file.write_text("{}", encoding="utf-8")

    response = asyncio.run(
        module.run_request_envelope(
            request_payload={
                "operation": "withdraw_import",
                "params": {
                    "service_root": str(service_root),
                    "twin_id": "twin_withdraw_01",
                },
            }
        )
    )

    session_payload = json.loads(Path(response["session_path"]).read_text(encoding="utf-8"))
    assert response["status"] == "ok"
    assert session_payload["state"] == "withdrawn"
    assert session_payload["selected_relationship_binding_id"] is None
    assert session_payload["artifact_refs"] == {}
    assert not Path(created["binding_path"]).exists()
    assert not Path(created["relationship_graph_manifest_path"]).exists()
    assert not graph_path.exists()
    assert not export_file.parent.exists()
    assert not soul_file.parent.exists()
    assert response["withdrawn_selected_relationship_bindings"][0]["binding_id"] == selected["selected_relationship_binding"]["binding_id"]
    assert response["withdrawn_selected_relationship_bindings"][0]["status"] == "withdrawn"


def test_withdraw_import_rejects_published_session(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_withdraw_published.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_withdraw_published",
                "title": "withdraw published chat",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "这次我会做到。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                        "labels": ["repair"],
                    }
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)
    created = asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_withdraw_published_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
        )
    )

    session = module._load_import_session(service_root, created["import_session"]["session_id"])
    session.state = "published"
    module._save_import_session(service_root, session)

    with pytest.raises(module.ServiceError, match="published import session"):
        asyncio.run(
            module.withdraw_import(
                service_root=str(service_root),
                twin_id="twin_withdraw_published_01",
            )
        )


def test_resolve_graphiti_runtime_preferences_defaults_from_app_llm_config(workspace: Path):
    module = _load_module()

    resolved = module._resolve_graphiti_runtime_preferences(
        llm_config_payload={
            "protocol": "openai",
            "providerId": "openai",
            "providerName": "OpenAI",
            "baseUrl": "https://api.openai.com/v1",
            "apiKey": "test-key",
            "model": "gpt-4o-mini",
            "reasoningEffort": "high",
        },
        graphiti_config_payload=None,
    )

    assert resolved["ready"] is True
    assert resolved["summary"]["config_source"] == "app_llm_default"
    assert resolved["llm"]["model"] == "gpt-4o-mini"
    assert resolved["embedder"]["model"] == "text-embedding-3-small"
    assert resolved["cross_encoder"]["model"] == "gpt-4o-mini"
    assert resolved["summary"]["llm"]["api_key_configured"] is True
    assert "api_key" not in resolved["summary"]["llm"]



def test_resolve_graphiti_runtime_preferences_requires_graphiti_overrides_for_anthropic(workspace: Path):
    module = _load_module()

    resolved = module._resolve_graphiti_runtime_preferences(
        llm_config_payload={
            "protocol": "anthropic",
            "providerId": "custom",
            "providerName": "Anthropic",
            "baseUrl": "https://api.anthropic.com",
            "apiKey": "anthropic-secret",
            "model": "claude-sonnet-4-5",
        },
        graphiti_config_payload=None,
    )

    assert resolved["ready"] is False
    warnings = " ".join(resolved["warnings"])
    assert "explicit Graphiti embedder override" in warnings
    assert "explicit Graphiti cross-encoder override" in warnings



def test_create_twin_binding_persists_graphiti_runtime_preferences_without_secret_material(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_graphiti_prefs.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_graphiti_prefs",
                "title": "graphiti prefs sample",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "我晚点再跟你说。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                    }
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_graphiti_prefs_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
            llm_config={
                "protocol": "openai",
                "providerId": "openai",
                "providerName": "OpenAI",
                "baseUrl": "https://api.openai.com/v1",
                "apiKey": "test-key",
                "model": "gpt-4o-mini",
            },
        )
    )

    binding_payload = json.loads((service_root / "bindings" / "twin_graphiti_prefs_01.binding.json").read_text(encoding="utf-8"))
    session_payload = json.loads(next((service_root / "bindings" / "import_sessions").glob("import_twin_graphiti_prefs_01_*.json")).read_text(encoding="utf-8"))

    assert binding_payload["graphiti_runtime_preferences"]["config_source"] == "app_llm_default"
    assert binding_payload["graphiti_runtime_preferences"]["llm"]["api_key_configured"] is True
    assert "api_key" not in binding_payload["graphiti_runtime_preferences"]["llm"]
    assert "test-key" not in json.dumps(binding_payload["graphiti_runtime_preferences"], ensure_ascii=False)
    assert session_payload["graphiti_runtime_preferences"]["llm"]["model"] == "gpt-4o-mini"



def test_list_relationship_candidates_keeps_persisted_graphiti_preferences_when_request_omits_runtime_config(workspace: Path):
    module = _load_module()
    source_path = workspace / "chat_graphiti_keep_prefs.json"
    source_path.write_text(
        json.dumps(
            {
                "source_id": "chat_graphiti_keep_prefs",
                "title": "graphiti prefs retention sample",
                "participants": [
                    {"entity_id": "actor_lin", "display_name": "Lin", "role": "anchor"},
                    {"entity_id": "actor_current_user", "display_name": "User", "role": "current_user"},
                ],
                "turns": [
                    {
                        "turn_id": "turn_01",
                        "speaker": "User",
                        "speaker_id": "actor_current_user",
                        "addressed_to": ["actor_lin"],
                        "text": "别担心，我记得。",
                        "timestamp": "2025-01-03T21:12:00+08:00",
                    }
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    service_root = _service_root(workspace)

    asyncio.run(
        module.create_twin_binding(
            service_root=str(service_root),
            twin_id="twin_graphiti_keep_prefs_01",
            anchor_person_id="actor_lin",
            interaction_mode="chat_twin",
            source_mode="chat_history",
            binding_type="real_user",
            binding_entity_id="actor_current_user",
            source_refs=[str(source_path)],
            llm_config={
                "protocol": "openai",
                "providerId": "openai",
                "providerName": "OpenAI",
                "baseUrl": "https://api.openai.com/v1",
                "apiKey": "test-key",
                "model": "gpt-4o-mini",
            },
        )
    )

    result = asyncio.run(
        module.list_relationship_candidates(
            service_root=str(service_root),
            twin_id="twin_graphiti_keep_prefs_01",
        )
    )
    binding = module._load_binding(service_root, "twin_graphiti_keep_prefs_01")

    assert result["graphiti_runtime_preferences"]["config_source"] == "app_llm_default"
    assert binding.graphiti_runtime_preferences["config_source"] == "app_llm_default"
