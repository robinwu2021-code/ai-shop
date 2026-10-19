// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, createElement as h, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Order } from "@/lib/types";

const mock = vi.hoisted(() => ({ impact: vi.fn(), cancel: vi.fn(), list: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  getCancellationImpact: mock.impact, proxyCancelOrder: mock.cancel, listOrders: mock.list,
  getProxyLimit: async () => ({ maxAmountMinor: 100000, maxPerDay: 10 }),
  listCommunities: async () => ({ records: [] }), listMerchants: async () => ({ records: [] }),
} }));
vi.mock("@/lib/use-can", () => ({ useCan: () => () => false }));
vi.mock("@/lib/notify", () => ({ notify: { success: vi.fn() } }));
vi.mock("@/components/status", async () => {
  const { createElement: h } = await import("react");
  return { OrderStatusBadge: ({ value }: { value: string }) => h("span", {}, value),
    useFulfillmentTypeMap: () => ({ STORE_PICKUP: "到店自取" }) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  const slot = ({ children }: { children?: ReactNode }) => h("div", {}, children);
  return { Drawer: ({ open, children, footer, onOpenChange }: { open?: boolean; children?: ReactNode;
    footer?: ReactNode; onOpenChange: (open: boolean) => void }) =>
    open ? h("div", { role: "dialog" }, h("button", { onClick: () => onOpenChange(false) }, "关闭"), children, footer) : null,
    DrawerSection: slot, Field: slot, FieldGrid: slot };
});
vi.mock("@/components/ui/paged-table", async () => {
  const { createElement: h } = await import("react");
  return { PagedTable: ({ query, columns }: { query: { data?: { records: Order[] } };
    columns: { cell: (order: Order) => ReactNode }[] }) => h("div", {},
    ...(query.data?.records || []).map((row) => h("div", { key: row.orderNo },
      ...columns.map((column, i) => h("span", { key: i }, column.cell(row)))))) };
});
vi.mock("@/components/ui/help-note", () => ({ HelpNote: () => null }));

import { ProxyTab } from "@/app/orders/proxy-tab";
import { ORDERS_COPY } from "@/app/orders/copy";
import { orders } from "@/lib/mock/db";

const copy = ORDERS_COPY.zh;
let root: Root;
let host: HTMLDivElement;
let query: QueryClient;
async function flush() { await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); }); }
async function render() {
  host = document.createElement("div"); document.body.append(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 15000 } } });
  root = createRoot(host);
  await act(async () => { root.render(h(QueryClientProvider, { client: query }, h(ProxyTab, { c: copy, canProxy: true }))); });
  await flush();
  const button = Array.from(host.querySelectorAll("button")).find((node) => node.textContent === copy.actionProxyCancel);
  expect(button).toBeDefined();
  await act(async () => { button!.click(); });
  await flush();
  return host.querySelector('[role="dialog"]')!;
}
async function enterReason(dialog: Element) {
  const input = dialog.querySelector("textarea")!;
  const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!;
  await act(async () => {
    set.call(input, "买家要求取消");
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await flush();
}

function confirmButton(dialog: Element) {
  return Array.from(dialog.querySelectorAll("button")).find((node) => node.textContent === copy.btnProxyCancel)!;
}

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.list.mockResolvedValue({ records: [{ ...orders[0], orderNo: "S1", parentNo: "P1", status: "WAIT_PAY",
    merchantName: "店一", payAmount: 1000 }] });
  mock.cancel.mockResolvedValue({ status: "CANCELLED" });
});
afterEach(async () => {
  if (root) await act(async () => root.unmount());
  query?.clear(); host?.remove();
});

