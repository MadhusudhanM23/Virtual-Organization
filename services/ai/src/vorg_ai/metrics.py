"""Prometheus text-format metrics of the assistant gateway (Plan 11.1, no extra dependency).

Exposes ``vo_ai_request_duration_seconds`` (histogram of ``POST /api/assistant/messages`` wall time, labelled by
outcome) and ``vo_ai_requests_total``. Counters are per process and reset on restart, as Prometheus expects.
"""

from __future__ import annotations

import threading
from collections import defaultdict

BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)


class Metrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts: dict[str, list[int]] = defaultdict(lambda: [0] * (len(BUCKETS) + 1))
        self._sums: dict[str, float] = defaultdict(float)

    def observe(self, outcome: str, seconds: float) -> None:
        with self._lock:
            counts = self._counts[outcome]
            for i, bound in enumerate(BUCKETS):
                if seconds <= bound:
                    counts[i] += 1
            counts[len(BUCKETS)] += 1
            self._sums[outcome] += seconds

    def render(self) -> str:
        lines = [
            "# HELP vo_ai_request_duration_seconds Assistant message handling time",
            "# TYPE vo_ai_request_duration_seconds histogram",
        ]
        with self._lock:
            for outcome, counts in sorted(self._counts.items()):
                for i, bound in enumerate(BUCKETS):
                    lines.append(f'vo_ai_request_duration_seconds_bucket{{outcome="{outcome}",le="{bound}"}} {counts[i]}')
                lines.append(f'vo_ai_request_duration_seconds_bucket{{outcome="{outcome}",le="+Inf"}} {counts[-1]}')
                lines.append(f'vo_ai_request_duration_seconds_sum{{outcome="{outcome}"}} {self._sums[outcome]}')
                lines.append(f'vo_ai_request_duration_seconds_count{{outcome="{outcome}"}} {counts[-1]}')
            lines.append("# HELP vo_ai_requests_total Assistant messages handled")
            lines.append("# TYPE vo_ai_requests_total counter")
            for outcome, counts in sorted(self._counts.items()):
                lines.append(f'vo_ai_requests_total{{outcome="{outcome}"}} {counts[-1]}')
        return "\n".join(lines) + "\n"
