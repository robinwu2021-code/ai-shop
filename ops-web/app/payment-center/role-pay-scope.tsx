"use client";

// 角色在支付中心的数据范围（只读，TDD-支付运营端经网关转发与权限下沉 §10 B2）。
// 范围存在 near-pay（SY04，按 ai-shop 角色码授予、两人审批），这里经网关读出生效的委托给配权限的人看；
// 增改仍在支付中心「数据范围」页签。不新增 ai-shop 端点。模块外经 module.ts 引用（模块边界）。
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { usePayCenterApi } from "./_kit/pay-kit";
import type { DataScopeRow } from "./_api/types";
import { fill, useCopy } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { ROLE_PAY_SCOPE_COPY as COPY } from "./role-pay-scope-copy";



/** 只留这个角色、生效中的委托（后端筛选参数不一定支持，前端再筛一遍） */
export function activeRoleScopes(rows: DataScopeRow[], roleCode: string, now = Date.now()): DataScopeRow[] {
  return rows.filter((r) => r.scopeType === "ROLE" && r.subjectNo === roleCode && r.status === "ACTIVE" && r.expiresAt > now);
}

export function RolePayScope({ roleCode }: { roleCode: string }) {
  const c = useCopy(COPY);
  const pay = usePayCenterApi();
  const scopes = useQuery({
    queryKey: ["role-pay-scope", roleCode],
    queryFn: () => pay.dataScopes.list({ scopeType: "ROLE", subjectNo: roleCode, status: "ACTIVE", page: 1, size: 100 }),
    staleTime: 30_000,
  });
  const rows = activeRoleScopes(scopes.data?.records ?? [], roleCode);

  return (
    <section className="space-y-2 rounded-card border border-border p-3" aria-label={c.payScopeTitle}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="txt-label">{c.payScopeTitle}</h3>
        <Link className="focus-ring txt-caption text-primary-ink hover:underline" href="/payment-center?tab=data-scopes">{c.payScopeOpen}</Link>
      </div>
      <p className="txt-caption text-muted-foreground">{c.payScopeHint}</p>
      {scopes.isPending ? null : scopes.error ? (
        <Notice tone="danger">{c.payScopeError}</Notice>
      ) : rows.length === 0 ? (
        <Notice tone="muted">{c.payScopeEmpty}</Notice>
      ) : (
        <ul className="space-y-1">
          {rows.map((r) => (
            <li key={r.scopeNo} className="flex flex-wrap items-center gap-2 txt-body">
              <Badge>{r.marketSet.join(" / ")}</Badge>
              <span>{r.entityNos.includes("*") ? c.payScopeAllMerchants : fill(c.payScopeMerchants, { n: r.entityNos.length })}</span>
              <span className="text-muted-foreground">·</span>
              <span>{r.fundsAccess === "MASKED_READ" ? c.payScopeFundsMasked : c.payScopeFundsNone}</span>
              <span className="text-muted-foreground">·</span>
              <span className="txt-caption text-muted-foreground">{fill(c.payScopeExpires, { date: fmtTime(r.expiresAt) })}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
