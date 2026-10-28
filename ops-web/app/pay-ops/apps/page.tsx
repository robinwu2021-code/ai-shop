"use client";
// 接入方与授权、接入方密钥（开发计划 M5b.1，原 F54）：密钥只看指纹 / 序列号 / 状态，明文不出现；吊销即时生效。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsAppRow, PayOpsCredentialRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, useTT } from "../shared";

const KIND: Record<string, [string, string]> = { APP_SECRET: ["签名密钥 AppSecret", "AppSecret"], ENCRYPT_KEY: ["字段加密 EncryptKey", "EncryptKey"] };

export default function PayOpsAppsPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [open, setOpen] = useState<string | null>(null);
  const list = useQuery({ queryKey: ["pay-ops-apps"], queryFn: () => api.payOpsApps() });
  const grants = useQuery({ queryKey: ["pay-ops-app-grants", open], queryFn: () => api.payOpsAppGrants(open!), enabled: !!open });
  const creds = useQuery({ queryKey: ["pay-ops-app-credentials", open], queryFn: () => api.payOpsAppCredentials(open!), enabled: !!open });
  const revoke = useMutation({
    mutationFn: (v: { c: PayOpsCredentialRow; reason: string }) => api.payOpsRevokeAppCredential(open!, v.c.kind, v.c.version, v.reason),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["pay-ops-app-credentials"] }),
  });

  function askRevoke(c: PayOpsCredentialRow) {
    const reason = window.prompt(tt(`吊销 ${open} 的 ${KIND[c.kind]?.[0] ?? c.kind} v${c.version}？吊销后用这一版签的请求立即被拒。写下原因：`,
      `Revoke ${open} ${KIND[c.kind]?.[1] ?? c.kind} v${c.version}? Requests signed with it are rejected at once. Reason:`));
    if (reason && reason.trim()) revoke.mutate({ c, reason: reason.trim() });
  }

  const columns = [
    { header: tt("接入方", "App"), cell: (a: PayOpsAppRow) => <span dir="ltr">{a.appNo}</span> },
    { header: tt("名称", "Name"), cell: (a: PayOpsAppRow) => a.name },
    { header: tt("类型", "Type"), cell: (a: PayOpsAppRow) => a.ownerType === "INTERNAL" ? tt("内部系统", "Internal") : tt("第三方", "Partner") },
    { header: tt("能力", "Scopes"), cell: (a: PayOpsAppRow) => <span dir="ltr">{a.scopes ?? "—"}</span> },
    { header: tt("授权门店", "Granted stores"), numeric: true, cell: (a: PayOpsAppRow) => a.activeGrants },
    // 接入方不绑币种：上限按最小单位原样给（各市场的币种在门店上）
    { header: tt("日退款上限（最小单位）", "Daily refund cap (minor units)"), numeric: true,
      cell: (a: PayOpsAppRow) => a.dailyRefundLimitMinor ? a.dailyRefundLimitMinor.toLocaleString() : "—" },
    { header: tt("状态", "Status"), cell: (a: PayOpsAppRow) => <StatusBadge status={a.status} /> },
  ];
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "apps", label: tt("接入方与授权", "Apps & grants") }]} value="apps" onChange={() => {}}
      desc={tt("调支付的第三方与内部系统。点一行看授权的门店与密钥（只有指纹，没有明文）。", "Partners and internal systems that call payments. Click a row for granted stores and keys (fingerprints only).")} />
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(a) => a.appNo} empty={tt("还没有接入方。", "No apps yet.")}
      rowProps={(a) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setOpen(a.appNo) })} />
    <Drawer open={!!open} onOpenChange={(o) => { if (!o) setOpen(null); }} title={open ?? ""} width="w-[860px]"
      desc={tt("授权门店与接入方密钥", "Granted stores and app credentials")}>
      {revoke.error && <Notice tone="warning">{revoke.error.message}</Notice>}
      <div className="space-y-4">
        <section><h3 className="font-semibold">{tt("接入方密钥", "Credentials")}</h3>
          <DataTable rows={creds.data} loading={creds.isPending && !!open} error={creds.error} onRetry={() => void creds.refetch()}
            rowKey={(c) => `${c.kind}-${c.version}`} empty={tt("还没有发过密钥（或仍在部署配置里）。", "No credentials yet (or still in deployment config).")} columns={[
              { header: tt("类型", "Kind"), cell: (c) => tt(...(KIND[c.kind] ?? [c.kind, c.kind])) },
              { header: tt("版本", "Version"), numeric: true, cell: (c) => `v${c.version}` },
              { header: tt("序列号", "Serial"), cell: (c) => <span dir="ltr">{c.serial ?? "—"}</span> },
              { header: tt("指纹", "Fingerprint"), cell: (c) => <span dir="ltr" className="font-mono">{c.fingerprint}</span> },
              { header: tt("录入人", "Entered by"), cell: (c) => c.enteredBy ?? "—" },
              { header: tt("状态", "Status"), cell: (c) => <StatusBadge status={c.status} /> },
              { header: tt("操作", "Actions"), stickyEnd: true, cell: (c) => can("pay:app-credential:revoke") && ["ACTIVE", "ROTATING", "VERIFIED"].includes(c.status)
                ? <Button size="sm" variant="outline" disabled={revoke.isPending} onClick={() => askRevoke(c)}>{tt("吊销", "Revoke")}</Button> : null },
            ]} /></section>
        <section><h3 className="font-semibold">{tt("授权门店", "Granted stores")}</h3>
          <DataTable rows={grants.data} loading={grants.isPending && !!open} error={grants.error} onRetry={() => void grants.refetch()}
            rowKey={(g) => `${g.storeId}-${g.grantedAt}`} empty={tt("没有门店授权过它。", "No store has granted it.")} columns={[
              { header: tt("门店", "Store"), cell: (g) => g.storeId ?? "—" },
              { header: tt("动作", "Actions"), cell: (g) => <span dir="ltr">{g.actions ?? "—"}</span> },
              { header: tt("授权人", "Granted by"), cell: (g) => g.grantedBy ?? "—" },
              { header: tt("授权时间", "Granted at"), cell: (g) => fmtTime(g.grantedAt) },
              { header: tt("状态", "Status"), cell: (g) => <StatusBadge status={g.status} /> },
            ]} /></section>
      </div>
    </Drawer>
  </main>;
}
