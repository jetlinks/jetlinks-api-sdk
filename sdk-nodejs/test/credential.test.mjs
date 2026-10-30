import assert from "node:assert/strict";
import test from "node:test";
import crypto from "node:crypto";
import http from "node:http";
import {
  BusinessApplicationCredentials,
  JetLinksClient,
  lp,
} from "../dist/index.js";

test("RSA bootstrap signs the bound identity and decrypts the matching response without Bearer", async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const pem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  let mismatch = false;
  let redirect = false;
  let calls = 0;
  const server = http.createServer(async (req, res) => {
    calls++;
    assert.equal(
      req.url,
      "/gateway/business-application-template/template/app/credential/_encrypted",
    );
    assert.equal(req.method, "POST");
    assert.equal(req.headers.authorization, undefined);
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    assert.equal(body.platformId, "");
    assert.equal(Buffer.from(body.nonce, "base64url").length, 16);
    assert.ok(Math.abs(body.timestamp - Date.now()) < 60_000);
    assert.equal(
      crypto.verify(
        "sha256",
        lp([
          "business-application-credential/request",
          "POST",
          "1",
          "template",
          "app",
          "template",
          "credential.read",
          body.nonce,
          String(body.timestamp),
        ]),
        {
          key: publicKey,
          padding: crypto.constants.RSA_PKCS1_PSS_PADDING,
          saltLength: 32,
        },
        Buffer.from(body.sign, "base64url"),
      ),
      true,
    );
    if (redirect) {
      res.writeHead(302, { location: "/elsewhere" });
      res.end();
      return;
    }
    const encrypted = crypto
      .publicEncrypt(
        { key: publicKey, oaepHash: "sha256" },
        Buffer.from("application-secret"),
      )
      .toString("base64");
    res.setHeader("Content-Type", "application/json");
    res.end(
      JSON.stringify({
        status: 200,
        result: {
          version: "1",
          platformId: "template",
          templateId: "template",
          applicationId: "app",
          usage: "credential.read",
          nonce: mismatch ? "other" : body.nonce,
          keyId: "rsa-1",
          keyRevision: 2,
          accessKey: "access-key",
          encryptedAccessSecret: encrypted,
        },
      }),
    );
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}/gateway`;
  const reader = new BusinessApplicationCredentials(base, "rsa-1", pem);
  try {
    const value = await reader.read("template", "app");
    assert.equal(value.accessKey, "access-key");
    assert.equal(value.accessSecret, "application-secret");
    assert.equal(value.keyRevision, 2);
    const local = JetLinksClient.local(
      base,
      value.accessKey,
      value.accessSecret,
    );
    assert.ok((await local.tokenProvider.token()).startsWith("at_"));
    local.close();
    mismatch = true;
    await assert.rejects(reader.read("template", "app"), {
      code: "AUTHENTICATION",
    });
    redirect = true;
    await assert.rejects(reader.read("template", "app"));
    assert.equal(calls, 3);
  } finally {
    reader.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
