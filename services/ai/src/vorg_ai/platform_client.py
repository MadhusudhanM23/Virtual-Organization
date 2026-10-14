"""HTTP client for the platform API with identity passthrough (REQ-102, BLUEPRINT §13, ADR-12).

The only credential this service ever uses is the **caller's own bearer token**, forwarded unchanged. There is no
service account, no token exchange and no retry with other rights: a 401/403/404 is reported to the caller as it is.
The tenant comes from the token (the API never accepts ``tenant_id`` from a request), so a tool can never reach
another tenant's data.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import httpx


class PlatformError(Exception):
    """A non-2xx answer of the platform API (problem+json when available)."""

    def __init__(
        self,
        status: int,
        title: str,
        detail: str | None = None,
        code: str | None = None,
        problem: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(f"{status} {title}: {detail or ''}".strip())
        self.status = status
        self.title = title
        self.detail = detail
        self.code = code
        self.problem = problem or {}


@dataclass
class CallLog:
    method: str
    path: str
    status: int


@dataclass
class PlatformClient:
    http: httpx.AsyncClient
    token: str = field(repr=False)
    correlation_id: str = ""
    calls: list[CallLog] = field(default_factory=list)

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json, application/problem+json",
        }
        if self.correlation_id:
            headers["X-Correlation-Id"] = self.correlation_id
        if extra:
            headers.update(extra)
        return headers

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        clean = {k: v for k, v in (params or {}).items() if v is not None and v != ""}
        res = await self.http.get(path, params=clean, headers=self._headers())
        return self._handle("GET", path, res)

    async def post_command(self, path: str, body: dict[str, Any] | None, idempotency_key: str) -> Any:
        res = await self.http.post(path, json=body or {}, headers=self._headers({"Idempotency-Key": idempotency_key}))
        return self._handle("POST", path, res)

    def _handle(self, method: str, path: str, res: httpx.Response) -> Any:
        self.calls.append(CallLog(method, path, res.status_code))
        if res.is_success:
            return res.json() if res.content else None
        problem: dict[str, Any] = {}
        try:
            body = res.json()
            if isinstance(body, dict):
                problem = body
        except ValueError:
            pass
        raise PlatformError(
            res.status_code,
            str(problem.get("title") or f"HTTP {res.status_code}"),
            problem.get("detail"),
            problem.get("code"),
            problem,
        )
