"use client";
// 平台证书与密钥（开发计划 M5b.3）：渠道平台证书、收款商户调渠道的密钥等（接入方的密钥在「接入方与授权」）。
// 只有指纹与序列号；启用要两个人 —— 录入人自己启用会被密钥模块拒绝。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsKeyVersionRow, PayOpsPlatformKeyRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, useTT } from "../shared";

export default function PayOpsPlatformKeysPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [open, setOpen] = useState<PayOpsPlatformKeyRow | null>(null);
  const list = useQuery({ queryKey: ["pay-ops-platform-keys"], queryFn: () => api.payOpsPlatformKeys() });
  const versions = useQuery({ queryKey: ["pay-ops-key-versions", open?.keyId], queryFn: () => api.payOpsPlatformKeyVersions(open!.keyId),
    enabled: !!open });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["pay-ops-key-versions"] }); void qc.invalidateQueries({ queryKey: ["pay-ops-platform-keys"] }); };
  const enable = useMutation({ mutationFn: (v: PayOpsKeyVersionRow) => api.payOpsEnablePlatformKey(open!.keyId, v.version), onSuccess: refresh });
  const revoke = useMutation({ mutationFn: (v: { row: PayOpsKeyVersionRow; reason: string }) =>
    api.payOpsRevokePlatformKey(open!.keyId, v.row.version, v.reason), onSuccess: refresh });

  function askRevoke(v: PayOpsKeyVersionRow) {
    const reason = window.prompt(tt(`吊销 ${open?.name} v${v.version}？立即失效。写下原因：`, `Revoke ${open?.name} v${v.version}? Takes effect at once. Reason:`));
    if (reason && reason.trim()) revoke.mutate({ row: v, reason: reason.trim() });
  }

  const columns = [
    { header: tt("名称", "Name"), cell: (k: PayOpsPlatformKeyRow) => k.name },
    { header: tt("归属", "Owner"), cell: (k: PayOpsPlatformKeyRow) => <span dir="ltr">{`${k.ownerType} #${k.ownerId}`}</span> },
    { header: tt("对端", "Counterparty"), cell: (k: PayOpsPlatformKeyRow) => k.counterparty ?? "—" },
    { header: tt("用途", "Purpose"), cell: (k: PayOpsPlatformKeyRow) => <span dir="ltr">{`${k.direction} · ${k.usage}`}</span> },
    { header: tt("当前版本", "Current"), numeric: true, cell: (k: PayOpsPlatformKeyRow) => k.currentVersion ? `v${k.currentVersion}` : "—" },
    { header: tt("到期", "Expires"), cell: (k: PayOpsPlatformKeyRow) => fmtTime(k.expiresAt) },
    { header: tt("状态", "Status"), cell: (k: PayOpsPlatformKeyRow) => <StatusBadge status={k.status} /> },
  ];
  const error = enable.error ?? revoke.error;
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "keys", label: tt("平台证书与密钥", "Platform keys") }]} value="keys" onChange={() => {}}
      desc={tt("支付用到的渠道证书与密钥。只显示指纹；启用一个版本要两个人（录入人不能自己启用）。", "Channel certificates and keys used by payments. Fingerprints only; enabling a version takes two people.")} />
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(k) => String(k.keyId)} empty={tt("还没有平台密钥。", "No platform keys yet.")}
      rowProps={(k) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setOpen(k) })} />
    <Drawer open={!!open} onOpenChange={(o) => { if (!o) setOpen(null); }} title={open?.name ?? ""} width="w-[760px]"
      desc={tt("各版本（新的在下）", "Versions")}>
      {error && <Notice tone="warning">{error.message}</Notice>}
      <DataTable rows={versions.data} loading={versions.isPending && !!open} error={versions.error} onRetry={() => void versions.refetch()}
        rowKey={(v) => String(v.version)} empty={tt("还没有版本。", "No versions yet.")} columns={[
          { header: tt("版本", "Version"), numeric: true, cell: (v) => `v${v.version}` },
          { header: tt("序列号", "Serial"), cell: (v) => <span dir="ltr">{v.serial ?? "—"}</span> },
          { header: tt("指纹", "Fingerprint"), cell: (v) => <span dir="ltr" className="font-mono">{v.fingerprint}</span> },
          { header: tt("录入人", "Entered by"), cell: (v) => v.enteredBy ?? "—" },
          { header: tt("状态", "Status"), cell: (v) => <StatusBadge status={v.status} /> },
          { header: tt("操作", "Actions"), stickyEnd: true, cell: (v) => <div className="flex gap-2">
            {v.status === "VERIFIED" && can("sec:key:enable") && <Button size="sm" variant="outline" disabled={enable.isPending}
              onClick={() => enable.mutate(v)}>{tt("启用", "Enable")}</Button>}
            {["ACTIVE", "ROTATING", "VERIFIED", "DRAFT"].includes(v.status) && can("sec:key:write") && <Button size="sm" variant="outline"
              disabled={revoke.isPending} onClick={() => askRevoke(v)}>{tt("吊销", "Revoke")}</Button>}
          </div> },
        ]} />
    </Drawer>
  </main>;
}
