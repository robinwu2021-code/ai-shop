// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const mock = vi.hoisted(() => ({ types: vi.fn(), rows: vi.fn(), detail: vi.fn(), stats: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { pointsTypes: mock.types, pointsTransactions: mock.rows, pointsTransaction: mock.detail, pointsPeriodStats: mock.stats } }));
vi.mock("@/lib/use-can", () => ({ useCan: () => () => true }));
vi.mock("@/app/points/earning-events", () => ({ EarningEvents: () => null }));
vi.mock("@/app/points/order-earning-details", () => ({ OrderEarningDetails: () => null }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: ({ open, title, children }: { open: boolean; title: string; children: unknown }) => open ? h("aside", {}, h("h2", {}, title), children as never) : null }));
vi.mock("@/components/ui/data-table", () => ({ DataTable: ({ rows = [], columns, rowProps }: { rows: never[]; columns: { header: string; cell: (r: never) => unknown }[]; rowProps?: (r: never) => object }) => h("div", {}, ...columns.map((c, i) => h("span", { key: `h${i}` }, c.header)), ...rows.map((r, i) => h("div", { key: i, ...rowProps?.(r) }, ...columns.map((c, j) => h("span", { key: j }, c.cell(r) as never))))) }));
import PointsTransactionsPage from "@/app/points/transactions/page";
let host: HTMLDivElement, root: Root, query: QueryClient;
const voucher = { voucherNo: "V1", documentNo: "PV1", bizRef: "TEST1", createdAt: 1, currency: "CNY", ratePoints: 1, rateMinor: 1 };
const row = { direction: "EARN", voucher, storeNo: "S1", userNo: "U1", pointsDelta: 20 };
const flush = () => act(async () => { await new Promise(r => setTimeout(r, 20)); });
async function render() {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTransactionsPage))));
  await vi.waitFor(async () => {
    await act(async () => {});
    expect(host.querySelector('[role="button"]')).not.toBeNull();
  });
}
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks(); mock.types.mockResolvedValue([{ typeCode: "HX", name: "平台积分" }]); mock.rows.mockResolvedValue({ rows: [row], hasMore: false });
  mock.detail.mockResolvedValue({ voucher, entries: { rows: [{ entryNo: "E1", holderType: "USER", holderNo: "U1", bucket: "AVAILABLE", deltaPoints: 20, afterPoints: 20 }], hasMore: false } });
  mock.stats.mockResolvedValue({ metrics: {}, from: "2026-01-01", to: "2026-01-02" });
  host = document.createElement("div"); document.body.append(host); root = createRoot(host); query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
it("keeps statistics opt-in and splits ledger identity fields", async () => {
  // Model a type lookup that completes after the former fixed 20ms render wait.
  mock.types.mockImplementationOnce(async () => { await new Promise(resolve => setTimeout(resolve, 80)); return [{ typeCode: "HX", name: "平台积分" }]; });
  await render(); expect(mock.stats).not.toHaveBeenCalled();
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click()); await flush();
  expect(host.querySelector("aside")!.textContent).toContain("持有人编号"); expect(host.querySelector("aside")!.textContent).not.toContain("USER · U1");
  expect(host.querySelector("aside")!.textContent).not.toContain("门店 / 会员");
});
it("closes stale details when the transaction direction changes", async () => {
  await render(); await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  const direction = host.querySelectorAll("select")[1];
  await act(async () => { direction.value = "REDEEM"; direction.dispatchEvent(new Event("change", { bubbles: true })); }); await flush();
  expect(host.querySelector("aside")).toBeNull();
  expect(mock.rows).toHaveBeenLastCalledWith(expect.objectContaining({ direction: "REDEEM", after: 0 }));
});
it("rejects invalid dates without showing an endless loading state", async () => {
  await render(); const dates = host.querySelectorAll('input[type="date"]'); mock.rows.mockClear();
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(dates[0], "2099-01-01"); dates[0].dispatchEvent(new Event("input", { bubbles: true })); });
  expect(mock.rows).not.toHaveBeenCalled();
  const search = [...host.querySelectorAll("button")].find(b => b.textContent === "查询")!;
  expect(search.disabled).toBe(true); expect(host.textContent).toContain("结束日期不能早于开始日期");
});
it("fetches summary on demand using its own date and type scope", async () => {
  await render();
  await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "统计概览")!.click()); await flush();
  expect(mock.stats).toHaveBeenCalledTimes(1);
  expect(mock.stats).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "HX" }));
  expect(host.textContent).toContain("不按门店、会员或方向筛选");
});

it("exposes manual refunds from the transactions workspace", async () => {
  await render(); expect([...host.querySelectorAll("button")].some(b => b.textContent === "退款人工处理")).toBe(true);
});
