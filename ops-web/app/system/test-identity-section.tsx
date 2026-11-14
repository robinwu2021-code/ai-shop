"use client";

// 测试身份白名单（TDD-测试身份白名单登录）—— **已接真后端** `/ops/test-identities/**`。
//
// 挂在「测试号固定验证码」页签下面而不另开页签：同一类东西、同一对权限码，
// 另开页签要动菜单、点位码与界面清单，换来的只是多一个入口。
// 类型按有没有 @ 自动认，省一个下拉框，也免得选错类型存进一条永远对不上的记录。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { TestIdentity } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { SystemCopy } from "./copy";

export function TestIdentitySection({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [value, setValue] = useState("");
  const [remark, setRemark] = useState("");

  const list = useQuery({ queryKey: ["test-identities"], queryFn: () => api.listTestIdentities() });
  const done = () => { qc.invalidateQueries({ queryKey: ["test-identities"] }); notify.success(c.tpSaved); };

  const save = useMutation({
    mutationFn: () => {
      const v = value.trim();
      return api.saveTestIdentity({
        identityType: v.includes("@") ? "EMAIL" : "PHONE",
        identityValue: v,
        remark: remark.trim() || undefined,
      });
    },
    onSuccess: () => { setValue(""); setRemark(""); done(); },
  });
  const enabled = useMutation({
    mutationFn: (v: { id: number; enabled: boolean }) => api.setTestIdentityEnabled(v.id, v.enabled),
    onSuccess: done,
  });
  const remove = useMutation({
    mutationFn: (id: number) => api.removeTestIdentity(id),
    onSuccess: done,
  });

  const columns: Column<TestIdentity>[] = [
    { header: c.tiColType, cell: (r) => (r.identityType === "EMAIL" ? c.tiTypeEmail : c.tiTypePhone), align: "start" },
    { header: c.tiColValue, cell: (r) => <code className="txt-caption">{r.identityValue}</code>, align: "start" },
    {
      header: c.tpColEnabled,
      cell: (r) => (r.enabled ? <Badge tone="warning">{c.tpEnabled}</Badge> : <Badge>{c.tpDisabled}</Badge>),
    },
    {
      header: c.tpColRemark,
      cell: (r) => r.remark ?? <span className="text-muted-foreground">{c.tpNoRemark}</span>,
    },
    {
      header: c.tpColActions,
      cell: (r) => canWrite ? (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => enabled.mutate({ id: r.id, enabled: !r.enabled })}>
            {r.enabled ? c.tpDisableAct : c.tpEnableAct}
          </Button>
          <Button size="sm" variant="outline" onClick={() => { if (confirm(c.tpRemoveConfirm)) remove.mutate(r.id); }}>
            {c.tpRemoveAct}
          </Button>
        </div>
      ) : null,
    },
  ];

  return (
    <section className="mt-8">
      <h3 className="txt-title mb-3">{c.tiTitle}</h3>
      <HelpNote className="mb-3">{c.tiNotice}</HelpNote>
      {list.data && !list.data.switchOn && <Notice className="mb-3">{c.tiSwitchOff}</Notice>}

      {canWrite && (
        <div className="mb-3 flex flex-wrap gap-2">
          <Input
            aria-label={c.tiColValue} className="w-72" value={value} placeholder={c.tiFieldValuePh}
            onChange={(e) => setValue(e.target.value)}
          />
          <Input
            aria-label={c.tpColRemark} className="w-72" value={remark} placeholder={c.tiFieldRemarkPh}
            onChange={(e) => setRemark(e.target.value)}
          />
          <Button variant="outline" disabled={!value.trim() || save.isPending} onClick={() => save.mutate()}>
            {c.tiAdd}
          </Button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={list.data?.items}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => String(r.id)}
        empty={c.tiEmpty}
      />
    </section>
  );
}
