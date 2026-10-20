"""示例单进程 SQLite 持久层。事务串行执行，网络异步端点通过 to_thread 调用。"""

import asyncio
import base64
import pathlib
import sqlite3
import threading
import time
import uuid
from decimal import Decimal, localcontext

from jetlinks_sdk_client import (
    ApplicationState,
    SdkError,
    apply_lifecycle,
    decode,
    encode,
)
from jetlinks_sdk_client.models import model_from_dict
from jetlinks_sdk_client.protocol import lp, sha256


def identity(platform, app):
    return (
        base64.urlsafe_b64encode(encode([platform, app]).encode()).rstrip(b"=").decode()
    )


def context_id(state):
    return "business-" + identity(state.platformId, state.applicationId)


class ExampleStore:
    def __init__(self, file, private_key):
        pathlib.Path(file).resolve().parent.mkdir(parents=True, exist_ok=True)
        self.private_key, self.lock = private_key, threading.RLock()
        self.db = sqlite3.connect(
            file + ".sqlite", check_same_thread=False, isolation_level=None
        )
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS apps(id TEXT PRIMARY KEY, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, received INTEGER NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS quotas(id TEXT PRIMARY KEY, body TEXT NOT NULL);
        """)

    def transaction(self, action):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                value = action()
                self.db.execute("COMMIT")
                return value
            except Exception:
                self.db.execute("ROLLBACK")
                raise

    def find(self, platform, app):
        with self.lock:
            row = self.db.execute(
                "SELECT body FROM apps WHERE id=?", (identity(platform, app),)
            ).fetchone()
            return model_from_dict(ApplicationState, decode(row[0])) if row else None

    def applications(self):
        with self.lock:
            return [
                model_from_dict(ApplicationState, decode(row[0]))
                for row in self.db.execute("SELECT body FROM apps ORDER BY id")
            ]

    async def accept(self, notice):
        return await asyncio.to_thread(self._accept, notice)

    def _accept(self, notice):
        def persist():
            key = sha256(
                lp(
                    [
                        notice.key.channelId,
                        notice.platformId,
                        notice.applicationId,
                        notice.eventId,
                    ]
                )
            )
            duplicate = bool(
                self.db.execute("SELECT 1 FROM inbox WHERE id=?", (key,)).fetchone()
            )
            if not duplicate:
                if notice.key.type == "business":
                    state = apply_lifecycle(
                        self.find(notice.platformId, notice.applicationId),
                        notice,
                        self.private_key,
                    )
                    self.db.execute(
                        "INSERT OR REPLACE INTO apps VALUES(?,?)",
                        (
                            identity(notice.platformId, notice.applicationId),
                            encode(state),
                        ),
                    )
                self.db.execute("INSERT INTO inbox VALUES(?)", (key,))
            self.receipt(
                {
                    "channelId": notice.key.channelId,
                    "platformId": notice.platformId,
                    "applicationId": notice.applicationId,
                    "eventId": notice.eventId,
                    "type": notice.event.type,
                    "verified": True,
                    "accepted": True,
                    "duplicate": duplicate,
                    "rawBody": notice.rawBody.decode(),
                    "rawBodySha256": sha256(notice.rawBody),
                    "event": notice.event,
                    "signatureHeaders": notice.signatureHeaders,
                }
            )
            return not duplicate

        return self.transaction(persist)

    def receipt(self, value):
        with self.lock:
            value = {
                **value,
                "recordId": str(uuid.uuid4()),
                "receivedAt": int(time.time() * 1000),
            }
            self.db.execute(
                "INSERT INTO receipts VALUES(?,?,?)",
                (value["recordId"], value["receivedAt"], encode(value)),
            )

    def receipts(self, filters):
        with self.lock:
            rows = [
                decode(row[0])
                for row in self.db.execute(
                    "SELECT body FROM receipts ORDER BY received DESC,rowid DESC"
                )
            ]

        def matches(row):
            for key, value in filters.items():
                if not value or key == "pageIndex":
                    continue
                if key == "status":
                    if not (
                        row.get("duplicate")
                        if value == "duplicate"
                        else row.get("accepted") and not row.get("duplicate")
                        if value == "accepted"
                        else not row.get("accepted")
                    ):
                        return False
                elif row.get(key) != value:
                    return False
            return True

        values = [row for row in rows if matches(row)]
        page = max(0, int(filters.get("pageIndex", 0)))
        return {"total": len(values), "data": values[page * 50 : (page + 1) * 50]}

    def detail(self, identity):
        with self.lock:
            row = self.db.execute(
                "SELECT body FROM receipts WHERE id=?", (identity,)
            ).fetchone()
            return decode(row[0]) if row else None

    def quota(self, state):
        key = (
            identity(state.platformId, state.applicationId) + ":" + str(state.createdAt)
        )
        row = self.db.execute("SELECT body FROM quotas WHERE id=?", (key,)).fetchone()
        return key, decode(row[0]) if row else {
            "revision": -1,
            "limits": [],
            "capabilities": [],
            "usage": Decimal(0),
            "windowStart": -1,
        }

    def save_limits(self, state, parameters):
        if (
            not isinstance(parameters.get("revision"), int)
            or parameters["revision"] < 0
            or not isinstance(parameters.get("limits"), list)
            or not isinstance(parameters.get("capabilities"), list)
        ):
            raise SdkError("INVALID_ARGUMENT", "Complete quota snapshot required")
        limits, capabilities = parameters["limits"], parameters["capabilities"]
        if (
            len(limits) > 1
            or any(
                v.get("metricId") != "example:requests"
                or v.get("limit") is None
                or not Decimal(v["limit"]).is_finite()
                or v.get("windowDurationMillis", 0) <= 0
                for v in limits
            )
            or len(capabilities) > 1
            or any(
                v.get("resource") != "example"
                or not isinstance(v.get("capabilities"), list)
                or any(c != "use" for c in v["capabilities"])
                for v in capabilities
            )
        ):
            raise SdkError("INVALID_ARGUMENT", "Invalid example quota")

        def persist():
            key, value = self.quota(state)
            if parameters["revision"] > value["revision"]:
                value.update(
                    revision=parameters["revision"],
                    limits=limits,
                    capabilities=capabilities,
                )
                self.db.execute(
                    "INSERT OR REPLACE INTO quotas VALUES(?,?)", (key, encode(value))
                )
            return {"revision": value["revision"]}

        return self.transaction(persist)

    def snapshot(self, value):
        now, duration = (
            int(time.time() * 1000),
            value["limits"][0]["windowDurationMillis"],
        )
        start = now // duration * duration
        return {
            "metricId": "example:requests",
            "usage": Decimal(value["usage"])
            if value["windowStart"] == start
            else Decimal(0),
            "timestamp": now,
            "windowDurationMillis": duration,
            "nextResetTime": start + duration,
        }

    def usage(self, state, parameters):
        with self.lock:
            _, value = self.quota(state)
            return (
                []
                if not value["limits"]
                or parameters.get("metricIds")
                and "example:requests" not in parameters["metricIds"]
                else [self.snapshot(value)]
            )

    def use(self, state, parameters):
        amount = Decimal(parameters.get("amount", 0))
        if not amount.is_finite() or amount <= 0:
            raise SdkError("INVALID_ARGUMENT", "Positive amount required")

        def persist():
            key, value = self.quota(state)
            if not value["limits"] or not any(
                v["resource"] == "example" and "use" in v["capabilities"]
                for v in value["capabilities"]
            ):
                raise SdkError("INVALID_STATE", "Resource not granted")
            snapshot = self.snapshot(value)
            with localcontext() as ctx:
                ctx.prec = 100
                usage = snapshot["usage"] + amount
            limit = Decimal(value["limits"][0]["limit"])
            if limit >= 0 and usage > limit:
                raise SdkError("LIMIT_EXCEEDED", "Quota exceeded")
            value.update(
                usage=usage,
                windowStart=snapshot["nextResetTime"]
                - snapshot["windowDurationMillis"],
            )
            self.db.execute(
                "INSERT OR REPLACE INTO quotas VALUES(?,?)", (key, encode(value))
            )
            return {**snapshot, "usage": usage}

        return self.transaction(persist)

    def health(self):
        with self.lock:
            self.db.execute("SELECT 1").fetchone()
            return {"status": "UP"}

    def close(self):
        self.db.close()


def redact(value):
    import re

    value = decode(encode(value))

    def clean(v):
        if isinstance(v, list):
            return [clean(x) for x in v]
        if not isinstance(v, dict):
            return v
        result = {}
        for key, data in v.items():
            if re.fullmatch(
                r"access_?secret|notificationSecret|client_?secret|secret|access_?token|refresh_?token|id_?token|token|authorization|cookie|set-cookie|encryptedAccessSecret|access_?key|x-signature",
                key,
                re.I,
            ):
                result[key] = "[redacted]"
            elif key == "rawBody":
                try:
                    result[key] = encode(clean(decode(data)))
                except SdkError:
                    result[key] = "[unparseable]"
            else:
                result[key] = clean(data)
        return result

    return clean(value)
