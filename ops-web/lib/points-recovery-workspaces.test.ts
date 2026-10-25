// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const mock = vi.hoisted(() => ({ list: vi.fn(), submit: vi.fn(), review: vi.fn(), refunds: vi.fn(), close: vi.fn(), retry: vi.fn(), confirm: vi.fn() }));
vi.mock("@/lib/api", () => ({ IS_MOCK: false, api: { pointsAdjustments: mock.list, submitPointsAdjustment: mock.submit, reviewPointsAdjustment: mock.review, manualOrderPointsRefunds: mock.refunds, closeOrderPointsRefund: mock.close, retryOrderPointsRefund: mock.retry } }));
vi.mock("@/components/ui/confirm-dialog", () => ({ useConfirm: () => ({ confirm: mock.confirm, dialog: null }) }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: ({ open, title, children, onOpenChange }: { open: boolean; title: string; children: unknown; onOpenChange: (open: boolean) => void }) => open ? h("aside", {}, h("h2", {}, title), h("button", { onClick: () => onOpenChange(false) }, "关闭抽屉"), children as never) : null }));
vi.mock("@/components/ui/data-table", () => ({ DataTable: ({ rows = [], columns, rowProps }: { rows: never[]; columns: { header: string; cell: (r: never) => unknown }[]; rowProps?: (r: never) => object }) => h("div", {}, ...rows.map((r, i) => h("div", { key: i, ...rowProps?.(r) }, ...columns.map((c, j) => h("span", { key: j }, c.cell(r) as never))))) }));
import { PointsAdjustments } from "@/app/points/adjustments";
import { ManualPointsRefundWorkspace } from "@/app/points/manual-refunds";
let host: HTMLDivElement, root: Root, query: QueryClient;
const adjustment = { id: 1, adjustmentNo: "A1", typeCode: "HX", userNo: "U1", direction: "INCREASE", points: 10, reason: "original reason", state: "SUBMITTED", submittedBy: "OP1", approvedBy: null, version: 7 };
const flush = () => act(async () => { await new Promise(r => setTimeout(r, 30)); });
async function render(child: ReturnType<typeof h>) { await act(async () => root.render(h(QueryClientProvider, { client: query }, child))); await flush(); }
async function click(text: string) { const button = [...host.querySelectorAll("button")].find(b => b.textContent === text)!; expect(button).toBeTruthy(); await act(async () => button.click()); await flush(); }
async function input(selector: string, value: string) { const el = host.querySelector(selector) as HTMLInputElement; await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(el, value); el.dispatchEvent(new Event("input", { bubbles: true })); }); }
async function row() { await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click()); await flush(); }
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.resetAllMocks(); mock.list.mockResolvedValue({ rows: [adjustment], next: 0, hasMore: false }); mock.submit.mockResolvedValue({}); mock.review.mockResolvedValue({});
  mock.refunds.mockResolvedValue({ rows: [{ afterSaleNo: "AS1", subOrderNo: "O1-1", createdAt: 1 }], hasMore: false });
  mock.close.mockResolvedValue({ afterSaleNo: "AS1", status: "CLOSED" }); mock.retry.mockResolvedValue({ afterSaleNo: "AS2", status: "PENDING" });
  mock.confirm.mockImplementation(async (opts: { action: () => Promise<unknown> }) => { await opts.action(); return true; });
  host = document.createElement("div"); document.body.append(host); root = createRoot(host); query = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
