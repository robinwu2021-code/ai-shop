import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { it } from "vitest";

// Keep the standalone Node regressions on the normal shared-guard execution path.
for (const file of ["ddl-column-definitions.test.mjs", "biz-role-migrations.test.mjs"]) {
  it(`guard parser regression: ${file}`, () => {
    execFileSync(process.execPath, ["--test", resolve(import.meta.dirname, "../../../scripts/tests", file)], {
      stdio: "pipe",
    });
  });
}
