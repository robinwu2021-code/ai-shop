import { describe, expect, it, vi } from "vitest";
import { dashboardMock } from "./dashboard";
import { BACKEND_ROLE_PERMS } from "../../permissions";
vi.mock("./_wait", () => ({ wait: (value: unknown) => Promise.resolve(value) }));
describe("explicit platform browser fixture identities", () => {
  it.each([
    ["admin", "SUPER_ADMIN"], ["bd", "MERCHANT_BD"], ["goods", "PRODUCT_OPS"], ["support", "CS"],
    ["campaign", "CAMPAIGN_OPS"], ["community", "COMMUNITY_OPS"], ["auditor", "AUDITOR"], ["finance", "FINANCE"],
    ["risk", "RISK"], ["analyst", "ANALYST"], ["tech", "TECH_OPS"], ["elec", "ELEC_ADMIN"],
  ])("%s resolves only its actual role permissions", async (username, role) => {
    const result = await dashboardMock.login(username!, "local-fixture-only");
    expect(result.role).toBe(role);
    expect(result.perms).toEqual(BACKEND_ROLE_PERMS[role!]);
    if (role !== "SUPER_ADMIN") expect(result.perms).not.toContain("*");
  });
});
