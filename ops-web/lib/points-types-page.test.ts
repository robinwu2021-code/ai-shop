// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mock = vi.hoisted(() => ({ types: vi.fn(), fallback: vi.fn(), terms: vi.fn(), create: vi.fn(), transition: vi.fn(), rename: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  pointsTypes: mock.types, pointsFallback: mock.fallback, pointsTerms: mock.terms, createPointsType: mock.create, transitionPointsType: mock.transition, renamePointsType: mock.rename,
} }));
vi.mock("@/components/ui/data-table", async () => {
  const { createElement: h } = await import("react");
  return { DataTable: ({ rows, columns, rowProps }: { rowProps?: (row: never) => Record<string, unknown>; rows?: Array<Record<string, unknown>>; columns: Array<{ header?: string; cell: (row: never) => unknown }> }) =>
    h("div", { "data-testid": "points-type-list" }, h("div", {}, ...columns.map((column, i) => h("span", { key: i }, column.header))), ...(rows ?? []).map((row, index) =>
      h("div", { key: index, ...rowProps?.(row as never) }, ...columns.map((column, col) => h("span", { key: col }, column.cell(row as never)))))) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  return { Drawer: ({ open, title, desc, children, footer }: { open: boolean; title: string; desc?: string; children: unknown; footer?: unknown }) =>
    open ? h("aside", {}, h("h2", {}, title), desc, children as never, footer as never) : null,
    DrawerSection: ({ children }: { children: unknown }) => h("section", {}, children as never) };
});

import { PointsTypesTab } from "@/app/points/types-tab";

let root: Root;
let host: HTMLDivElement;
let query: QueryClient;

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.types.mockResolvedValue([]);
  mock.fallback.mockResolvedValue(null);
  mock.terms.mockResolvedValue(null);
  mock.create.mockResolvedValue({ requestId: "REQ1", action: "CREATE", typeCode: "CODE1", at: 1 });
  host = document.createElement("div");
  document.body.append(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  root = createRoot(host);
});

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  query?.clear();
  host?.remove();
});

it("keeps the settings page focused on its own list and rule details", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: false }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });

  expect(host.querySelector('[data-testid="points-type-list"]')).not.toBeNull();
  expect(host.textContent).toContain("积分设置");
  expect(host.textContent).not.toContain("门店协议查询");
  expect(host.textContent).not.toContain("订单积分按门店接线");
  expect(host.textContent).not.toContain("原订单积分核验");
  expect(host.textContent).not.toContain("积分退款待处理");
});

it("creates a named points type with the selected currency and conversion rate", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  const create = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes("新建积分类型"));
  expect(create).toBeDefined();
  await act(async () => create?.click());

  expect(host.textContent).toContain("新增积分类型");
  expect(host.textContent).not.toContain("草稿");
  expect(host.textContent).not.toContain("1 积分 = 1 分");
  expect(host.querySelector('[name="currency"]')).not.toBeNull();
  expect(host.querySelector('[name="unit"]')).not.toBeNull();
  expect(host.querySelector('[name="name"]')).not.toBeNull();
  expect(host.querySelector('[name="code"]')).not.toBeNull();
  expect(host.querySelector('[name="remark"]')).not.toBeNull();
  expect(host.querySelector('[name="market"]')).toBeNull();
  expect(host.querySelector('[name="days"]')).toBeNull();
  expect(host.querySelector('[name="points"]')).not.toBeNull();
  expect(host.querySelector('[name="minor"]')).toBeNull();

  (host.querySelector('[name="name"]') as HTMLInputElement).value = "会员积分";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "CODE1";
  (host.querySelector('[name="remark"]') as HTMLInputElement).value = "首期积分";
  await act(async () => host.querySelector("form#points-type-create")?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.create).toHaveBeenCalledWith(expect.objectContaining({
    command: { typeCode: "CODE1", name: "会员积分", currency: "CNY", rate: { pointsUnit: 1, minorUnit: 1 } },
    reason: "首期积分",
  }));
});

