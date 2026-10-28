import { afterEach, expect, it, vi } from "vitest";
vi.mock("../auth", () => ({ currentAuth: () => ({ token: "TOKEN", username: "operator" }), scopeOf: () => ({ tenantNo: "TEN" }), refreshPerms: vi.fn(), useAuth: { getState: vi.fn() } }));
vi.mock("../notify", () => ({ notify: { error: vi.fn() } }));
vi.mock("../stores/locale", () => ({ useLocaleStore: { getState: () => ({ locale: "en" }) } }));
import { client } from "./http-client";
afterEach(() => vi.unstubAllGlobals());
it("adds idempotency without discarding authenticated scope or serializing it into the body", async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, data: { operationNo: "OP1" } }) });
  vi.stubGlobal("fetch", fetch);
  const body = { reason: "review", dryRun: true };
  await client.post("/ops/print/reconciliations", body, undefined, { "Idempotency-Key": "ORIGINAL" });
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/ops/print/reconciliations"), expect.objectContaining({
    method: "POST", body: JSON.stringify(body), headers: expect.objectContaining({ Authorization: "Bearer TOKEN", "X-Tenant-No": "TEN", "Idempotency-Key": "ORIGINAL", "Content-Type": "application/json" }),
  }));
});
