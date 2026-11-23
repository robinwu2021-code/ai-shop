"use client";

// 审批页面共用的胶水：OpsKit 客户端（带当前令牌与界面语言）、命令执行与提示、状态徽标。
import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { StatusMap } from "@/components/ui/status-badge";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { useLocaleStore } from "@/lib/stores/locale";
import { useCopy } from "@/lib/use-copy";
import { createOpsKitApi, type OpsKitApi } from "./_api/client";
import { explainError } from "./_api/errors";
import { APPROVALS_COPY, type ApprovalsCopy } from "./copy";

export const QUERY_KEY = "opskit";
export const useAc = (): ApprovalsCopy => useCopy(APPROVALS_COPY);

export function useOpsKit(): OpsKitApi {
  return React.useMemo(() => createOpsKitApi({
    getToken: () => useAuth.getState().token,
    locale: () => useLocaleStore.getState().locale,
  }), []);
}

/** 执行一个命令：成功提示 + 刷新审批相关查询；失败按错误键显示文案并抛出（弹框留在原地）。 */
export function useOkRun() {
  const c = useAc();
  const { locale } = useI18n();
  const qc = useQueryClient();
  return React.useCallback(async <R,>(fn: () => Promise<R>): Promise<R> => {
    try {
      const r = await fn();
      notify.success(c.done);
      await qc.invalidateQueries({ queryKey: [QUERY_KEY] });
      return r;
    } catch (e) {
      const msg = explainError(e, locale);
      notify.error(msg);
      throw new Error(msg);
    }
  }, [c, locale, qc]);
}

export function useApprovalStatusMap(): StatusMap<string> {
  const c = useAc();
  return React.useMemo(() => ({
    PENDING: { label: c.st_PENDING, tone: "info" },
    APPROVED: { label: c.st_APPROVED, tone: "success" },
    REJECTED: { label: c.st_REJECTED, tone: "danger" },
    WITHDRAWN: { label: c.st_WITHDRAWN, tone: "muted" },
  }), [c]);
}

export function useCategoryMap(): StatusMap<string> {
  const c = useAc();
  return React.useMemo(() => ({
    NEW: { label: c.cat_NEW, tone: "muted" },
    IN_PROGRESS: { label: c.cat_IN_PROGRESS, tone: "info" },
    WAITING: { label: c.cat_WAITING, tone: "warning" },
    DONE: { label: c.cat_DONE, tone: "success" },
    CANCELLED: { label: c.cat_CANCELLED, tone: "muted" },
  }), [c]);
}

/** 业务对象 → ai-shop 里的原单页面。没登记的类型不出「打开原单」。 */
export function sourceHref(subject?: { type: string; ref: string }): string | null {
  if (!subject) return null;
  switch (subject.type) {
    case "shop.store_opening":
      return `/merchants?tab=stores&keyword=${encodeURIComponent(subject.ref)}`;
    default:
      return null;
  }
}

export function displayTitle(x: { title?: string; subject?: { ref: string; display?: { title?: string } } }): string {
  return x.title || x.subject?.display?.title || x.subject?.ref || "—";
}
