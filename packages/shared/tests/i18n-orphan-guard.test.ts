import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { expect, it } from "vitest";

it("keeps server permission literals distinct from actual translation references", () => {
  const result = spawnSync(process.execPath,
    ["--test", join(import.meta.dirname, "../../../scripts/tests/i18n-orphan.test.mjs")],
    { encoding: "utf8" });
  expect(result.status, result.stdout + result.stderr).toBe(0);
}, 15_000);
