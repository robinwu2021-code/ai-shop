"use client";

// 行业主数据（ADR-010）—— **已接真后端** `/ops/industries/**`。
//
// 它不是一张普通的字典表：**行业决定商家能不能以小微主体进件**。
// 微信的小微白名单按行业给，判错一次商家就是进件被拒 ——
// 而那时他已经开完店、上完架，回头改主体要重走一遍开户。
//
// 所以这一页的每个开关都要说清"关掉之后谁会受影响"，而不是一排裸开关。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { Industry } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { SystemCopy } from "./copy";

export function IndustryTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [current, setCurrent] = useState<Industry | null>(null);
  const [remark, setRemark] = useState("");

  const list = useQuery({ queryKey: ["industries"], queryFn: () => api.listIndustries() });
  const invalidate = () => qc.invalidateQueries({ queryKey: ["industries"] });

  const micro = useMutation({
    mutationFn: (v: { industry: string; payChannel: string; allowed: boolean }) =>
      api.setIndustryMicroAllowed(v.industry, v.payChannel, v.allowed, remark),
    onSuccess: (row) => { invalidate(); setCurrent(row); notify.success(c.indSaved); },
  });

  const enabled = useMutation({
    mutationFn: (v: { industry: string; enabled: boolean }) =>
      api.setIndustryEnabled(v.industry, v.enabled),
    onSuccess: (row) => { invalidate(); setCurrent(row); notify.success(c.indSaved); },
  });

  const points = useMutation({
    mutationFn: (v: { industry: string; forced: boolean }) =>
      api.setIndustryPointsForced(v.industry, v.forced),
    onSuccess: (row) => { invalidate(); setCurrent(row); notify.success(c.indSaved); },
  });

  const yesNo = (v: boolean) =>
    v ? <Badge tone="success">{c.indAllowed}</Badge> : <Badge tone="danger">{c.indNotAllowed}</Badge>;

  const columns: Column<Industry>[] = [
    { header: c.indColCode, cell: (i) => i.industry, numeric: true, align: "start" },
    { header: c.indColName, cell: (i) => i.name },
    { header: c.indColEnabled, cell: (i) => (i.enabled ? <Badge tone="success">{c.indEnabled}</Badge> : <Badge>{c.indDisabled}</Badge>) },
    { header: c.indColWechat, cell: (i) => yesNo(i.wechatMicroAllowed) },
    { header: c.indColAlipay, cell: (i) => yesNo(i.alipayMicroAllowed) },
    { header: c.indColPoints, cell: (i) => (i.pointsForced ? <Badge tone="warning">{c.indForced}</Badge> : "—") },
    { header: c.indColRemark, cell: (i) => i.remark ?? "—" },
    {
      header: c.indColActions,
      cell: (i) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(i); setRemark(i.remark ?? ""); }}>
          {c.indConfigure}
        </Button>
      ),
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">
        {c.indNotice}
      </HelpNote>

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(i) => i.industry}
      />

      <Drawer open={!!current} onOpenChange={(o) => !o && setCurrent(null)} title={current?.name ?? ""}>
        {current && (
          <>
            <DrawerSection title={c.indSectionNow}>
              <FieldGrid>
                <Field label={c.indColCode}>{current.industry}</Field>
                <Field label={c.indSort}>{current.sort}</Field>
                <Field label={c.indColEnabled}>{current.enabled ? c.indYes : c.indNo}</Field>
                <Field label={c.indColPoints}>{current.pointsForced ? c.indYes : c.indNo}</Field>
              </FieldGrid>
            </DrawerSection>

            {canWrite && (
              <DrawerSection title={c.indSectionEdit}>
                {/* 备注放在开关**上面**：改完再补备注，多半就不补了 */}
                <Label>{c.indRemarkLabel}</Label>
                <Textarea
                  value={remark}
                  onChange={(v) => setRemark(v)}
                  placeholder={c.indRemarkPh}
                />

                <div className="mt-4 flex flex-wrap gap-2">
                  <Button
                    variant="outline"
                    onClick={() => micro.mutate({
                      industry: current.industry, payChannel: "WECHAT",
                      allowed: !current.wechatMicroAllowed,
                    })}
                  >
                    {current.wechatMicroAllowed ? c.indWechatOff : c.indWechatOn}
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => micro.mutate({
                      industry: current.industry, payChannel: "ALIPAY",
                      allowed: !current.alipayMicroAllowed,
                    })}
                  >
                    {current.alipayMicroAllowed ? c.indAlipayOff : c.indAlipayOn}
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => points.mutate({ industry: current.industry, forced: !current.pointsForced })}
                  >
                    {current.pointsForced ? c.indPointsOff : c.indPointsOn}
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => enabled.mutate({ industry: current.industry, enabled: !current.enabled })}
                  >
                    {current.enabled ? c.indDisableAct : c.indEnableAct}
                  </Button>
                </div>

                {/* 停用不影响存量：不说清楚的话，没人敢点这个按钮 */}
                <Notice className="mt-3">
                  {c.indDisableNote}
                </Notice>
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