it("keeps adjustment requests read-only without write permissions", async () => {
  await render(h(PointsAdjustments, { typeCode: "HX", typeName: "平台积分", canMake: false, canCheck: false }));
  expect(host.textContent).toContain("平台积分"); expect(host.textContent).not.toContain("申请调账"); await row();
  expect(host.textContent).toContain("original reason"); expect(host.querySelector("input")).toBeNull(); expect(mock.review).not.toHaveBeenCalled();
});
it("reviews the selected version and invalidates balance and budget queries", async () => {
  const invalidate = vi.spyOn(query, "invalidateQueries");
  await render(h(PointsAdjustments, { typeCode: "HX", canMake: false, canCheck: true })); await row();
  expect([...host.querySelectorAll("button")].find(b => b.textContent === "批准")!.disabled).toBe(true);
  await input("aside input", "  checked  "); await click("批准");
  expect(mock.review).toHaveBeenCalledWith(expect.objectContaining({ adjustmentNo: "A1", typeCode: "HX", expectedVersion: 7, approve: true, reason: "checked", requestId: expect.any(String) }));
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ["points-accounts"] }); expect(invalidate).toHaveBeenCalledWith({ queryKey: ["points-overview"] });
});
it("retains form data and intent ID after a lost submission response", async () => {
  mock.submit.mockRejectedValueOnce(new Error("lost response"));
  await render(h(PointsAdjustments, { typeCode: "HX", canMake: true, canCheck: false })); await click("申请调账");
  await input('[name="no"]', "A2"); await input('[name="user"]', " U2 "); await input('[name="points"]', "20"); await input("aside input:not([name])", "manual correction");
  const submit = async () => { await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); await flush(); };
  await submit(); expect(host.textContent).toContain("lost response"); expect((host.querySelector('[name="no"]') as HTMLInputElement).value).toBe("A2");
  await submit(); expect(mock.submit).toHaveBeenCalledTimes(2); expect(mock.submit.mock.calls[0][0]).toEqual(mock.submit.mock.calls[1][0]);
  expect(mock.submit.mock.calls[0][0]).toMatchObject({ userNo: "U2", points: 20, typeCode: "HX" });
});
it("rejects fractional points and whitespace identifiers before mutation", async () => {
  await render(h(PointsAdjustments, { typeCode: "HX", canMake: true, canCheck: false })); await click("申请调账");
  await input('[name="no"]', "  "); await input('[name="user"]', "U2"); await input('[name="points"]', "1.5"); await input("aside input:not([name])", "reason");
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.submit).not.toHaveBeenCalled(); expect(host.textContent).toContain("正整数");
});
async function searchStore(store: string) { await input("input", store); await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); await flush(); }
it("queries only after store selection and clears stale details on store change", async () => {
  await render(h(ManualPointsRefundWorkspace, { canEdit: false })); expect(mock.refunds).not.toHaveBeenCalled();
  await searchStore("S1"); await row(); expect(host.querySelector("aside")!.textContent).toContain("AS1"); expect(host.querySelector("aside input")).toBeNull();
  await searchStore("S2"); expect(host.querySelector("aside")).toBeNull(); expect(mock.refunds).toHaveBeenLastCalledWith("S2", "", 20);
});
it("canceling refund confirmation performs no financial action", async () => {
  mock.confirm.mockResolvedValue(false);
  await render(h(ManualPointsRefundWorkspace, { canEdit: true })); await searchStore("S1"); await row(); await input("aside input", "checked"); await click("新建退款重试");
  expect(mock.confirm).toHaveBeenCalledWith(expect.objectContaining({ danger: true, action: expect.any(Function) })); expect(mock.retry).not.toHaveBeenCalled(); expect(mock.close).not.toHaveBeenCalled();
});
it("confirms a refund retry using the selected case and shows the new case result", async () => {
  await render(h(ManualPointsRefundWorkspace, { canEdit: true })); await searchStore("S1"); await row(); await input("aside input", "  checked  "); await click("新建退款重试");
  expect(mock.retry).toHaveBeenCalledWith("AS1", "checked"); expect(mock.close).not.toHaveBeenCalled(); expect(host.textContent).toContain("AS2"); expect(host.querySelector("aside")).toBeNull();
});

it("preserves an unconfirmed adjustment intent when its drawer is reopened", async () => {
  mock.submit.mockRejectedValueOnce(new Error("lost response"));
  await render(h(PointsAdjustments, { typeCode: "HX", canMake: true, canCheck: false }));
  async function fillAndSubmit() {
    await click("申请调账"); await input('[name="no"]', "A2"); await input('[name="user"]', "U2"); await input('[name="points"]', "20"); await input("aside input:not([name])", "correction");
    await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); await flush();
  }
  await fillAndSubmit(); await click("关闭抽屉"); await fillAndSubmit();
  expect(mock.submit).toHaveBeenCalledTimes(2); expect(mock.submit.mock.calls[0][0].requestId).toBe(mock.submit.mock.calls[1][0].requestId);
});
