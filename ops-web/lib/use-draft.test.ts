// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { createDraftStore, type DraftRef } from "./draft";
import { useDraft, type UseDraftResult } from "./use-draft";

type V = { remark: string; bankAccount: string };
const ref: DraftRef = { operator: "alice", page: "pay-orders", action: "edit", id: "P1" };
let root: Root;
let host: HTMLDivElement;
let api: UseDraftResult<V>;

function Probe(props: { store: ReturnType<typeof createDraftStore>; baseVersion?: number; draftRef?: DraftRef | null }) {
  api = useDraft<V>({
    ref: props.draftRef === undefined ? ref : props.draftRef,
    initial: { remark: "", bankAccount: "" },
    sensitiveKeys: ["bankAccount"],
    baseVersion: props.baseVersion,
    store: props.store,
    delayMs: 500,
  });
  return null;
}
async function mount(p: Parameters<typeof Probe>[0]) {
  await act(async () => root.render(h(Probe, p)));
}

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.useFakeTimers();
  localStorage.clear();
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.useRealTimers(); });

it("编辑后防抖落盘，敏感字段不落盘", async () => {
  const store = createDraftStore(() => localStorage);
  await mount({ store });
  await act(async () => api.setValues({ remark: "补查", bankAccount: "6222" }));
  expect(api.dirty).toBe(true);
  expect(store.read(ref)).toBeNull();
  await act(async () => { vi.advanceTimersByTime(500); });
  expect(store.read(ref)?.values).toEqual({ remark: "补查" });
});

it("重新打开同一操作时恢复草稿，并给出保存时间", async () => {
  const store = createDraftStore(() => localStorage);
  store.write(ref, { remark: "上次没写完" }, { now: Date.now() - 60_000 });
  await mount({ store });
  expect(api.values).toEqual({ remark: "上次没写完", bankAccount: "" });
  expect(api.restored?.savedAt).toBeLessThan(Date.now());
  expect(api.conflict).toBe(false);
});

it("服务端版本变了：标记冲突，不丢草稿", async () => {
  const store = createDraftStore(() => localStorage);
  store.write(ref, { remark: "旧版改动" }, { baseVersion: 1 });
  await mount({ store, baseVersion: 2 });
  expect(api.conflict).toBe(true);
  expect(api.values.remark).toBe("旧版改动");
});

it("提交成功后 clear 清掉草稿，之后的防抖不会再写回", async () => {
  const store = createDraftStore(() => localStorage);
  await mount({ store });
  await act(async () => api.setValues({ remark: "x", bankAccount: "" }));
  await act(async () => api.clear());
  await act(async () => { vi.advanceTimersByTime(1000); });
  expect(store.read(ref)).toBeNull();
  expect(api.dirty).toBe(false);
});

it("放弃草稿：清存储并回到初始值", async () => {
  const store = createDraftStore(() => localStorage);
  store.write(ref, { remark: "不要了" });
  await mount({ store });
  await act(async () => api.discard());
  expect(store.read(ref)).toBeNull();
  expect(api.values).toEqual({ remark: "", bankAccount: "" });
  expect(api.restored).toBeNull();
});

it("卸载（关抽屉、切页面）时立即落盘未到防抖的改动", async () => {
  const store = createDraftStore(() => localStorage);
  await mount({ store });
  await act(async () => api.setValues({ remark: "马上离开", bankAccount: "" }));
  await act(async () => root.unmount());
  expect(store.read(ref)?.values).toEqual({ remark: "马上离开" });
  root = createRoot(host);
});

it("页面隐藏（关标签、切走）时立即落盘", async () => {
  const store = createDraftStore(() => localStorage);
  await mount({ store });
  await act(async () => api.setValues({ remark: "切走了", bankAccount: "" }));
  await act(async () => { window.dispatchEvent(new Event("pagehide")); });
  expect(store.read(ref)?.values).toEqual({ remark: "切走了" });
});

it("存储写入失败时标记 persistFailed", async () => {
  const store = createDraftStore(() => null);
  await mount({ store });
  await act(async () => api.setValues({ remark: "x", bankAccount: "" }));
  await act(async () => { vi.advanceTimersByTime(500); });
  expect(api.persistFailed).toBe(true);
});

it("ref 为 null 时只做内存表单，不读写存储", async () => {
  const store = createDraftStore(() => localStorage);
  await mount({ store, draftRef: null });
  await act(async () => api.setValues({ remark: "x", bankAccount: "" }));
  await act(async () => { vi.advanceTimersByTime(500); });
  expect(localStorage.length).toBe(0);
  expect(api.values.remark).toBe("x");
});
