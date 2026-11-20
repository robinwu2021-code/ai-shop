// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, createElement as h, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { Drawer } from "@/components/ui/drawer";
import { RowActionButtons, useActionHost, validateActions, type ActionSpec } from "@/components/ui/action-host";
import { ListPage } from "@/components/ui/list-page";

type Row = { id: string; status: "OPEN" | "CLOSED" };
const rows: Row[] = [{ id: "1", status: "OPEN" }, { id: "2", status: "CLOSED" }];

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
  window.history.replaceState(null, "", "/payment-center/orders?tab=list");
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); document.body.innerHTML = ""; });

const run = vi.fn(async () => undefined);
function specs(): ActionSpec<Row>[] {
  return [
    { key: "view", label: "查看", kind: "drawer", title: (r) => `订单 ${r?.id}`, render: (ctx) => h("div", null,
      h("p", null, `detail-${ctx.row?.id}`),
      h("button", { type: "button", onClick: () => ctx.switchTo("edit") }, "去编辑"),
      h("button", { type: "button", onClick: () => ctx.open("close") }, "去关闭")) },
    { key: "edit", label: "编辑", kind: "drawer", perm: "pay:order:manage", title: () => "编辑", render: (ctx) => h("div", null,
      h("p", null, "edit-body"),
      ctx.back ? h("button", { type: "button", onClick: ctx.back }, "返回详情") : null) },
    { key: "recover", label: "补查", kind: "confirm", perm: "pay:order:manage", confirm: () => ({ title: "确认补查" }), run },
    { key: "close", label: "关闭", kind: "dialog", danger: true, visible: (r) => r?.status === "OPEN", confirm: () => ({ title: "关闭订单" }), run },
    { key: "export-one", label: "导出", kind: "confirm", confirm: () => ({ title: "导出" }), run },
  ];
}

let hostApi: ReturnType<typeof useActionHost<Row>>;
function Harness({ allow, children }: { allow: (p: string) => boolean; children?: ReactNode }) {
  hostApi = useActionHost<Row>({ actions: specs(), allow, getRowId: (r) => r.id, syncUrl: { resolve: (id) => rows.find((r) => r.id === id) ?? null } });
  return h("div", null, children, hostApi.node);
}
const allowAll = () => true;
const drawers = () => [...document.querySelectorAll('[role="dialog"]')].filter((d) => d.getAttribute("data-surface") === "drawer");
const dialogs = () => [...document.querySelectorAll('[role="alertdialog"], [role="dialog"]')].filter((d) => d.getAttribute("data-surface") !== "drawer");
const click = async (el: Element | null | undefined) => act(async () => { (el as HTMLElement).click(); });
const byText = (text: string) => [...document.querySelectorAll("button")].find((b) => b.textContent === text);

describe("validateActions", () => {
  it("抽屉必须有 render；弹框与确认必须有 run；kind 只能三种", () => {
    expect(() => validateActions([{ key: "a", label: "A", kind: "drawer" } as ActionSpec<Row>])).toThrow(/render/);
    expect(() => validateActions([{ key: "a", label: "A", kind: "dialog", confirm: () => ({ title: "x" }) } as ActionSpec<Row>])).toThrow(/run/);
    expect(() => validateActions([{ key: "a", label: "A", kind: "sheet" } as unknown as ActionSpec<Row>])).toThrow(/kind/);
    expect(() => validateActions([...specs(), specs()[0]])).toThrow(/重复/);
    expect(() => validateActions(specs())).not.toThrow();
  });
});

