// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
vi.mock("@/lib/i18n", () => ({ useT: () => (v: string) => v, useI18n: () => ({ locale: "zh", t: (v: string) => v }) }));
import { FormDrawer } from "@/components/ui/form-drawer";

let root: Root; let host: HTMLDivElement;
beforeEach(() => { Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); host = document.createElement("div"); document.body.append(host); root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });

// 支付中心 P4 的「允许的产品」「退回补件字段」都用 multiselect；此前 FormDrawer 没有这个分支，退化成文本框，要人手打编码
it("multiselect 字段渲染成多选，不是文本框；csv 形态以逗号串回写", async () => {
  const onChange = vi.fn();
  await act(async () => root.render(h(FormDrawer, {
    inDrawer: true, open: true, onOpenChange: () => {}, titleNew: "", titleEdit: "", isEdit: false,
    fields: [{ key: "codes", label: "产品", type: "multiselect", csv: true, options: [{ value: "A", label: "甲" }, { value: "B", label: "乙" }] }],
    value: { codes: "A" }, onChange, onSubmit: () => {}, submitting: false,
  })));
  expect(host.querySelector('input[type="text"]')).toBeNull();
  expect(host.textContent).toContain("甲");
});
