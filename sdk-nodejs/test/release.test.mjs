import test from "node:test";
import assert from "node:assert/strict";
import { releaseInfo } from "../tools/release.mjs";

test("release tags match canonical versions and choose the public npm channel", () => {
  for (const version of ["2.0.0-alpha.0", "2.0.0-rc.1", "2.0.0-dev.123"]) {
    assert.deepEqual(releaseInfo(`refs/tags/node-v${version}`, version), {
      version,
      dist_tag: "next",
    });
  }
  assert.deepEqual(releaseInfo("refs/tags/node-v2.0.0", "2.0.0"), {
    version: "2.0.0",
    dist_tag: "latest",
  });
});

test("branches, mismatched tags and noncanonical versions cannot publish", () => {
  for (const ref of [
    "refs/heads/2.0",
    "refs/heads/node-v2.0.0",
    "refs/tags/v2.0.0",
    "refs/tags/node-v2.0.1",
    "refs/tags/node-v2.0.0\n",
  ]) {
    assert.throws(() => releaseInfo(ref, "2.0.0"), /Expected tag/);
  }
  for (const version of [
    "v2.0.0",
    "2.0",
    "02.0.0",
    "2.0.0-alpha.01",
    "2.0.0+build.1",
    "2.0.0\n",
    undefined,
  ]) {
    assert.throws(
      () => releaseInfo(`refs/tags/node-v${version}`, version),
      /canonical SemVer/,
    );
  }
});