describe("useActionHost", () => {
  it("按权限码与 visible 过滤可用操作", async () => {
    await act(async () => root.render(h(Harness, { allow: (p) => p !== "pay:order:manage" })));
    expect(hostApi.available(rows[0]).map((a) => a.key)).toEqual(["view", "close", "export-one"]);
    expect(hostApi.available(rows[1]).map((a) => a.key)).toEqual(["view", "export-one"]);
  });

  it("同一时刻只有一个抽屉：再开一个是替换，并可返回", async () => {
    await act(async () => root.render(h(Harness, { allow: allowAll })));
    await act(async () => hostApi.open("view", rows[0]));
    expect(drawers()).toHaveLength(1);
    expect(document.body.textContent).toContain("detail-1");
    await click(byText("去编辑"));
    expect(drawers()).toHaveLength(1);
    expect(document.body.textContent).toContain("edit-body");
    expect(document.body.textContent).not.toContain("detail-1");
    await click(byText("返回详情"));
    expect(document.body.textContent).toContain("detail-1");
  });

  it("弹框可以叠在抽屉上，抽屉保留", async () => {
    await act(async () => root.render(h(Harness, { allow: allowAll })));
    await act(async () => hostApi.open("view", rows[0]));
    await click(byText("去关闭"));
    expect(drawers()).toHaveLength(1);
    expect(dialogs().length).toBeGreaterThanOrEqual(1);
    expect(document.body.textContent).toContain("关闭订单");
  });

  it("打开的抽屉同步到地址栏，关闭后移除，其他参数保留", async () => {
    await act(async () => root.render(h(Harness, { allow: allowAll })));
    await act(async () => hostApi.open("view", rows[1]));
    expect(window.location.search).toBe("?tab=list&action=view&id=2");
    await act(async () => hostApi.close());
    expect(window.location.search).toBe("?tab=list");
  });

  it("带着 ?action=&id= 进入页面时还原抽屉", async () => {
    window.history.replaceState(null, "", "/payment-center/orders?action=view&id=2");
    await act(async () => root.render(h(Harness, { allow: allowAll })));
    await act(async () => { await Promise.resolve(); });
    expect(document.body.textContent).toContain("detail-2");
  });

  it("操作的可见性还在加载（pending）时，等它就绪再还原，不清地址栏", async () => {
    window.history.replaceState(null, "", "/payment-center/merchants?action=create");
    let ready = false;
    const late = (): ActionSpec<Row>[] => [{ key: "create", label: "新建", kind: "drawer", pending: () => !ready, visible: () => ready,
      render: () => h("p", null, "create-body") }];
    function Late() {
      const api = useActionHost<Row>({ actions: late(), allow: allowAll, getRowId: (r) => r.id, syncUrl: { resolve: () => null } });
      return h("div", null, api.node);
    }
    await act(async () => root.render(h(Late)));
    await act(async () => { await Promise.resolve(); });
    expect(window.location.search).toBe("?action=create");
    expect(drawers()).toHaveLength(0);
    ready = true;
    await act(async () => root.render(h(Late)));
    await act(async () => { await Promise.resolve(); });
    expect(document.body.textContent).toContain("create-body");
  });

  it("地址栏里的操作无权时不还原", async () => {
    window.history.replaceState(null, "", "/payment-center/orders?action=edit&id=1");
    await act(async () => root.render(h(Harness, { allow: (p) => p !== "pay:order:manage" })));
    await act(async () => { await Promise.resolve(); });
    expect(drawers()).toHaveLength(0);
  });
});

describe("RowActionButtons", () => {
  it("最多 3 个直接按钮，其余与危险操作进「更多」", async () => {
    function Row1() {
      const api = useActionHost<Row>({ actions: specs(), allow: allowAll, getRowId: (r) => r.id });
      return h("div", null, h(RowActionButtons<Row>, { row: rows[0], host: api }), api.node);
    }
    await act(async () => root.render(h(Row1)));
    const direct = [...host.querySelectorAll("button[data-row-action]")].map((b) => b.textContent);
    expect(direct).toEqual(["查看", "编辑", "补查"]);
    expect(host.querySelector('button[aria-label]')).not.toBeNull();
  });
});

describe("Drawer 嵌套检测", () => {
  it("在已打开的抽屉里再渲染抽屉时报错", async () => {
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    await act(async () => root.render(h(Drawer, { open: true, onOpenChange: () => {}, title: "外层" },
      h(Drawer, { open: true, onOpenChange: () => {}, title: "内层" }, "x"))));
    expect(err.mock.calls.some((c) => String(c[0]).includes("[Drawer] nested"))).toBe(true);
    err.mockRestore();
  });
});

describe("ListPage", () => {
  it("渲染标题、表格行、行操作与页面级操作", async () => {
    const pageActions: ActionSpec<Row>[] = [
      { key: "create", label: "新建", kind: "drawer", title: () => "新建", render: () => h("p", null, "create-body") },
    ];
    await act(async () => root.render(h(ListPage<Row>, {
      header: h("h1", null, "支付订单"),
      query: { data: { records: rows, total: 2 } },
      page: 1, size: 20, onPage: () => {}, onSize: () => {},
      columns: [{ header: "编号", cell: (r: Row) => r.id }],
      rowKey: (r: Row) => r.id,
      actions: specs(),
      pageActions,
      allow: allowAll,
    })));
    expect(host.textContent).toContain("支付订单");
    expect(host.querySelectorAll("tbody tr")).toHaveLength(2);
    // 页面级操作只在工具栏，不进每一行（P3a 实机发现：行里出现了「新建」）
    for (const tr of host.querySelectorAll("tbody tr")) {
      expect([...tr.querySelectorAll("button")].map((b) => b.textContent)).not.toContain("新建");
    }
    await click(byText("新建"));
    expect(document.body.textContent).toContain("create-body");
  });

  it("页面级操作只出现在工具栏，不进表格行", async () => {
    await act(async () => root.render(h(ListPage<Row>, {
      header: h("h1", null, "x"),
      query: { data: { records: rows, total: 2 } },
      page: 1, size: 20, onPage: () => {}, onSize: () => {},
      columns: [{ header: "编号", cell: (r: Row) => r.id }],
      rowKey: (r: Row) => r.id,
      actions: [specs()[0]],
      pageActions: [{ key: "create", label: "新建", kind: "drawer", title: () => "新建", render: () => h("p", null, "c") }],
      allow: allowAll,
    })));
    for (const tr of host.querySelectorAll("tbody tr")) {
      expect([...tr.querySelectorAll("button[data-row-action]")].map((b) => b.getAttribute("data-row-action"))).toEqual(["view"]);
    }
    expect([...host.querySelectorAll("button")].filter((b) => b.textContent === "新建")).toHaveLength(1);
  });
});
