import base64
import hmac
import importlib.util
import pathlib
import struct
import time

import pytest

from jetlinks_sdk_client import (
    NotificationReceiver,
    NotificationVerifier,
    TrustedKey,
    encode,
    sha256,
)

spec = importlib.util.spec_from_file_location(
    "example_store", pathlib.Path(__file__).resolve().parents[1] / "examples/store.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


async def test_failed_inbox_rolls_back_binding_and_restart_deduplicates(tmp_path):
    file = str(tmp_path / "database")
    store = module.ExampleStore(file, lambda _: "")
    event = {
        "id": "event",
        "type": "application.updated",
        "occurredAt": int(time.time() * 1000),
        "data": {
            "application": {
                "id": "app",
                "templateId": "template",
                "createTime": 1,
                "state": "enabled",
                "apiBaseUrl": "http://localhost/gateway",
            }
        },
    }
    raw, stamp = encode(event).encode(), str(int(time.time() * 1000))
    values = ["1", "app", "east", "key", stamp, "event", sha256(raw)]
    lp = b"".join(struct.pack(">I", len(v.encode())) + v.encode() for v in values)
    signature = (
        base64.urlsafe_b64encode(hmac.digest(b"secret", lp, "sha256"))
        .rstrip(b"=")
        .decode()
    )
    headers = {
        "X-Signature-Version": "1",
        "X-Platform-Id": "east",
        "X-Application-Id": "app",
        "X-Key-Id": "key",
        "X-Timestamp": stamp,
        "X-Event-Id": "event",
        "X-Signature": signature,
    }
    verifier = NotificationVerifier(
        lambda *_: TrustedKey(
            "business", "secret", platformId="east", templateId="template"
        )
    )
    try:
        store.db.execute(
            "CREATE TRIGGER fail_inbox BEFORE INSERT ON inbox BEGIN SELECT RAISE(FAIL,'unavailable'); END"
        )
        with pytest.raises(Exception, match="unavailable"):
            await NotificationReceiver(verifier, store).receive(headers, raw)
        assert store.find("east", "app") is None
        assert store.receipts({})["total"] == 0
        store.db.execute("DROP TRIGGER fail_inbox")
        assert await NotificationReceiver(verifier, store).receive(headers, raw)
        store.close()
        store = module.ExampleStore(file, lambda _: "")
        assert not await NotificationReceiver(verifier, store).receive(headers, raw)
        assert store.find("east", "app").applicationId == "app"
    finally:
        store.close()
