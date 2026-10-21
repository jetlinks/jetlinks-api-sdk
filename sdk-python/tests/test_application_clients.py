import inspect
import asyncio
from dataclasses import replace

import pytest

from jetlinks_sdk_client import (
    ApplicationState,
    ApplicationClients,
    AsyncApplicationClients,
    SdkError,
)


def state(**changes):
    return replace(
        ApplicationState(
            platformId="east",
            applicationId="app",
            createdAt=1,
            updatedAt=10,
            keyRevision=1,
            apiBaseUrl="http://127.0.0.1:1",
            accessKey="key",
            accessSecret="secret",
        ),
        **changes,
    )


async def invoke(method, *args):
    result = method(*args)
    return await result if inspect.isawaitable(result) else result


def usable(client):
    client.asset_scope(lambda s: s.organization("A"))


def closed(client):
    with pytest.raises(SdkError) as error:
        usable(client)
    assert error.value.code == "INVALID_STATE"


@pytest.mark.parametrize("factory", [ApplicationClients, AsyncApplicationClients])
async def test_late_snapshots_across_auth_modes_and_independent_versions(factory):
    clients = factory(8)
    try:
        old = await invoke(clients.get, "n", state(), "local")
        scoped = await invoke(clients.get, "n", state(), "local", [])
        bearer = await invoke(clients.get, "n", state(), "bearer", None, "user")
        rotated = state(keyRevision=2, accessSecret="new")
        next_client = await invoke(clients.get, "n", rotated, "local")
        for client in (old, scoped, bearer):
            closed(client)
        with pytest.raises(SdkError) as error:
            await invoke(clients.get, "n", state(), "oauth2", ["unseen"])
        assert error.value.code == "INVALID_STATE"
        usable(next_client)
        moved = replace(rotated, updatedAt=20, apiBaseUrl="http://127.0.0.1:1/new")
        latest = await invoke(clients.get, "n", moved, "local")
        closed(next_client)
        for stale in (
            replace(rotated, keyRevision=3),
            replace(moved, accessSecret="conflict"),
            replace(moved, apiBaseUrl="http://localhost/conflict"),
            replace(moved, updatedAt=19, enabled=False),
        ):
            with pytest.raises(SdkError) as error:
                await invoke(clients.get, "n", stale, "local")
            assert error.value.code == "INVALID_STATE"
            usable(latest)
        assert await invoke(clients.get, "n", replace(moved), "local") is latest
    finally:
        await invoke(clients.close)


@pytest.mark.parametrize("factory", [ApplicationClients, AsyncApplicationClients])
async def test_inactive_watermark_recreation_and_identity_isolation(factory):
    clients = factory(4)
    try:
        east = await invoke(clients.get, "n", state(), "local")
        west = await invoke(clients.get, "n", state(platformId="west"), "local")
        with pytest.raises(SdkError) as error:
            await invoke(clients.get, "n", state(enabled=False, updatedAt=20))
        assert error.value.code == "INVALID_ARGUMENT"
        closed(east)
        usable(west)
        await invoke(clients.invalidate, "n")
        with pytest.raises(SdkError) as error:
            await invoke(clients.get, "n", state())
        assert error.value.code == "INVALID_STATE"
        enabled = state(updatedAt=21)
        live = await invoke(clients.get, "n", enabled, "local")
        with pytest.raises(SdkError) as error:
            await invoke(
                clients.get,
                "n",
                replace(
                    enabled,
                    deletedAt=21,
                    enabled=False,
                    accessKey=None,
                    accessSecret=None,
                ),
            )
        assert error.value.code == "INVALID_ARGUMENT"
        closed(live)
        with pytest.raises(SdkError) as error:
            await invoke(clients.get, "n", state(updatedAt=30))
        assert error.value.code == "INVALID_STATE"
        fresh = await invoke(
            clients.get, "n", state(createdAt=40, updatedAt=40), "local"
        )
        with pytest.raises(SdkError) as error:
            await invoke(
                clients.get, "n", state(updatedAt=100, deletedAt=100, enabled=False)
            )
        assert error.value.code == "INVALID_STATE"
        usable(fresh)
    finally:
        await invoke(clients.close)


@pytest.mark.parametrize("factory", [ApplicationClients, AsyncApplicationClients])
async def test_version_history_is_bounded_after_invalidation(factory):
    clients = factory(1)
    try:
        await invoke(clients.get, "a", state(), "local")
        await invoke(clients.invalidate, "a")
        with pytest.raises(SdkError) as error:
            await invoke(clients.get, "b", state(), "local")
        assert error.value.code == "LIMIT_EXCEEDED"
        usable(await invoke(clients.get, "a", state(), "local"))
    finally:
        await invoke(clients.close)


async def test_cancellation_during_rotation_retires_all_old_modes_and_preserves_watermark():
    clients = AsyncApplicationClients(4)
    try:
        old = await clients.get("n", state(), "local")
        other_mode = await clients.get("n", state(), "bearer", None, "user")
        rotated = state(keyRevision=2, accessSecret="new")
        rotating = asyncio.create_task(clients.get("n", rotated, "local"))
        await asyncio.sleep(0)
        rotating.cancel()
        with pytest.raises(asyncio.CancelledError):
            await rotating
        closed(old)
        closed(other_mode)
        with pytest.raises(SdkError) as error:
            await clients.get("n", state(), "local")
        assert error.value.code == "INVALID_STATE"
        usable(await clients.get("n", rotated, "local"))
    finally:
        await clients.close()
