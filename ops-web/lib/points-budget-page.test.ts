// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const mock = vi.hoisted(() => ({ types: vi.fn(), requests: vi.fn(), overview: vi.fn(), submit: vi.fn(), review: vi.fn() }));
vi.mock("@/lib/api", () => ({ IS_MOCK: false, api: { pointsTypes: mock.types, pointsRequests: mock.requests, pointsOverview: mock.overview, submitPointsRequest: mock.submit, reviewPointsRequest: mock.review } }));
vi.mock("@/lib/use-can", () => ({ useCan: () => () => true }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: ({ open, title, children, footer }: { open: boolean; title: string; children: unknown; footer: unknown }) => open ? h("aside", {}, h("h2", {}, title), children as never, footer as never) : null }));
vi.mock("@/components/ui/data-table", () => ({ DataTable: ({ rows = [], columns, rowProps }: { rows: never[]; columns: { header: string; cell: (r: never) => unknown }[]; rowProps: (r: never) => object }) => h("div", {}, h("div", {}, ...columns.map((c, i) => h("span", { key: i }, c.header))), ...rows.map((r, i) => h("button", { key: i, ...rowProps(r) }, ...columns.map((c, j) => h("span", { key: j }, c.cell(r) as never))))) }));
import PointsBudgetPage from "@/app/points/budget/page";
let host: HTMLDivElement, root: Root, query: QueryClient;
const flush = () => act(async () => { await new Promise(r => setTimeout(r, 20)); });
async function open() { await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsBudgetPage)))); await flush(); await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "新增预算申请")!.click()); }
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.types.mockResolvedValue([{ typeCode: "HX", name: "平台积分", market: "CN", currency: "CNY", scale: 2 }]);
  mock.requests.mockResolvedValue({ rows: [{ requestNo: "REQ1", action: "TOPUP_U", amountMinor: 100, state: "SUBMITTED", submittedBy: "maker", approvedBy: null, version: 1 }], hasMore: false });
  mock.overview.mockResolvedValue({ uMinor: 10000, bMinor: 200, reservedBMinor: 0, lockedUMinor: 0 });
  host = document.createElement("div"); document.body.append(host); root = createRoot(host); query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
it("uses separate business fields and explicit currency units", async () => {
  await open();
  expect(host.textContent).not.toContain("申请人 / 复核人");
  expect(host.textContent).not.toContain("U · 发行备款");
  expect(host.textContent).toContain("金额（分）");
  expect(host.textContent).toContain("人民币");
});
it("clears amount and evidence when the budget action changes", async () => {
  await open();
  const amount = host.querySelector('[name="amount"]') as HTMLInputElement;
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(amount, "500"); amount.dispatchEvent(new Event("input", { bubbles: true })); });
  (host.querySelector('[name="evidence"]') as HTMLInputElement).value = "OLD";
  const action = host.querySelector('form select') as HTMLSelectElement;
  await act(async () => { action.value = "TOPUP_B"; action.dispatchEvent(new Event("change", { bubbles: true })); });
  expect((host.querySelector('[name="amount"]') as HTMLInputElement).value).toBe("");
  expect((host.querySelector('[name="evidence"]') as HTMLInputElement).value).toBe("");
});
it("rejects whitespace-only identifiers without sending a funding command", async () => {
  await open();
  (host.querySelector('[name="requestNo"]') as HTMLInputElement).value = " ";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.submit).not.toHaveBeenCalled();
  expect(host.querySelector('form [role="alert"]')).not.toBeNull();
});
it("submits the exact minor-unit amount with trimmed references", async () => {
  mock.submit.mockResolvedValue({ requestId: "DONE" });
  await open();
  (host.querySelector('[name="requestNo"]') as HTMLInputElement).value = " REQ2 ";
  (host.querySelector('[name="evidence"]') as HTMLInputElement).value = " BANK2 ";
  (host.querySelector('[name="reason"]') as HTMLInputElement).value = " 补充预算 ";
  const amount = host.querySelector('[name="amount"]') as HTMLInputElement;
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(amount, "123"); amount.dispatchEvent(new Event("input", { bubbles: true })); });
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.submit).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "HX", requestNo: "REQ2", action: "TOPUP_U", amountMinor: 123, evidenceNo: "BANK2", reason: "补充预算" }));
});
