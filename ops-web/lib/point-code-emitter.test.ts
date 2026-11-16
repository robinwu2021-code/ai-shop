import { expect, it } from "vitest";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { execFileSync } from "node:child_process";

function frozen(source: string): Map<string, string> {
  return new Map([...source.matchAll(/^\s*"((?:[^"\\]|\\.)*)":\s*"([^"]+)"/gm)]
    .map((m) => [JSON.parse(`"${m[1]}"`) as string, m[2]]));
}

it("emitting a new menu preserves every frozen code across merged sections", () => {
  const ops = new URL("..", import.meta.url).pathname;
  const root = mkdtempSync(join(tmpdir(), "ops-point-emitter-"));
  try {
    for (const file of ["ops-web/scripts/gen-perm-seed.mjs", "ops-web/lib/nav.ts", "ops-web/lib/platform-menu-aliases.json", "ops-web/lib/perm-map.ts", "ops-web/lib/point-codes.ts", "backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java"]) {
      const target = join(root, file);
      mkdirSync(join(target, ".."), { recursive: true });
      writeFileSync(target, readFileSync(join(ops, "..", file)));
    }
    const before = frozen(readFileSync(join(root, "ops-web/lib/point-codes.ts"), "utf8"));
    execFileSync(process.execPath, [join(root, "ops-web/scripts/gen-perm-seed.mjs"), "--emit-point-codes"], { stdio: "pipe" });
    const after = frozen(readFileSync(join(root, "ops-web/lib/point-codes.ts"), "utf8"));
    expect(before.size).toBeGreaterThan(100);
    for (const [key, value] of before) expect(after.get(key), key).toBe(value);
    expect(after.get("/mall")).toBe("OPS_COMMERCE");
    expect(after.get("/system?tab=storeIndustry")).toBe("OPS_SYSTEM__TAB_STOREINDUSTRY");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
