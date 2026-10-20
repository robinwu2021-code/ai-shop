import { describe, expect, it, vi } from "vitest";

const get = vi.hoisted(() => vi.fn(async () => []));
vi.mock("@shared/net/http-client", () => ({
  clientTag: () => "MP",
  http: { get, post: vi.fn(), put: vi.fn() },
}));

import { httpApi } from "@/api/http";

describe("买家积分账本读取", () => {
  it("按类型读取账户和游标流水，不传可伪造的用户号", async () => {
    await httpApi.pointsAccounts();
    expect(get).toHaveBeenLastCalledWith("/mp/points/accounts", undefined, undefined);
    await httpApi.orderPointsEarning("S");
    expect(get).toHaveBeenLastCalledWith("/mp/order/S/points-earning", undefined, undefined);
    await httpApi.pointsEntries("CN", 42);
    expect(get).toHaveBeenLastCalledWith("/mp/points/entries",
      { typeCode: "CN", after: 42, limit: 20 }, undefined);
  });
});

it("uses the name field and rejects nameless accounts instead of falling back to SYS_TOKEN", async () => {
  get.mockResolvedValueOnce([{ typeCode: "SYS_TOKEN", name: "商城积分", balance: 15 }] as never);
  expect(await httpApi.pointsAccounts()).toMatchObject([{ typeCode: "SYS_TOKEN", name: "商城积分", balance: 15 }]);
  get.mockResolvedValueOnce([{ typeCode: "SYS_TOKEN", balance: 15 }] as never);
  await expect(httpApi.pointsAccounts()).rejects.toThrow("POINTS_NAME_MISSING");
});
