"use client";

// 支付中心各页签共用的小件：列表状态、状态映射、详情加载、命令执行与提示、表单字段转换。
// 页面骨架（ListPage / ActionHost / DetailBody / ActionForm）在 components/ui，这里只放支付中心自己的胶水。
import * as React from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { DataTable, type Column } from "@/components/ui/data-table";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import type { StatusMap } from "@/components/ui/status-badge";
import type { BadgeTone } from "@/components/ui/badge";
import type { FieldDef } from "@/components/ui/form-drawer";
import { usePayCenterApi } from "@/app/payment-center/_kit/pay-kit";
import { useCopy } from "@/lib/use-copy";
import { notify } from "@/lib/notify";
import { PayOpsError, type PayPage } from "@/app/payment-center/_api/client";
import { money } from "@/lib/utils";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { PAY_CENTER_COPY, type PayCenterCopy } from "./copy";

export type C = PayCenterCopy;
export const usePc = () => useCopy(PAY_CENTER_COPY);
export { usePayCenterApi };

const KEY = "pay-center";

/** 列表页签的状态：分页 + 关键字 + 筛选，任一变化回到第 1 页。 */
export function useTabList<T, F extends Record<string, string | boolean>>(
  name: string, initial: F, fetch: (api: PayCenterApi, q: Record<string, string | number | boolean | undefined>) => Promise<PayPage<T>>,
) {
  const api = usePayCenterApi();
  const [page, setPage] = React.useState(1);
  const [size, setSizeState] = React.useState(20);
  const [keyword, setKeywordState] = React.useState("");
  const [filters, setFilters] = React.useState<F>(initial);
  const q = { page, size, keyword: keyword || undefined, ...Object.fromEntries(Object.entries(filters).map(([k, v]) => [k, v === "" || v === false ? undefined : v])) };
  const query = useQuery({ queryKey: [KEY, name, api.source, q], queryFn: () => fetch(api, q), placeholderData: keepPreviousData });
  return {
    api, query, page, size, keyword, filters,
    setPage,
    setSize: (n: number) => { setSizeState(n); setPage(1); },
    setKeyword: (v: string) => { setKeywordState(v); setPage(1); },
    setFilter: <K extends keyof F>(k: K, v: F[K]) => { setFilters((f) => ({ ...f, [k]: v })); setPage(1); },
  };
}

/**
 * 当前运营在 near-pay 的员工号（/me/access 的 operatorNo）。审批的申请人 / 认领人、告警认领人、接入申请提交人
 * 在 near-pay 都记员工号（ST-…），拿登录名比永远不相等 —— 「批准」「释放」「撤回」这类「是不是我」的按钮要用它判断。
 * 取不到时返回 null（按钮按「不是我」处理，不会误放开）。
 */
export function usePayOperatorNo(): string | null {
  const api = usePayCenterApi();
  const q = useQuery({ queryKey: [KEY, "me", api.source], queryFn: () => api.me(), staleTime: 10 * 60_000, retry: 2 });
  return q.data?.operatorNo ?? null;
}

/** 让本页签（及打开中的详情）重新取数。 */
export function useRefresh(name: string) {
  const qc = useQueryClient();
  return React.useCallback(() => qc.invalidateQueries({ queryKey: [KEY, name] }), [qc, name]);
}

/** near-pay 错误 → 给人看的一句话（版本冲突给统一说法） */
export function errorText(e: unknown, c: C): string {
  if (e instanceof PayOpsError && e.reasonCode === "VERSION_CONFLICT") return c.versionChanged;
  if (e && typeof e === "object" && "reasonCode" in e && (e as { reasonCode?: string }).reasonCode === "VERSION_CONFLICT") return c.versionChanged;
  return e instanceof Error ? e.message : String(e);
}

/**
 * 执行一个命令：成功 toast + 刷新；失败 toast 并把错误抛回去
 * （确认弹框据此留在原地，ActionForm 据此不关抽屉）。
 */
/** near-pay 已切 OpsKit 审批的操作（出款回执、对账差异关闭、人工确认到账）回的是「待审批」而不是结果。 */
export function isPendingApproval(r: unknown): boolean {
  if (!r || typeof r !== "object") return false;
  const o = r as { status?: unknown; statusAfter?: unknown };
  return o.status === "PENDING_APPROVAL" || o.statusAfter === "PENDING_APPROVAL";
}

