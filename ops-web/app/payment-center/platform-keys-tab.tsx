"use client";

// 开放平台密钥（P12，RSA 方案；near-pay OpsPlatformKeyController）。列表是已公布的平台公钥（即将启用 / 使用中 / 重叠期）；
// 页面级：生成签名 / 加密密钥（理由弹框，新版本为草稿）、核验 / 启用（PLATFORM_OPEN 凭证，启用两人）。
// 行操作：查看（公钥 PEM + 该凭证的版本与核验 / 启用 / 撤销）。私钥永不出现在任何响应里。
import * as React from "react";
import { Copy } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import { DetailBody } from "@/components/ui/detail-drawer";
import { Button } from "@/components/ui/button";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import type { CredentialRow, PlatformKeyRow, PlatformKeyUsage } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, MiniTable, usePc, useRun, useTabList } from "./shared";
import { CredentialDetailView } from "./credentials-tab";
import { P12_COPY, type P12Copy } from "./p12-copy";
import { P10_COPY } from "./p10-copy";
import { p8Map } from "./members-tab";

const NAME = "platform-keys";
type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const usageLabel = (m: P12Copy, u: string) => (m as unknown as Record<string, string>)[`usage_${u}`] ?? u;
const rowId = (k: PlatformKeyRow) => `${k.keyId}:${k.versionIndex}`;

export function PlatformKeysTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const run = useRun(NAME);
  const statusMap = React.useMemo(() => p8Map(m, "st_pk", ["VERIFIED", "ACTIVE", "ROTATING"], { ACTIVE: "success", VERIFIED: "info", ROTATING: "warning" }), [m]);
  // near-pay 回整张表（量很小）：前端分页
  const list = useTabList<PlatformKeyRow, Record<string, string>>(NAME, {}, async (api, q) => {
    const all = await api.platformKeys.list(); const page = Number(q.page ?? 1), size = Number(q.size ?? 20);
    return { records: all.slice((page - 1) * size, page * size), total: all.length };
  });
  const { api } = list;
  const generate = (usage: PlatformKeyUsage): ActionSpec<PlatformKeyRow> => ({
    key: `generate-${usage}`, label: usage === "SIGN" ? m.generateSign : m.generateEncrypt, kind: "dialog", perm: "pay:credential:manage",
    confirm: () => ({ title: fill(m.generateKeyTitle, { usage: usageLabel(m, usage) }), desc: m.generateKeyDesc, reasonHint: c.reasonHint }),
    run: async (_r, reason) => {
      const g = await run(() => api.platformKeys.generate(usage, reason));
      notify.info(fill(m.generatedKey, { serial: g.version.externalKeyId ?? `v${g.version.versionIndex}`, keyId: g.keyId }));
    },
  });

  const actions: ActionSpec<PlatformKeyRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[860px]", title: () => m.platformKeyTitle,
    render: (ctx) => <PlatformKeyView api={api} k={ctx.row!} run={run} />,
  }];
  const pageActions: ActionSpec<PlatformKeyRow>[] = [
    generate("SIGN"),
    generate("ENCRYPT"),
    { key: "versions", label: m.manageVersions, kind: "drawer", perm: "pay:credential:view", width: "w-[860px]", title: () => m.manageVersions, render: () => <PlatformCredentials api={api} run={run} /> },
  ];

  const columns: Column<PlatformKeyRow>[] = [
    { header: m.usage, cell: (k) => usageLabel(m, k.usage) },
    { header: m.serial, cell: (k) => <PayRefCell value={k.serial} /> },
    { header: c.colStatus, cell: (k) => <StatusBadge value={k.status} map={statusMap} /> },
    { header: m.fingerprint, cell: (k) => <span className="font-mono txt-caption">{k.fingerprint}</span> },
    { header: m.enabledAt, cell: (k) => (k.enabledAt ? fmtTime(k.enabledAt) : "—") },
    { header: m.overlapUntil, cell: (k) => (k.overlapUntil ? fmtTime(k.overlapUntil) : "—") },
  ];

  return (
    <PayListPage<PlatformKeyRow>
      header={header}
      help={m.platformKeysDesc}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={rowId}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.platformKeys.list().then((all) => all.find((k) => rowId(k) === id) ?? null)}
      empty={m.platformKeysEmpty}
    />
  );
}

function PlatformKeyView({ api, k, run }: { api: PayCenterApi; k: PlatformKeyRow; run: Run }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  return (
    <>
      <DetailBody sections={[
        { title: c.sectionBasic, fields: [
          { label: m.usage, value: usageLabel(m, k.usage) },
          { label: m.serial, value: <PayRefCell value={k.serial} /> },
          { label: m.fingerprint, value: <span className="font-mono break-all">{k.fingerprint}</span> },
          { label: m.enabledAt, value: k.enabledAt ? fmtTime(k.enabledAt) : "—" },
          { label: "PEM", full: true, value: (
            <div>
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded-field bg-muted p-3 font-mono txt-caption">{k.publicKeyPem}</pre>
              <Button size="sm" variant="ghost" className="mt-1" onClick={() => { void navigator.clipboard?.writeText(k.publicKeyPem).then(() => notify.success(c.done), () => {}); }}>
                <Copy className="size-3.5" /> {m.copyPublicKey}
              </Button>
            </div>
          ) },
        ] },
      ]} />
      <CredentialDetailView api={api} keyId={k.keyId} run={run} name={NAME} />
    </>
  );
}

/** PLATFORM_OPEN 凭证（含未公布的草稿）：选一条，就地显示版本与核验 / 启用 / 撤销 */
function PlatformCredentials({ api, run }: { api: PayCenterApi; run: Run }) {
  const m = useCopy(P12_COPY);
  const t = useCopy(P10_COPY);
  const [keyId, setKeyId] = React.useState<number | null>(null);
  return (
    <DetailLoader name={NAME} id="platform-credentials" load={() => api.credentials.list({ ownerType: "PLATFORM_OPEN", page: 1, size: 50 })}>
      {(p) => (
        <div className="space-y-4">
          <MiniTable<CredentialRow> rows={p.records} rowKey={(r) => String(r.keyId)} empty={m.platformKeysEmpty} columns={[
            { header: t.keyId, cell: (r) => r.keyId },
            { header: m.usage, cell: (r) => usageLabel(m, r.credentialType === "OPEN_PLATFORM_SIGN" ? "SIGN" : "ENCRYPT") },
            { header: t.currentVersion, cell: (r) => (r.currentVersion ? `v${r.currentVersion}` : "—") },
            { header: t.pendingVersions, cell: (r) => r.pendingVersions },
            { header: "", cell: (r) => <Button size="sm" variant={keyId === r.keyId ? "default" : "outline"} onClick={() => setKeyId(r.keyId)}>{m.manageVersions}</Button> },
          ]} />
          {keyId != null && <CredentialDetailView key={keyId} api={api} keyId={keyId} run={run} name={NAME} />}
        </div>
      )}
    </DetailLoader>
  );
}