it("shows a clear message when the backend rejects a duplicate code or name", async () => {
  mock.create.mockRejectedValue(new Error("积分代码或名称已存在，请修改后重试。"));
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  const create = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes("新建积分类型"));
  await act(async () => create?.click());
  (host.querySelector('[name="name"]') as HTMLInputElement).value = "积分名称";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "DUPLICATE";
  (host.querySelector('[name="remark"]') as HTMLInputElement).value = "备注";
  await act(async () => host.querySelector("form#points-type-create")?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(await host.textContent).toContain("积分代码或名称已存在，请修改后重试。");
});

it("sends non-default currency and rate through the page without dropping the settings", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent?.includes("新建积分类型"))!.click());
  const currency = host.querySelector('[name="currency"]') as HTMLSelectElement;
  await act(async () => { currency.value = "KWD"; currency.dispatchEvent(new Event("change", { bubbles: true })); });
  const unit = host.querySelector('[name="unit"]') as HTMLSelectElement;
  await act(async () => { unit.value = "dinar"; unit.dispatchEvent(new Event("change", { bubbles: true })); });
  (host.querySelector('[name="name"]') as HTMLInputElement).value = "第纳尔积分";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "KWD_POINTS";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.create).toHaveBeenCalledWith(expect.objectContaining({
    command: { typeCode: "KWD_POINTS", name: "第纳尔积分", currency: "KWD", rate: { pointsUnit: 1000, minorUnit: 1000 } },
    reason: "创建积分类型",
  }));
});

it("shows the default and offers pause without affecting redemption", async () => {
  mock.types.mockResolvedValue([{ typeCode: "HX_TOKEN", name: "平台积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", earningPaused: false, orderDefault: true }]);
  mock.transition.mockResolvedValue({ requestId: "PAUSE1" });
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.textContent).toContain("订单默认");
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  await act(async () => (host.querySelector('button[aria-label="编辑状态"]') as HTMLButtonElement).click());
  const actions = host.querySelector('[name="action"]') as HTMLSelectElement;
  expect([...actions.options].map((o) => o.value)).toContain("PAUSE_EARNING");
  expect([...actions.options].map((o) => o.value)).not.toContain("SET_DEFAULT");
  actions.value = "PAUSE_EARNING";
  expect(host.querySelector('[name="reason"]')).toBeNull();
  await act(async () => host.querySelector("form#points-type-state")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.transition).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "HX_TOKEN", action: "PAUSE_EARNING" }));
  await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent === "使用说明")!.click());
  expect(host.textContent).toContain("已有积分仍可抵扣");
});

it("offers resume and explicit default selection for a paused independent type", async () => {
  mock.types.mockResolvedValue([{ typeCode: "OTHER", name: "其他积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", earningPaused: true, orderDefault: false }]);
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.textContent).toContain("赠送已暂停");
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  await act(async () => (host.querySelector('button[aria-label="编辑状态"]') as HTMLButtonElement).click());
  const actions = host.querySelector('[name="action"]') as HTMLSelectElement;
  expect([...actions.options].map((o) => o.value)).toContain("RESUME_EARNING");
  expect([...actions.options].map((o) => o.value)).not.toContain("SET_DEFAULT");
  expect(host.querySelector('button[aria-label="编辑订单默认"]')).not.toBeNull();
});

it("edits an active points name in its drawer and refreshes the list", async () => {
  const original = { typeCode: "HX_TOKEN", name: "平台积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", orderDefault: true };
  query.setQueryData(["points-earning-context"], { types: [original] });
  mock.types.mockResolvedValue([original]);
  mock.rename.mockImplementation(async () => { mock.types.mockResolvedValue([{ ...original, name: "虹选积分" }]); return { requestId: "RENAME1" }; });
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  expect(host.querySelector('form#points-type-rename')).toBeNull();
  await act(async () => (host.querySelector('button[aria-label="编辑积分名称"]') as HTMLButtonElement).click());
  const name = host.querySelector('form#points-type-rename [name="name"]') as HTMLInputElement;
  expect(name).not.toBeNull();
  expect(name.value).toBe("平台积分");
  name.value = "虹选积分";
  expect(host.querySelector('form#points-type-rename [name="remark"]')).toBeNull();
  await act(async () => host.querySelector('form#points-type-rename')!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(mock.rename).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "HX_TOKEN", expectedName: "平台积分", name: "虹选积分" }));
  expect(mock.rename.mock.calls[0][0]).not.toHaveProperty("reason");
  expect(host.querySelector('[data-testid="points-type-list"]')!.textContent).toContain("虹选积分");
  expect(query.getQueryState(["points-earning-context"])?.isInvalidated).toBe(true);
});