export function useRun(name: string) {
  const c = usePc();
  const refresh = useRefresh(name);
  return React.useCallback(async <R,>(fn: () => Promise<R>, success?: string): Promise<R> => {
    try {
      const r = await fn();
      // OpsKit 审批（第二批 Q11）：near-pay 拦下的操作回 PENDING_APPROVAL —— 没有生效，不能报「已完成」
      if (isPendingApproval(r)) notify.info(c.pendingApproval);
      else notify.success(success ?? c.done);
      await refresh();
      return r;
    } catch (e) {
      const msg = errorText(e, c);
      notify.error(msg);
      throw new Error(msg);
    }
  }, [c, refresh]);
}

/** 按枚举值从文案取状态名：`st_<prefix>_<value>`；没登记的值原样显示。 */
export function useStatusMap(prefix: string, values: readonly string[], tones: Record<string, BadgeTone> = {}): StatusMap<string> {
  const c = usePc() as unknown as Record<string, string>;
  return React.useMemo(() => Object.fromEntries(values.map((v) => [v, { label: c[`st_${prefix}_${v}`] ?? v, tone: tones[v] ?? "muted" }])),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [c, prefix, values.join(",")]);
}

/** 抽屉里取详情：加载中骨架、失败可重试 —— 不渲染空内容让人以为数据是空的（列表与抽屉规范 §4）。 */
export function DetailLoader<T>({ name, id, load, children }: {
  name: string; id: string; load: () => Promise<T>; children: (d: T) => React.ReactNode;
}) {
  const c = usePc();
  const api = usePayCenterApi();
  const q = useQuery({ queryKey: [KEY, name, "detail", api.source, id], queryFn: load });
  if (q.isPending) return <div className="space-y-3"><Skeleton className="h-5 w-1/2" /><Skeleton className="h-5 w-2/3" /><Skeleton className="h-5 w-1/3" /></div>;
  if (q.error) return <ErrorState error={new Error(errorText(q.error, c))} onRetry={() => void q.refetch()} />;
  return <>{children(q.data as T)}</>;
}

/**
 * 抽屉里的窄表（明细行）。行来自已经由 DetailLoader 取好的详情，不是独立查询 ——
 * 加载与出错由外层 DetailLoader 负责（骨架 / ErrorState + 重试），这里没有自己的 error 可接。
 */
export function MiniTable<T>({ rows, columns, rowKey, empty }: { rows: T[]; columns: Column<T>[]; rowKey: (r: T) => string; empty: string }) {
  return <DataTable compact rows={rows} columns={columns} rowKey={rowKey} empty={empty} />;
}

/**
 * 实例币种：near-pay 一个实例一种币种（经营设置里的 currency）。有些 DTO（提现、对账差异、费率）不带币种，
 * 用它格式化；取不到（无权看经营设置、接口失败）时退化为不带币种符号的数字 —— 宁可不写符号，也不默认成人民币。
 */
export function useInstanceCurrency(): string | undefined {
  const api = usePayCenterApi();
  const q = useQuery({ queryKey: [KEY, "instance-currency", api.source], queryFn: () => api.settings.get(), staleTime: 10 * 60_000, retry: false });
  return q.data?.currency;
}
export function amt(minor: number, currency?: string): string {
  return currency ? money(minor, currency) : (minor / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// ── 表单字段转换 ──
export const csv = (v: unknown): string[] => String(v ?? "").split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean);
export const joinCsv = (v: string[] | null | undefined) => (v ?? []).join(", ");
/** 元 ↔ 最小单位：与 lib/utils 的 money()（固定 MINOR_UNIT=100）同一口径，界面输入与显示才对得上 */
export const toMinor = (v: unknown) => Math.round(Number(v) * 100);
export const fromMinor = (v: number) => v / 100;
/** 「2026-11-20 14:30」→ UTC 毫秒（按浏览器本地时区解释）；不合法返回 NaN */
export function parseLocalDateTime(v: unknown): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?$/.exec(String(v ?? "").trim());
  if (!m) return Number.NaN;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4] ?? 0), Number(m[5] ?? 0)).getTime();
}
export const datetimePattern = (c: C) => ({ re: "^\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2})?$", msg: c.settlePaidAtHelp });

/** 「原因」字段：所有改状态、改配置的表单都要它（near-pay Requests.reason 1..255） */
export function reasonField(c: C): FieldDef {
  return { key: "reason", label: c.reasonField, type: "textarea", required: true, maxLength: 255, rows: 2, help: c.reasonHelp };
}

export const options = (values: readonly string[], label: (v: string) => string) => values.map((v) => ({ value: v, label: label(v) }));
export const yesNo = (b: boolean | null | undefined, c: C) => (b ? c.yes : c.no);
