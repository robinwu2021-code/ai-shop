// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const mock = vi.hoisted(() => ({ types: vi.fn(), accounts: vi.fn(), entries: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { pointsTypes: mock.types, pointsAccounts: mock.accounts, pointsEntries: mock.entries } }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: ({ open, title, children }: { open: boolean; title: string; children: unknown }) => open ? h("aside", {}, h("h2", {}, title), children as never) : null }));
vi.mock("@/components/ui/data-table", () => ({ DataTable: ({ rows = [], columns, rowProps }: { rows: never[]; columns: { header: string; cell: (r: never) => unknown }[]; rowProps?: (r: never) => object }) => h("div", {}, h("div", {}, ...columns.map((c, i) => h("span", { key: i }, c.header))), ...rows.map((r, i) => h("div", { key: i, ...rowProps?.(r) }, ...columns.map((c, j) => h("span", { key: j }, c.cell(r) as never))))) }));
import PointsAccountsPage from "@/app/points/accounts/page";
let host: HTMLDivElement, root: Root, query: QueryClient;
const row = { exists: true, snapshot: { holderType: "USER", holderNo: "U1", typeCode: "HX", frozen: false, buckets: { AVAILABLE: 12, HELD: 3 } } };
const flush = () => act(async () => { await new Promise(r => setTimeout(r, 20)); });
async function render() { await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsAccountsPage)))); await flush(); }
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks(); mock.types.mockResolvedValue([{ typeCode: "HX", name: "平台积分" }]);
  mock.accounts.mockResolvedValue({ rows: [row], hasMore: false }); mock.entries.mockResolvedValue({ rows: [{ entryNo: "E1", voucherDocumentNo: "V1", bucket: "AVAILABLE", deltaPoints: 12, afterPoints: 12, at: 1 }], hasMore: false });
  host = document.createElement("div"); document.body.append(host); root = createRoot(host); query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
it("uses business balance labels and separate ledger identifiers", async () => {
  await render(); expect(host.textContent).toContain("可用积分"); expect(host.textContent).not.toContain("AVAILABLE");
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click()); await flush();
  expect(host.querySelector("aside")!.textContent).toContain("凭证编号"); expect(host.querySelector("aside")!.textContent).toContain("分录编号");
  expect(host.querySelector("aside")!.textContent).not.toContain("凭证 / 业务");
});
it("clears the typed holder number and closes details when holder type changes", async () => {
  await render(); const input = host.querySelector("input") as HTMLInputElement;
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, "U1"); input.dispatchEvent(new Event("input", { bubbles: true })); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  const holder = host.querySelectorAll("select")[1];
  await act(async () => { holder.value = "STORE"; holder.dispatchEvent(new Event("change", { bubbles: true })); }); await flush();
  expect(input.value).toBe(""); expect(host.querySelector("aside")).toBeNull();
  expect(mock.accounts).toHaveBeenLastCalledWith(expect.objectContaining({ holderType: "STORE", after: 0 }));
});
it("does not present a missing account as active or fetch nonexistent ledger entries", async () => {
  mock.accounts.mockResolvedValue({ rows: [{ ...row, exists: false }], hasMore: false });
  await render(); await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click()); await flush();
  expect(host.querySelector("aside")!.textContent).toContain("未建账"); expect(host.querySelector("aside")!.textContent).not.toContain("正常");
  expect(mock.entries).not.toHaveBeenCalled();
});
