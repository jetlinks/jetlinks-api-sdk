import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import { gzipSync, deflateSync, brotliCompressSync } from "node:zlib";
import {
  JetLinksClient,
  AssetScope,
  CachedTokenProvider,
} from "../dist/index.js";

async function fixture(run) {
  const seen = [];
  const server = http.createServer(async (req, res) => {
    const scopes = [];
    for (let i = 0; i < req.rawHeaders.length; i += 2)
      if (req.rawHeaders[i].toLowerCase() === "x-asset-scope")
        scopes.push(req.rawHeaders[i + 1]);
    seen.push({ path: req.url, scopes, auth: req.headers.authorization });
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const query = JSON.parse(Buffer.concat(chunks).toString() || "{}");
    let result = scopes;
    if (req.url === "/pages" || req.url.endsWith("/_query"))
      result = {
        data: [{ id: String(query.pageIndex ?? 0), scopes }],
        total: req.url === "/pages" ? 2 : 1,
      };
    if (req.url.endsWith("/events")) result = [];
    if (req.url === "/stream") {
      res.end(JSON.stringify({ scopes }) + "\n");
      return;
    }
    const body = JSON.stringify({ status: 200, result });
    if (
      ["/gzip", "/deflate", "/br", "/stacked", "/error-gzip"].includes(req.url)
    ) {
      const encoding =
        req.url === "/deflate"
          ? "deflate"
          : req.url === "/br"
            ? "br"
            : req.url === "/stacked"
              ? "gzip, br"
              : "gzip";
      res.writeHead(req.url === "/error-gzip" ? 503 : 200, {
        "Content-Encoding": encoding,
      });
      res.end(
        encoding === "br"
          ? brotliCompressSync(body)
          : encoding === "deflate"
            ? deflateSync(body)
            : encoding === "gzip, br"
              ? brotliCompressSync(gzipSync(body))
              : gzipSync(body),
      );
    } else res.end(body);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    await run("http://127.0.0.1:" + server.address().port, seen);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
}

test("async scope callbacks reject before HTTP; returned rejections remain observable", async () => {
  await fixture(async (base, seen) => {
    const root = new JetLinksClient(base);
    const cause = new Error("lookup failed");
    const callback = async (scope) => {
      await Promise.resolve();
      scope.organization("late");
      throw cause;
    };
    try {
      let rejected;
      assert.throws(
        () => root.assetScope(callback),
        (error) => {
          rejected = error;
          return error.code === "INVALID_ARGUMENT";
        },
      );
      await assert.rejects(root.request("/echo", { assetScope: callback }), {
        code: "INVALID_ARGUMENT",
      });
      await assert.rejects(
        root.request("/echo", {
          assetScope: () => ({ then: (_resolve, reject) => reject(cause) }),
        }),
        { code: "INVALID_ARGUMENT" },
      );
      const stream = root.stream("/stream", { assetScope: callback });
      await assert.rejects(stream.next(), { code: "INVALID_ARGUMENT" });
      await new Promise((resolve) => setImmediate(resolve));
      assert.equal(rejected.cause, cause);
      assert.equal(seen.length, 0);
      assert.deepEqual(
        await root.request("/echo", {
          assetScope: (s) => s.organization("ready"),
        }),
        ["org:ready"],
      );
    } finally {
      root.close();
    }
  });
});

test("actual repeated headers, precedence, independent views, pagination and helpers", async () => {
  await fixture(async (base, seen) => {
    const tokens = new CachedTokenProvider(async () => ({
      token: "shared",
      expiresAt: Date.now() + 60000,
    }));
    const root = new JetLinksClient(base, { token: tokens });
    let retained;
    const a = root.assetScope((s) => {
      retained = s;
      s.tenant("T").organization("A");
    });
    retained.organization("mutated");
    const b = a.assetScope((s) => s.organization("B"));
    try {
      assert.deepEqual(await root.request("/echo"), []);
      assert.deepEqual(await a.request("/echo"), ["tenant:T", "org:A"]);
      assert.deepEqual(
        await a.request("/echo", {
          headers: { "x-ASSET-scope": "org:raw" },
          assetScope: (s) => s.organization("old").scope("org", "final:Id"),
        }),
        ["tenant:T", "org:final:Id"],
      );
      assert.deepEqual(
        await b.request("/echo", { headers: { "X-Asset-Scope": "org:raw" } }),
        ["tenant:T", "org:raw"],
      );
      const calls = await Promise.all(
        Array.from({ length: 20 }, (_, i) => (i % 2 ? a : b).request("/echo")),
      );
      calls.forEach((value, i) =>
        assert.deepEqual(value, ["tenant:T", i % 2 ? "org:A" : "org:B"]),
      );
      const pages = await Array.fromAsync(a.pages("/pages", { pageSize: 1 }));
      assert.equal(pages.length, 2);
      pages.forEach((row) =>
        assert.deepEqual(row.scopes, ["tenant:T", "org:A"]),
      );
      assert.deepEqual(await Array.fromAsync(b.stream("/stream")), [
        { scopes: ["tenant:T", "org:B"] },
      ]);
      await Array.fromAsync(a.businessApplication("app").members());
      await a.subscriptions("app").events();
      seen
        .slice(-2)
        .forEach((call) =>
          assert.deepEqual(call.scopes, ["tenant:T", "org:A"]),
        );
      assert.ok(seen.every((call) => call.auth === "Bearer shared"));
      assert.equal(tokens.refreshes, 1);
      a.close();
      await assert.rejects(a.request("/echo"), { code: "INVALID_STATE" });
      assert.deepEqual(await b.request("/echo"), ["tenant:T", "org:B"]);
      root.close();
      await assert.rejects(b.request("/echo"), { code: "INVALID_STATE" });
      assert.throws(() => b.assetScope((s) => s.organization("C")), {
        code: "INVALID_STATE",
      });
      assert.equal(tokens.refreshes, 1);
    } finally {
      root.close();
    }
  });
});

test("invalid scope boundaries fail before HTTP and raw header remains supported", async () => {
  for (const value of [
    "",
    " a",
    "a ",
    "a\n",
    "a\u007f",
    "\u00a0a",
    "a\ufeff",
    "x".repeat(257),
    null,
  ]) {
    assert.throws(() => new AssetScope().organization(value), {
      code: "INVALID_ARGUMENT",
    });
  }
  assert.throws(() => new AssetScope().scope("bad:type", "id"), {
    code: "INVALID_ARGUMENT",
  });
  assert.throws(() => new AssetScope().scope("x".repeat(65), "id"), {
    code: "INVALID_ARGUMENT",
  });
  const builder = new AssetScope();
  for (let i = 0; i < 16; i++) builder.scope("d" + i, "id");
  builder.scope("d0", "replacement");
  assert.throws(() => builder.scope("extra", "id"), {
    code: "INVALID_ARGUMENT",
  });
  await fixture(async (base, seen) => {
    const client = new JetLinksClient(base);
    try {
      await assert.rejects(
        client.request("/echo", { assetScope: (s) => s.tenant("") }),
        { code: "INVALID_ARGUMENT" },
      );
      await assert.rejects(
        client.request("/echo", {
          headers: { "X-Asset-Scope": "missing-colon" },
        }),
        { code: "INVALID_ARGUMENT" },
      );
      assert.equal(seen.length, 0);
      assert.deepEqual(
        await client.request("/echo", {
          headers: { "X-Asset-Scope": "org:raw" },
        }),
        ["org:raw"],
      );
    } finally {
      client.close();
    }
  });
});

test("replacement HTTP transport decodes gzip success and error bodies", async () => {
  await fixture(async (base) => {
    const root = new JetLinksClient(base);
    const client = root.assetScope((s) => s.organization("A"));
    try {
      for (const path of ["/gzip", "/deflate", "/br", "/stacked"])
        assert.deepEqual(await client.request(path), ["org:A"]);
      await assert.rejects(
        client.request("/error-gzip"),
        (error) =>
          error.code === "REMOTE_ERROR" && error.response.result[0] === "org:A",
      );
      const tiny = new JetLinksClient(base, { maxBytes: 8 });
      try {
        await assert.rejects(tiny.request("/gzip"), { code: "LIMIT_EXCEEDED" });
      } finally {
        tiny.close();
      }
    } finally {
      root.close();
    }
  });
});

test("scope views share concurrency and root shutdown; streams cancel and time out; redirects are not followed", async () => {
  const arrivals = new Map();
  let redirectHits = 0;
  const server = http.createServer((req, res) => {
    if (req.url === "/target") {
      redirectHits++;
      res.end("{}");
      return;
    }
    if (req.url === "/redirect") {
      res.writeHead(302, { Location: "/target" });
      res.end("{}");
      return;
    }
    if (req.url === "/stream") {
      res.write('{"first":true}\n');
    }
    arrivals.get(req.url)?.(res);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const base = "http://127.0.0.1:" + server.address().port;
  const root = new JetLinksClient(base, { timeout: 2000, maxConcurrency: 1 });
  const a = root.assetScope((s) => s.organization("A"));
  const b = root.assetScope((s) => s.organization("B"));
  const arrived = new Promise((resolve) => arrivals.set("/hold", resolve));
  const pending = a.request("/hold");
  const failed = assert.rejects(pending, { code: "TRANSPORT" });
  try {
    await arrived;
    await assert.rejects(b.request("/other"), { code: "LIMIT_EXCEEDED" });
    root.close();
    await failed;
    await assert.rejects(b.request("/other"), { code: "INVALID_STATE" });

    const client = new JetLinksClient(base, {
      timeout: 100,
      maxConcurrency: 1,
    });
    try {
      const streamArrived = new Promise((resolve) =>
        arrivals.set("/stream", resolve),
      );
      const rows = client.stream("/stream");
      assert.deepEqual((await rows.next()).value, { first: true });
      const response = await streamArrived;
      const disconnected = new Promise((resolve) =>
        response.on("close", resolve),
      );
      await rows.return();
      await disconnected;
      await assert.rejects(client.request("/timeout"), { code: "TRANSPORT" });
      await assert.rejects(client.request("/redirect"), {
        code: "REMOTE_ERROR",
      });
      assert.equal(redirectHits, 0);
      const aborted = new AbortController();
      const abortArrived = new Promise((resolve) =>
        arrivals.set("/abort", resolve),
      );
      const cancelled = assert.rejects(
        client.request("/abort", { signal: aborted.signal }),
        { code: "TRANSPORT" },
      );
      await abortArrived;
      aborted.abort();
      await cancelled;
      const stalled = client.stream("/stream");
      await stalled.next();
      await assert.rejects(stalled.next(), { code: "TRANSPORT" });
    } finally {
      client.close();
    }
  } finally {
    root.close();
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
