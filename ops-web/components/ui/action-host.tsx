"use client";

// 操作的声明与承载（TDD-支付中心运营页面 §2.2 / §2.3）。
//
// 页面只声明「有哪些操作、谁能用、用什么载体」，承载由这里统一做：
// - 载体只有三种：drawer（查看、多字段编辑）、dialog（填理由的短弹框）、confirm（纯确认 / 强确认）。
// - **抽屉只有一个槽位**：再开一个抽屉是替换，并记住上一个以便「返回」；绝不叠第二层。
// - 弹框可以叠在抽屉上（确认、填理由），由 `useConfirm` 承载。
// - 打开的抽屉同步到地址栏 `?action=<key>&id=<编号>`，刷新还原、链接可分享。
//
// 判权函数由调用方传入（`allow`）：`ui/` 不认权限码的来源，业务层传 `useCan()`。
import * as React from "react";
import { Drawer } from "./drawer";
import { Button } from "./button";
import { RowActions } from "./dropdown-menu";
import { useConfirm, type ConfirmOptions } from "./confirm-dialog";

export type ActionKind = "drawer" | "dialog" | "confirm";
const KINDS: readonly ActionKind[] = ["drawer", "dialog", "confirm"];

export interface ActionContext<T> {
  /** 当前对象；页面级操作（新建）为 null。 */
  row: T | null;
  close: () => void;
  /** 在**同一个抽屉**里换成另一个抽屉操作（详情 → 编辑），记住来处以便返回。 */
  switchTo: (key: string) => void;
  /** 打开任一操作（抽屉则替换，弹框则叠上）。 */
  open: (key: string) => void;
  /** 由 switchTo 进来时才有：回到上一个抽屉。 */
  back?: () => void;
  /** 当前对象上可用的操作（已按权限码与 visible 过滤），详情底栏据此出按钮。 */
  available: () => ActionSpec<T>[];
}

export interface ActionSpec<T> {
  key: string;
  label: string;
  kind: ActionKind;
  /** 权限码；无则人人可用（页面本身已按查看码判过）。 */
  perm?: string;
  /** 按对象状态决定是否出现（如只有「开启」状态可关闭）。 */
  visible?: (row: T | null) => boolean;
  /** 危险操作：在「更多」里置底并以红色显示。 */
  danger?: boolean;
  // ── drawer ──
  title?: (row: T | null) => string;
  width?: string;
  render?: (ctx: ActionContext<T>) => React.ReactNode;
  // ── dialog / confirm ──
  /** 弹框文案；dialog 自动要求填理由，强确认用 `requireText`。 */
  confirm?: (row: T | null) => Omit<ConfirmOptions, "action" | "requireReason">;
  run?: (row: T | null, reason: string) => Promise<unknown>;
}

/** 配置自检：开发期把写错的配置拦在页面外，而不是等运营点到才发现。 */
export function validateActions<T>(specs: ActionSpec<T>[]): void {
  const seen = new Set<string>();
  for (const s of specs) {
    if (seen.has(s.key)) throw new Error(`[ActionSpec] 重复的 key: ${s.key}`);
    seen.add(s.key);
    if (!KINDS.includes(s.kind)) throw new Error(`[ActionSpec] ${s.key}: kind 只能是 ${KINDS.join("/")}`);
    if (s.kind === "drawer" && !s.render) throw new Error(`[ActionSpec] ${s.key}: drawer 必须有 render`);
    if (s.kind !== "drawer" && (!s.run || !s.confirm)) throw new Error(`[ActionSpec] ${s.key}: ${s.kind} 必须有 confirm 与 run`);
  }
}

interface Slot<T> { key: string; row: T | null; from?: { key: string; row: T | null } }

export interface UseActionHostOptions<T> {
  actions: ActionSpec<T>[];
  allow: (perm: string) => boolean;
  getRowId?: (row: T) => string;
  /** 传了才与地址栏同步；resolve 用编号找回对象（可异步取详情）。 */
  syncUrl?: { resolve: (id: string) => T | null | Promise<T | null> };
}

export interface ActionHost<T> {
  available: (row: T | null) => ActionSpec<T>[];
  open: (key: string, row: T | null) => void;
  close: () => void;
  current: { key: string; row: T | null } | null;
  /** 抽屉与弹框的挂载点，页面渲染一次即可。 */
  node: React.ReactNode;
}

const PARAM_ACTION = "action";
const PARAM_ID = "id";

function writeUrl(key: string | null, id: string | null) {
  if (typeof window === "undefined") return;
  const url = new URL(window.location.href);
  if (key) url.searchParams.set(PARAM_ACTION, key); else url.searchParams.delete(PARAM_ACTION);
  if (key && id) url.searchParams.set(PARAM_ID, id); else url.searchParams.delete(PARAM_ID);
  const next = `${url.pathname}${url.search}${url.hash}`;
  if (next !== `${window.location.pathname}${window.location.search}${window.location.hash}`) {
    window.history.replaceState(window.history.state, "", next);
  }
}

