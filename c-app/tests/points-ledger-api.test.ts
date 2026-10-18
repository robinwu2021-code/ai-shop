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
    await httpApi.pointsEntries("CN", 42);
    expect(get).toHaveBeenLastCalledWith("/mp/points/entries",
      { typeCode: "CN", after: 42, limit: 20 }, undefined);
  });
});
