import { afterEach, expect, it, vi } from "vitest";
vi.mock("../../auth", () => ({ currentAuth: () => ({ token: "TOKEN", username: "op" }), scopeOf: () => ({ tenantNo: "MAIN" }), refreshPerms: vi.fn(), useAuth: { getState: () => ({ logout: vi.fn() }) } }));
vi.mock("../../notify", () => ({ notify: { error: vi.fn() } }));
vi.mock("../../stores/locale", () => ({ useLocaleStore: { getState: () => ({ locale: "zh" }) } }));
import { systemHttp } from "./system";
afterEach(() => vi.unstubAllGlobals());

it("maintains the exact store industry key and expected version through HTTP", async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, data: [] }) });
  vi.stubGlobal("fetch", fetch);
  await systemHttp.listStoreIndustries();
  const command = { industryCode: "RETAIL", subIndustryCode: "CVS", businessTypeCode: "RETAIL", nameZh: "便利店", nameTw: "便利商店", nameEn: "Convenience", sort: 2, enabled: false, expectedVersion: 7, reason: "暂停新增" };
  await systemHttp.saveStoreIndustry(command);
  expect(fetch.mock.calls[0][0]).toContain("/ops/store-industries");
  expect(fetch.mock.calls[1][1].method).toBe("POST");
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(command);
});

it("surfaces a version conflict without pretending the edit was saved", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ code: 10409, msg: "冲突" }) }));
  await expect(systemHttp.saveStoreIndustry({ industryCode: "RETAIL", subIndustryCode: "", businessTypeCode: "RETAIL", nameZh: "零售", nameTw: "零售", sort: 0, enabled: true, expectedVersion: 0, reason: "修改" })).rejects.toMatchObject({ code: 10409 });
});
