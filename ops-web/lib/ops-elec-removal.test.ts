import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { NAV } from "./nav";
import { BACKEND_ROLE_PERMS, permsOf } from "./permissions";
import { UI_PERM_MAP } from "./perm-map";

const root = process.cwd();

describe("operations electronic-components retirement", () => {
  it("removes the operations pages and API slices", () => {
    for (const file of [
      "app/elec/page.tsx", "lib/api/contracts/elec.ts", "lib/api/https/elec.ts", "lib/api/mocks/elec.ts", "lib/types/elec.ts",
    ]) expect(existsSync(resolve(root, file)), `${file} should be retired`).toBe(false);
    expect(NAV.some((section) => section.key === "elec")).toBe(false);
  });
  it("removes electronic-components permissions from the operations UI roles", () => {
    expect(Object.keys(UI_PERM_MAP).some((code) => code.startsWith("elec:"))).toBe(false);
    for (const role of ["PRODUCT_OPS", "MERCHANT_BD", "CS", "RISK", "ELEC_ADMIN"]) {
      expect(permsOf(role as never).some((code) => code.startsWith("elec:"))).toBe(false);
      expect((BACKEND_ROLE_PERMS[role] ?? []).some((code) => code.startsWith("elec:"))).toBe(false);
    }
    expect(BACKEND_ROLE_PERMS.ELEC_ADMIN).toEqual(["dashboard:overview:read"]);
  });
});