it("blocks duplicate names in the drawer before submitting", async () => {
  const base = { market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE" };
  mock.types.mockResolvedValue([{ ...base, typeCode: "HX_TOKEN", name: "平台积分" }, { ...base, typeCode: "OTHER", name: "已有积分" }]);
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  await act(async () => (host.querySelector('button[aria-label="编辑积分名称"]') as HTMLButtonElement).click());
  (host.querySelector('form#points-type-rename [name="name"]') as HTMLInputElement).value = "已有积分";

  await act(async () => host.querySelector('form#points-type-rename')!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(host.textContent).toContain("积分名称已存在，请修改后重试。");
  expect(mock.rename).not.toHaveBeenCalled();
});

it("cancels inline name editing without submitting", async () => {
  mock.types.mockResolvedValue([{ typeCode: "HX_TOKEN", name: "平台积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", orderDefault: true }]);
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  await act(async () => (host.querySelector('button[aria-label="编辑积分名称"]') as HTMLButtonElement).click());
  await act(async () => (host.querySelector('form#points-type-rename button[type="button"]') as HTMLButtonElement).click());
  expect(host.querySelector('form#points-type-rename')).toBeNull();
  expect(mock.rename).not.toHaveBeenCalled();
});

it("edits the default field independently without a remark", async () => {
  mock.types.mockResolvedValue([{ typeCode: "OTHER", name: "其他积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", orderDefault: false }]);
  mock.transition.mockResolvedValue({ requestId: "D1" });
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  expect(host.querySelector('form')).toBeNull();
  await act(async () => (host.querySelector('button[aria-label="编辑订单默认"]') as HTMLButtonElement).click());
  expect(host.querySelector('[name="reason"]')).toBeNull();
  await act(async () => host.querySelector('form#points-type-default')!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.transition).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "OTHER", action: "SET_DEFAULT" }));
  expect(mock.transition.mock.calls[0][0]).not.toHaveProperty("reason");
});

it("filters the list by name or code and keeps help collapsed initially", async () => {
  const base = { market: "CN", currency: "CNY", rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, status: "ACTIVE" };
  mock.types.mockResolvedValue([{ ...base, typeCode: "HX_TOKEN", name: "平台积分" }, { ...base, typeCode: "MEMBER", name: "会员积分" }]);
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: false }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.textContent).not.toContain("多个积分类型可同时生效");
  const search = host.querySelector('input[aria-label="搜索积分名称或代码"]') as HTMLInputElement;
  expect(search).not.toBeNull();
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(search, "member"); search.dispatchEvent(new Event("input", { bubbles: true })); });
  const list = host.querySelector('[data-testid="points-type-list"]')!;
  expect(list.textContent).toContain("会员积分");
  expect(list.textContent).not.toContain("平台积分");
});

it("keeps a failed rename and its error within the editable field for retry", async () => {
  mock.types.mockResolvedValue([{ typeCode: "HX_TOKEN", name: "平台积分", currency: "CNY", rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, status: "ACTIVE", orderDefault: true }]);
  mock.rename.mockRejectedValue(new Error("暂时无法保存"));
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  await act(async () => (host.querySelector('button[aria-label="编辑积分名称"]') as HTMLButtonElement).click());
  (host.querySelector('[name="name"]') as HTMLInputElement).value = "虹选积分";
  await act(async () => host.querySelector('#points-type-rename')!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.querySelector('#points-type-rename')!.textContent).toContain("暂时无法保存");
  expect((host.querySelector('[name="name"]') as HTMLInputElement).value).toBe("虹选积分");
  await act(async () => host.querySelector('#points-type-rename')!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.rename).toHaveBeenCalledTimes(2);
});
