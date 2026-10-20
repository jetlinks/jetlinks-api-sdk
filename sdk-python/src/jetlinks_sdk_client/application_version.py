"""管理器的有界版本准入元数据；不保存凭据明文或第二份业务状态。"""

import hashlib
from dataclasses import dataclass

from .protocol import SdkError, encode


@dataclass(frozen=True)
class ApplicationVersion:
    created_at: int
    updated_at: int
    key_revision: int
    deleted: bool
    status: str
    credentials: str
    connection: str

    @classmethod
    def of(cls, state):
        for value in (
            state.createdAt,
            state.updatedAt,
            state.keyRevision,
            state.deletedAt,
        ):
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise SdkError("INVALID_ARGUMENT", "Invalid application version")

        def digest(value):
            return hashlib.sha256(encode(value).encode("utf-8")).hexdigest()

        credentials = digest([state.accessKey, state.accessSecret])
        return cls(
            state.createdAt,
            state.updatedAt,
            state.keyRevision,
            bool(state.deletedAt),
            digest([state.enabled, state.deletedAt, state.apiBaseUrl]),
            credentials,
            digest([state.createdAt, state.apiBaseUrl, credentials, state.keyRevision]),
        )

    def check(self, previous):
        # 状态和凭据可由不同事件推进；其中任一倒退都须重新读取当前持久化快照。
        if previous is None or self.created_at > previous.created_at:
            return
        if (
            self.created_at < previous.created_at
            or self.updated_at < previous.updated_at
            or self.key_revision < previous.key_revision
            or (previous.deleted and not self.deleted)
            or (
                self.updated_at == previous.updated_at
                and self.status != previous.status
                and not (self.deleted and not previous.deleted)
            )
            or (
                self.key_revision == previous.key_revision
                and not self.deleted
                and self.credentials != previous.credentials
            )
        ):
            raise SdkError("INVALID_STATE", "Stale or conflicting application snapshot")
