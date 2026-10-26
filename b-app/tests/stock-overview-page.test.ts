// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import { mount, flushPromises } from "@vue/test-utils";
import "./component-setup";
import type { StockOverview } from "@shared/types";
const m = vi.hoisted(() => ({
  prompt: vi.fn(), adjust: vi.fn(), summary: vi.fn(), balances: vi.fn(), locations: vi.fn(), batch: vi.fn(), item: vi.fn(), ledger: vi.fn(), overview: vi.fn(),
  store: {} as { storeNo: string; can: () => boolean; multiStore: boolean; ensureStores: () => Promise<void> },
  show: undefined as (() => void) | undefined,
}));
vi.mock("@/api", () => ({ api: { mStockSummary: m.summary, mStockBalances: m.balances,
  mStockLocations: m.locations, mStockOverviews: m.batch, mStockItem: m.item,
  mStockLedger: m.ledger, mStockOverview: m.overview, mStockAdjust: m.adjust } }));
vi.mock("@ai-shop/ui/prompt", async (original) => ({ ...await original<object>(), prompt: m.prompt }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => m.store }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string, p?: Record<string, unknown>) => k + (p ? JSON.stringify(p) : "") }) }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => void) => { m.show = cb; },
  onLoad: (cb: (q: { itemId: string }) => void) => cb({ itemId: "I" }) }));
import List from "@/pages/stock/index.vue";
import Detail from "@/pages/stock-detail/index.vue";
const row = { itemId: "I", skuNo: "K", name: "Pear", baseUom: "件", onHand: 20, reserved: 5, available: 15, flags: [] };
const overview = { storeNo: "S", skuNo: "K", unit: "件", inventoryState: "MAPPED", accountingMode: "PAYMENT_DEDUCT",
  onHand: 20, reserved: 5, available: 15, safetyStock: 3, supplyAvailable: 12, onlineSellable: 0,
  onlineLocked: 5, salesStockMode: "STORE", ruleSource: { scopeType: "STORE", scopeRef: "S", ruleType: "ALL", param: 0 },
  syncEnabled: true, syncHealth: "NOT_ASSESSED", asOf: 1 } as StockOverview;
const options = { global: { mocks: { $t: (k: string) => k }, stubs: { "sh-scaffold": { template: "<div><slot/></div>" } } } };
beforeEach(() => {
  vi.clearAllMocks();
  m.store = reactive({ storeNo: "S", can: () => true, multiStore: false, ensureStores: async () => {} });
  m.summary.mockResolvedValue({ itemCount: 1, shortageCount: 1, staleCount: 0, inTransitCount: 0 });
  m.balances.mockResolvedValue([row]); m.locations.mockResolvedValue([]); m.batch.mockResolvedValue([overview]);
  m.item.mockResolvedValue({ ...row, byLocation: [] }); m.ledger.mockResolvedValue({ entries: [], nextCursor: null });
  m.overview.mockResolvedValue(overview);
});
describe("库存页面只读接入", () => {
  it("列表保留库存并展示线上零可售，批量查询一次", async () => {
    const w = mount(List, options); m.show?.(); await flushPromises();
    expect(w.text()).toContain('stockOverview.onlineN{"n":0');
    expect(w.text()).toContain("Pear");
    expect(m.batch).toHaveBeenCalledExactlyOnceWith("S", ["K"]); w.unmount();
  });
  it("线上接口失败不隐藏库存，无SKU不查询", async () => {
    m.batch.mockRejectedValue(new Error("offline"));
    const w = mount(List, options); m.show?.(); await flushPromises();
    expect(w.text()).toContain("Pear"); expect(w.text()).toContain("stockOverview.unavailable"); w.unmount();
    m.balances.mockResolvedValue([{ ...row, skuNo: undefined }]); m.batch.mockClear();
    const other = mount(List, options); m.show?.(); await flushPromises();
    expect(other.text()).toContain("stockOverview.unmapped"); expect(m.batch).not.toHaveBeenCalled(); other.unmount();
  });
  it("详情展示账面口径与未检查状态，不将开关视为同步成功", async () => {
    const w = mount(Detail, options); m.show?.(); await flushPromises();
    expect(w.text()).toContain("stockOverview.book"); expect(w.text()).toContain("stockOverview.notAssessed");
    expect(w.text()).toContain("stockOverview.totalScope"); expect(w.text()).toContain("stockOverview.supplyHint");
    expect(w.text()).toContain("12"); w.unmount();
  });
  it("库存流水将长单号收缩显示，不挤占数量和操作人", async () => {
    const docNo = "U202609161449430000150";
    m.ledger.mockResolvedValue({ entries: [{ id: 1, itemId: "I", itemName: "Pear", docKind: "IN",
      docNo, reasonCode: "RETURN", qtyDelta: 1, balanceAfter: 1, occurredAt: "2026-09-23T04:24:00Z",
      operator: "长名称操作员" }], nextCursor: null });
    const w = mount(Detail, options); m.show?.(); await flushPromises();
    expect(w.find(".led__doc").text()).toBe(docNo);
    expect(w.find(".led__doc").attributes("title")).toBe(docNo);
    expect(w.find(".led__operator").text()).toBe("长名称操作员");
    w.unmount();
  });
  it("盘点输入期间切换门店不会将旧店操作提交到新店", async () => {
    let resolveInput!: (v: string) => void;
    m.prompt.mockImplementationOnce(() => new Promise(r => { resolveInput = r; }));
    const w = mount(Detail, options); m.show?.(); await flushPromises();
    await w.findAll(".sh-btn").find(n => n.text() === "stockDetail.adjustTitle")!.trigger("tap");
    m.store.storeNo = "B"; await flushPromises();
    resolveInput("20"); await flushPromises();
    expect(m.adjust).not.toHaveBeenCalled(); w.unmount();
  });
  it("切换门店后不会应用旧店慢响应", async () => {
    let resolveOld!: (v: StockOverview[]) => void;
    m.batch.mockImplementationOnce(() => new Promise(r => { resolveOld = r; }))
      .mockResolvedValueOnce([{ ...overview, storeNo: "B", onlineSellable: 7 }]);
    const w = mount(List, options); m.show?.(); await flushPromises();
    m.store.storeNo = "B"; await flushPromises();
    resolveOld([overview]); await flushPromises();
    expect(w.text()).toContain('stockOverview.onlineN{"n":7');
    expect(w.text()).not.toContain('stockOverview.onlineN{"n":0'); w.unmount();
  });
});