export function useActionHost<T>({ actions, allow, getRowId, syncUrl }: UseActionHostOptions<T>): ActionHost<T> {
  if (process.env.NODE_ENV !== "production") validateActions(actions);
  const { confirm, dialog } = useConfirm();
  const [slot, setSlot] = React.useState<Slot<T> | null>(null);
  const byKey = React.useMemo(() => new Map(actions.map((a) => [a.key, a])), [actions]);

  const permitted = React.useCallback((a: ActionSpec<T>, row: T | null) =>
    (!a.perm || allow(a.perm)) && (!a.visible || a.visible(row)), [allow]);

  const available = React.useCallback((row: T | null) => actions.filter((a) => permitted(a, row)), [actions, permitted]);

  const idOf = React.useCallback((row: T | null) => (row && getRowId ? getRowId(row) : null), [getRowId]);

  const setDrawer = React.useCallback((next: Slot<T> | null) => {
    setSlot(next);
    if (syncUrl) writeUrl(next?.key ?? null, next ? idOf(next.row) : null);
  }, [syncUrl, idOf]);

  const runDialog = React.useCallback((a: ActionSpec<T>, row: T | null) => {
    void confirm({ ...a.confirm!(row), requireReason: a.kind === "dialog", action: (reason) => a.run!(row, reason) });
  }, [confirm]);

  const open = React.useCallback((key: string, row: T | null, from?: Slot<T>["from"]) => {
    const a = byKey.get(key);
    if (!a || !permitted(a, row)) return;
    if (a.kind === "drawer") setDrawer({ key, row, from });
    else runDialog(a, row);
  }, [byKey, permitted, setDrawer, runDialog]);

  const close = React.useCallback(() => setDrawer(null), [setDrawer]);

  // 进页时按地址栏还原（只做一次）。无权、找不到对象都静默不开。
  const restored = React.useRef(false);
  React.useEffect(() => {
    if (restored.current || !syncUrl || typeof window === "undefined") return;
    restored.current = true;
    const params = new URLSearchParams(window.location.search);
    const key = params.get(PARAM_ACTION);
    if (!key) return;
    const a = byKey.get(key);
    if (!a || a.kind !== "drawer") { writeUrl(null, null); return; }
    const id = params.get(PARAM_ID);
    void Promise.resolve(id ? syncUrl.resolve(id) : null).then((row) => {
      if ((id && !row) || !permitted(a, row)) { writeUrl(null, null); return; }
      setSlot({ key, row });
    });
  }, [syncUrl, byKey, permitted]);

  const spec = slot ? byKey.get(slot.key) : undefined;
  const ctx: ActionContext<T> | null = slot ? {
    row: slot.row,
    close,
    open: (key) => open(key, slot.row),
    switchTo: (key) => open(key, slot.row, { key: slot.key, row: slot.row }),
    back: slot.from ? () => setDrawer({ key: slot.from!.key, row: slot.from!.row }) : undefined,
    available: () => available(slot.row),
  } : null;

  const node = (
    <>
      {spec && ctx && (
        <Drawer
          // key 随操作与对象变：替换内容时整块重挂，表单状态不串
          key={`${slot!.key}:${idOf(slot!.row) ?? "new"}`}
          open
          onOpenChange={(o) => { if (!o) close(); }}
          title={spec.title?.(slot!.row) ?? spec.label}
          width={spec.width}
        >
          {spec.render!(ctx)}
        </Drawer>
      )}
      {dialog}
    </>
  );

  return { available, open: (k, r) => open(k, r), close, current: slot ? { key: slot.key, row: slot.row } : null, node };
}

/**
 * 表格操作列：最多 3 个直接按钮，其余（以及所有危险操作）进「更多」。
 * 第一个通常是「查看」—— 由声明顺序决定，这里不重排。
 */
export function RowActionButtons<T>({ row, host, max = 3 }: { row: T; host: ActionHost<T>; max?: number }) {
  const items = host.available(row);
  if (!items.length) return null;
  const plain = items.filter((a) => !a.danger);
  const direct = plain.slice(0, max);
  const rest = [...plain.slice(max), ...items.filter((a) => a.danger)];
  return (
    <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
      {direct.map((a) => (
        <Button key={a.key} size="sm" variant="ghost" data-row-action={a.key} onClick={() => host.open(a.key, row)}>
          {a.label}
        </Button>
      ))}
      <RowActions actions={rest.map((a) => ({ label: a.label, danger: a.danger, onSelect: () => host.open(a.key, row) }))} />
    </div>
  );
}
