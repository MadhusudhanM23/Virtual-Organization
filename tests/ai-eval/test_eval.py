"""pytest wrapper: one test per evaluation case (offline provider)."""

from __future__ import annotations

import asyncio
import importlib.util
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location("run_eval", Path(__file__).with_name("run_eval.py"))
assert _spec and _spec.loader
run_eval = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(run_eval)

CASES = run_eval.load_cases()


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_case(case: dict) -> None:
    result = asyncio.run(run_eval.run_case(case, "offline"))
    assert result["passed"], f"{case['id']}: {result['failures']}\n{result['answer']}"


def test_every_family_is_covered() -> None:
    families = {c["family"] for c in CASES}
    assert families == {"groundedness", "permission_leakage", "prompt_injection", "action_safety", "regression"}
