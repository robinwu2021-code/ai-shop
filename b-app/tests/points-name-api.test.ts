import { expect, it, vi } from "vitest";
const get = vi.hoisted(() => vi.fn());
vi.mock("@shared/net/http-client", () => ({ http: { get, post: vi.fn(), put: vi.fn() }, clientTag: () => "APP" }));
import { httpApi } from "@/api/http";
it("loads the real name field while preserving the ledger code", async () => {
  get.mockResolvedValueOnce([{ typeCode: "SYS_TOKEN", name: "商城积分", currency: "CNY" }]);
  expect(await httpApi.mStorePointTypes()).toMatchObject([{ typeCode: "SYS_TOKEN", name: "商城积分" }]);
  get.mockResolvedValueOnce([{ typeCode: "SYS_TOKEN", name: " " }]);
  await expect(httpApi.mStorePointTypes()).rejects.toThrow("POINTS_NAME_MISSING");
});
