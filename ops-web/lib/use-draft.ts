"use client";

// 抽屉表单的草稿钩子（TDD-支付中心运营页面 §2.4）。
//
// 行为：
// - 打开时若有草稿：用草稿覆盖初始值（敏感字段始终取初始值），给出保存时间；
//   服务端版本与草稿的基线版本不一致时标 conflict，**不自动丢弃**，由界面让人决定。
// - 每次改动 delayMs 防抖落盘；卸载（关抽屉、切菜单）与页面隐藏（关标签、切走、刷新）时立即落盘。
//   所以离开页面不再需要拦截确认框 —— 内容已经在本地了。
// - clear()：提交成功后调用，清存储并取消待落盘的写入。
// - discard()：「放弃草稿」，清存储并回到初始值。
import * as React from "react";
import { draftStore, type DraftRef, type DraftStore } from "./draft";

export interface UseDraftOptions<V extends Record<string, unknown>> {
  /** null = 不启用草稿（只做内存表单），如操作人未知。 */
  ref: DraftRef | null;
  initial: V;
  sensitiveKeys?: readonly string[];
  baseVersion?: string | number | null;
  store?: DraftStore;
  delayMs?: number;
}

export interface UseDraftResult<V> {
  values: V;
  setValues: (v: V) => void;
  /** 有尚未提交的改动（含恢复出来的草稿）。 */
  dirty: boolean;
  restored: { savedAt: number } | null;
  conflict: boolean;
  persistFailed: boolean;
  discard: () => void;
  clear: () => void;
  flush: () => void;
}

export function useDraft<V extends Record<string, unknown>>({
  ref, initial, sensitiveKeys = [], baseVersion, store = draftStore, delayMs = 500,
}: UseDraftOptions<V>): UseDraftResult<V> {
  const refKey = ref ? JSON.stringify([ref.operator, ref.page, ref.action, ref.id ?? null]) : null;

  // 首次渲染就同步读草稿：避免先渲染空表单再闪成草稿内容。
  const [state, setState] = React.useState(() => load());
  function load() {
    const rec = ref ? store.read<Partial<V>>(ref) : null;
    if (!rec) return { values: initial, dirty: false, restored: null as { savedAt: number } | null, conflict: false };
    const values = { ...initial, ...rec.values } as V;
    for (const k of sensitiveKeys) (values as Record<string, unknown>)[k] = initial[k];
    const conflict = baseVersion != null && rec.baseVersion != null && rec.baseVersion !== baseVersion;
    return { values, dirty: true, restored: { savedAt: rec.savedAt }, conflict };
  }
  const [persistFailed, setPersistFailed] = React.useState(false);

  // 换了对象（同一抽屉切到另一行）时重新加载。
  const lastKey = React.useRef(refKey);
  React.useEffect(() => {
    if (lastKey.current === refKey) return;
    lastKey.current = refKey;
    setState(load());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refKey]);

  // 待落盘的值放 ref：卸载与 pagehide 时要拿到最新值，而不是闭包里的旧值。
  const pending = React.useRef<V | null>(null);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const live = React.useRef({ ref, sensitiveKeys, baseVersion, store });
  live.current = { ref, sensitiveKeys, baseVersion, store };

  const flush = React.useCallback(() => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null; }
    const v = pending.current;
    pending.current = null;
    const { ref: r, sensitiveKeys: sk, baseVersion: bv, store: st } = live.current;
    if (!v || !r) return;
    const ok = st.write(r, v, { sensitiveKeys: sk, baseVersion: bv });
    setPersistFailed(!ok);
  }, []);

  const setValues = React.useCallback((v: V) => {
    setState((s) => ({ ...s, values: v, dirty: true }));
    if (!live.current.ref) return;
    pending.current = v;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(flush, delayMs);
  }, [delayMs, flush]);

  const cancelPending = () => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null; }
    pending.current = null;
  };

  const clear = React.useCallback(() => {
    cancelPending();
    if (live.current.ref) live.current.store.clear(live.current.ref);
    setState((s) => ({ ...s, dirty: false, restored: null, conflict: false }));
  }, []);

  const discard = React.useCallback(() => {
    cancelPending();
    if (live.current.ref) live.current.store.clear(live.current.ref);
    setState({ values: initial, dirty: false, restored: null, conflict: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial]);

  React.useEffect(() => {
    const onHide = () => flush();
    const onVisibility = () => { if (document.visibilityState === "hidden") flush(); };
    window.addEventListener("pagehide", onHide);
    window.addEventListener("beforeunload", onHide);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", onHide);
      window.removeEventListener("beforeunload", onHide);
      document.removeEventListener("visibilitychange", onVisibility);
      flush();
    };
  }, [flush]);

  return { ...state, setValues, persistFailed, discard, clear, flush };
}
