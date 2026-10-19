import { describe, it, expect, vi } from "vitest";
import { loadStockOverviewMap, createStockRequestScope } from "../src/shared/stock-overview";
import type { StockOverview } from "@shared/types";
const sample = { storeNo: "S", skuNo: "K", onHand: 20, reserved: 5, available: 15,
  supplyAvailable: 12, onlineSellable: 0, syncHealth: "NOT_ASSESSED" } as StockOverview;

describe("库存总览读取", () => {
  it("一次批量读取，去重且不拿物料号冒充SKU", async () => {
    const fetch = vi.fn().mockResolvedValue([sample]);
    const map = await loadStockOverviewMap("S", [{ skuNo: "K" }, { skuNo: "K" }, {}], fetch);
    expect(fetch).toHaveBeenCalledExactlyOnceWith("S", ["K"]);
    expect(map.K.onlineSellable).toBe(0);
    expect(map.K.available).toBe(15);
    expect(map.K.supplyAvailable).toBe(12);
  });
  it("无关联SKU不查询；异常与空记录不伪造成零", async () => {
    const fetch = vi.fn().mockRejectedValue(new Error("offline"));
    expect(await loadStockOverviewMap("S", [{}], fetch)).toEqual({});
    expect(fetch).not.toHaveBeenCalled();
    await expect(loadStockOverviewMap("S", [{ skuNo: "K" }], fetch)).rejects.toThrow("offline");
  });
  it("丢弃跨店和请求范围之外的响应", async () => {
    const map = await loadStockOverviewMap("S", [{ skuNo: "K" }], vi.fn().mockResolvedValue([
      { ...sample, storeNo: "OTHER" }, { ...sample, skuNo: "OTHER" },
    ]));
    expect(map).toEqual({});
  });
  it("新请求或切店后旧响应失效", () => {
    const scope = createStockRequestScope();
    const old = scope.begin("A");
    const now = scope.begin("B");
    expect(scope.current(old, "A")).toBe(false);
    expect(scope.current(now, "B")).toBe(true);
    expect(scope.current(now, "C")).toBe(false);
  });
});
