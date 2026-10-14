"""HTTP client for the vision ingestion endpoints of services/api (docs/api/vision.md).

Authentication is the vision service credential (ADR-64): ``Authorization: Service <client_id>:<secret>``. The secret
is read from an environment variable by the CLI and never logged. Each batch is a command with a deterministic
``Idempotency-Key`` (camera, session, batch number), so a retry after a timeout can never double-ingest.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status
        self.body = body


@dataclass
class VisionApi:
    base_url: str
    client_id: str
    secret: str
    timeout_s: float = 15.0
    retries: int = 3

    def __post_init__(self) -> None:
        if not self.base_url.startswith(("http://", "https://")):
            raise ValueError("the API URL must be http:// or https://")

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Service {self.client_id}:{self.secret}", "Accept": "application/json"}

    def _request(self, method: str, path: str, body: dict | None = None, extra: dict[str, str] | None = None) -> dict:
        url = self.base_url.rstrip("/") + path
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = self._headers()
        if body is not None:
            headers["Content-Type"] = "application/json"
        if extra:
            headers.update(extra)
        attempt = 0
        while True:
            attempt += 1
            req = urllib.request.Request(url, data=data, method=method, headers=headers)  # noqa: S310 - http(s) only
            try:
                with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:  # noqa: S310 - http(s) only
                    return json.loads(resp.read().decode("utf-8") or "{}")
            except urllib.error.HTTPError as ex:
                text = ex.read().decode("utf-8", "replace")
                if ex.code >= 500 and attempt <= self.retries:
                    time.sleep(0.5 * attempt)
                    continue
                raise ApiError(ex.code, text) from None
            except urllib.error.URLError:
                if attempt <= self.retries:
                    time.sleep(0.5 * attempt)
                    continue
                raise

    def camera(self, camera_id: str) -> dict:
        return self._request("GET", f"/api/vision/cameras/{camera_id}")

    def post_batch(self, camera_id: str, idempotency_key: str, batch: dict) -> dict:
        return self._request(
            "POST", f"/api/vision/cameras/{camera_id}/observations", batch, {"Idempotency-Key": idempotency_key}
        )