describe("代客取消抽屉基于服务端完整影响集合", () => {
  it("离开页面重挂后同一单不会复用旧预览请求", async () => {
    mock.impact.mockImplementationOnce(() => new Promise(() => {}));
    await render();
    mock.impact.mockResolvedValue({ canCancel: false, blockedCode: "ORDER_STATE_ILLEGAL" });
    await act(async () => { root.unmount(); });
    root = createRoot(host);
    await act(async () => {
      root.render(h(QueryClientProvider, { client: query }, h(ProxyTab, { c: copy, canProxy: true })));
    });
    await flush();
    const action = Array.from(host.querySelectorAll("button")).find((node) => node.textContent === copy.actionProxyCancel)!;
    await act(async () => { action.click(); });
    await flush();
    expect(mock.impact).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain(copy.cancelImpactState);
  });

  it("关闭后立即重开同一单必须重新预览，不能使用15秒内的旧授权", async () => {
    mock.impact.mockResolvedValueOnce({ canCancel: true, payOrderNo: "P1", requestedOrderNo: "S1",
      totalPayableMinor: 1000, currency: "CNY", affectedOrders: [] });
    const dialog = await render();
    await enterReason(dialog);
    expect(confirmButton(dialog).disabled).toBe(false);
    mock.impact.mockResolvedValue({ canCancel: false, blockedCode: "ORDER_STATE_ILLEGAL" });
    await act(async () => { (dialog.querySelector("button") as HTMLButtonElement).click(); });
    const action = Array.from(host.querySelectorAll("button")).find((node) => node.textContent === copy.actionProxyCancel)!;
    await act(async () => { action.click(); });
    await flush();
    const reopened = host.querySelector('[role="dialog"]')!;
    await enterReason(reopened);
    expect(mock.impact).toHaveBeenCalledTimes(2);
    expect(confirmButton(reopened).disabled).toBe(true);
    expect(reopened.textContent).toContain(copy.cancelImpactState);
  });

  it("成功缓存的预览回查失败后不能继续确认", async () => {
    mock.impact.mockResolvedValue({ canCancel: true, payOrderNo: "P1", requestedOrderNo: "S1",
      totalPayableMinor: 1000, currency: "CNY", affectedOrders: [] });
    const dialog = await render();
    await enterReason(dialog);
    expect(confirmButton(dialog).disabled).toBe(false);
    mock.impact.mockRejectedValue(new Error("preview timed out"));
    await act(async () => { await query.invalidateQueries({ queryKey: ["order-cancel-impact", "S1"] }); });
    await flush();
    expect(dialog.textContent).toContain(copy.cancelImpactFailed);
    expect(confirmButton(dialog).disabled).toBe(true);
    confirmButton(dialog).click();
    expect(mock.cancel).not.toHaveBeenCalled();
  });

  it("S1取消迟到成功不会关闭随后打开的S2抽屉", async () => {
    mock.list.mockResolvedValue({ records: [
      { ...orders[0], orderNo: "S1", parentNo: "P1", status: "WAIT_PAY", merchantName: "店一", payAmount: 1000 },
      { ...orders[0], orderNo: "S2", parentNo: "P2", status: "WAIT_PAY", merchantName: "店二", payAmount: 2000 },
    ] });
    mock.impact.mockImplementation((no: string) => Promise.resolve({ canCancel: true, requestedOrderNo: no,
      payOrderNo: no === "S1" ? "P1" : "P2", totalPayableMinor: no === "S1" ? 1000 : 2000,
      currency: "CNY", affectedOrders: [] }));
    let finish!: (result: unknown) => void;
    mock.cancel.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const dialog = await render();
    await enterReason(dialog);
    await act(async () => { confirmButton(dialog).click(); });
    await flush();
    expect(mock.cancel).toHaveBeenCalledWith({ orderNo: "S1", reason: "买家要求取消" });
    const actions = Array.from(host.querySelectorAll("button")).filter((node) => node.textContent === copy.actionProxyCancel);
    await act(async () => { actions[1]!.click(); });
    await flush();
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain("店二");
    await act(async () => { finish({ orderNo: "S1", status: "CANCELLED" }); });
    await flush();
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain("店二");
  });

  it("等待影响预览时不能确认", async () => {
    mock.impact.mockImplementation(() => new Promise(() => {}));
    const dialog = await render();
    expect(mock.impact).toHaveBeenCalledWith("S1");
    expect(confirmButton(dialog).disabled).toBe(true);
  });

  it("跨两店显示整笔30元及被影响的第二家门店", async () => {
    mock.impact.mockResolvedValue({ canCancel: true, payOrderNo: "P1", requestedOrderNo: "S1",
      totalPayableMinor: 3000, currency: "CNY", affectedOrders: [
        { subOrderNo: "S1", merchantNo: "M1", merchantName: "店一", storeNo: "ST1", storeName: "门店一", payableMinor: 1000, status: "WAIT_PAY" },
        { subOrderNo: "S2", merchantNo: "M2", merchantName: "店二", storeNo: "ST2", storeName: "门店二", payableMinor: 2000, status: "WAIT_PAY" },
      ] });
    const dialog = await render();
    expect(dialog.textContent).toContain("30.00");
    expect(dialog.textContent).toContain("门店二");
    expect(dialog.textContent).toContain("S2");
  });

  it("缺少完整授权时不显示隐藏明细，并禁用取消", async () => {
    mock.impact.mockResolvedValue({ canCancel: false, blockedCode: "FORBIDDEN" });
    const dialog = await render();
    expect(mock.impact).toHaveBeenCalledWith("S1");
    expect(confirmButton(dialog).disabled).toBe(true);
    expect(dialog.textContent).not.toContain("S2");
    expect(dialog.textContent).not.toContain("30.00");
    expect(mock.cancel).not.toHaveBeenCalled();
  });
});
