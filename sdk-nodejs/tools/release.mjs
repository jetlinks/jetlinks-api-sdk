import { appendFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import semver from "semver";

/** Tag 与包版本绑定；npm 忽略 build metadata，不能用它区分发布版本。 */
export function releaseInfo(ref, version) {
  const parsed = typeof version === "string" ? semver.parse(version) : null;
  if (!parsed || parsed.version !== version || parsed.build.length)
    throw new Error("Expected a canonical SemVer without build metadata");
  if (ref !== `refs/tags/node-v${version}`)
    throw new Error(
      `Expected tag refs/tags/node-v${version} matching package.json`,
    );
  return { version, dist_tag: parsed.prerelease.length ? "next" : "latest" };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const { version } = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8"),
  );
  const info = releaseInfo(process.argv[2], version);
  const output =
    Object.entries(info)
      .map(([key, value]) => `${key}=${value}`)
      .join("\n") + "\n";
  if (process.env.GITHUB_OUTPUT)
    appendFileSync(process.env.GITHUB_OUTPUT, output);
  process.stdout.write(output);
}
