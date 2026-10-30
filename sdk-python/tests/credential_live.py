"""平台联调入口，stdin 为合成配置，不输出密钥。"""

import asyncio
import json
import sys
from jetlinks_sdk_client import (
    BusinessApplicationCredentials,
    AsyncBusinessApplicationCredentials,
    JetLinksClient,
)

config = json.load(sys.stdin)


def check(value):
    assert value.access_key == config["accessKey"]
    assert value.access_secret == config["accessSecret"]
    assert str(value.key_revision) == config["keyRevision"]


with BusinessApplicationCredentials(
    config["base"], config["keyId"], config["privateKey"]
) as reader:
    value = reader.read(
        config["templateId"], config["applicationId"], config["platformId"]
    )
    check(value)
    with JetLinksClient.local(
        config["base"], value.access_key, value.access_secret
    ) as client:
        assert client.request("/credential-test/verify")["authenticated"]


async def call_async():
    async with AsyncBusinessApplicationCredentials(
        config["base"], config["keyId"], config["privateKey"]
    ) as reader:
        check(
            await reader.read(
                config["templateId"], config["applicationId"], config["platformId"]
            )
        )


asyncio.run(call_async())
print("python sync/async credential integration passed")
