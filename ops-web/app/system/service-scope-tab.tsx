"use client";

// 经营范围开关（ADR-009 / TDD-一期主数据收敛 阶段二）—— **已接真后端** `/ops/service-scopes/**`。
//
// 档位本身是枚举，永远是那三个；这一页配的是**这一期开放哪几档**。
// 两件事分开是有意的：合成一个的话，在这里放开一档会顺手获得
// 「往 serviceScope 写任意字符串」的能力，而那正是本次修掉的 D1。
//
// 这一页存在的全部理由：拿到 EDI 切平台模式时，运营在这里点开「全平台」即可，
// 不用改代码、不用发版。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { ServiceScopeConfig } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { SystemCopy } from "./copy";

export function ServiceScopeTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [current, setCurrent] = useState<ServiceScopeConfig | null>(null);
  const [reason, setReason] = useState("");

  const list = useQuery({ queryKey: ["service-scopes"], queryFn: () => api.listServiceScopes() });

  const enabled = useMutation({
    mutationFn: (v: { scope: string; enabled: boolean }) =>
      api.setServiceScopeEnabled(v.scope, v.enabled, reason),
    onSuccess: (rows) => {
      qc.invalidateQueries({ queryKey: ["service-scopes"] });
      setCurrent(rows.find((r) => r.scope === current?.scope) ?? null);
      notify.success(c.scSaved);
    },
  });

  // 档位名与说明都在文案里，不从后端取：它们是产品定义（ADR-009），不是数据
  const label = (scope: string) =>
    scope === "COMMUNITY" ? c.scCommunity : scope === "CITY" ? c.scCity : c.scPlatform;
  const desc = (scope: string) =>
    scope === "COMMUNITY" ? c.scCommunityDesc : scope === "CITY" ? c.scCityDesc : c.scPlatformDesc;

  const columns: Column<ServiceScopeConfig>[] = [
    { header: c.scColScope, cell: (r) => label(r.scope), align: "start" },
    { header: c.scColDesc, cell: (r) => <span className="text-muted-foreground">{desc(r.scope)}</span> },
    {
      header: c.scColEnabled,
      cell: (r) => (r.enabled ? <Badge tone="success">{c.scOpen}</Badge> : <Badge>{c.scClosed}</Badge>),
    },
    // 关之前要知道影响面：关掉一个 300 家店在用的档，和关一个没人用的，是两件事
    { header: c.scColMerchants, cell: (r) => r.merchantCount, numeric: true },
    {
      header: c.scColActions,
      cell: (r) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(r); setReason(""); }}>
          {c.scConfigure}
        </Button>
      ),
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.scNotice}</HelpNote>

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => r.scope}
      />

      <Drawer open={!!current} onOpenChange={(o) => !o && setCurrent(null)} title={current ? label(current.scope) : ""}>
        {current && (
          <>
            <DrawerSection title={c.scSectionNow}>
              <FieldGrid>
                <Field label={c.scColScope}>{current.scope}</Field>
                <Field label={c.scColEnabled}>{current.enabled ? c.scOpen : c.scClosed}</Field>
                <Field label={c.scColMerchants}>{current.merchantCount}</Field>
              </FieldGrid>
              <p className="txt-caption text-muted-foreground mt-2">{desc(current.scope)}</p>
            </DrawerSection>

            {canWrite && (
              <DrawerSection title={c.scConfigure}>
                {/* 原因放在开关上面：改完再补，多半就不补了 */}
                <Label>{c.scReasonLabel}</Label>
                <Textarea value={reason} onChange={(v) => setReason(v)} placeholder={c.scReasonPh} />

                <div className="mt-4">
                  <Button
                    variant="outline"
                    onClick={() => enabled.mutate({ scope: current.scope, enabled: !current.enabled })}
                  >
                    {current.enabled ? c.scCloseAct : c.scOpenAct}
                  </Button>
                </div>

                <Notice className="mt-3">{c.scLastOneNote}</Notice>
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
