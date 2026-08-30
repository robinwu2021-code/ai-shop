"use client";

// 支付通道设置与费率（后端 sys_pay_channel + sys_pay_channel_rate）。
//
// **与隔壁「分档费率与服务费」是两笔钱**：那边配的是平台向商家收的佣金，
// 这边配的是通道向我们收的手续费。两者都进 stl_bill，但来源与谈判对象完全不同 ——
// 放在同一个 tab 里会让人以为改一个就够了。
//
// 同一条规矩：**只增不改**。结算按下单时刻的版本算，调整不影响已生成的账。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { PayChannelSetting, PayChannelRateVersion } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { FinanceCopy } from "./copy";

/** 万分比 → 百分比。财务说的是「0.38%」不是「38 个万分点」。 */
const pct = (bp: number) => `${(bp / 100).toFixed(2)}%`;

/** `*` 是存储值，不该直接显示给人看。 */
const anyLabel = (v: string) => (v === "*" ? "—" : v);

export function PayChannelTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const channels = useQuery({ queryKey: ["pay-channels"], queryFn: () => api.listPayChannels() });

  const [channel, setChannel] = useState("");
  const [payMethod, setPayMethod] = useState("");
  const [legalForm, setLegalForm] = useState("");
  const [rate, setRate] = useState("");
  const [minFee, setMinFee] = useState("");
  const [from, setFrom] = useState("");
  const [remark, setRemark] = useState("");

  const rows = channels.data ?? [];
  const current = channel || rows[0]?.payChannel || "";

  const addRate = useMutation({
    mutationFn: () =>
      api.addPayChannelRate(current, {
        payMethod: payMethod || undefined,
        legalForm: legalForm || undefined,
        rateBp: Number(rate),
        minFeeMinor: minFee ? Number(minFee) : undefined,
        // 留空 = 立即生效；填了就是预约生效
        effectiveFrom: from ? Date.parse(from) : undefined,
        remark: remark || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-channels"] });
      setRate("");
      setMinFee("");
      setFrom("");
      setRemark("");
      notify.success(c.pcToastAdded);
    },
  });

  const toggle = useMutation({
    mutationFn: (row: PayChannelSetting) =>
      api.updatePayChannel(row.payChannel, { enabled: !row.enabled }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-channels"] });
      notify.success(c.pcToastUpdated);
    },
  });

  const columns: Column<PayChannelSetting>[] = [
    { header: c.pcColChannel, cell: (r) => r.name || r.payChannel },
    {
      header: c.pcColState,
      cell: (r) => (r.enabled
        ? <Badge tone="success">{c.pcEnabled}</Badge>
        : <Badge tone="muted">{c.pcDisabled}</Badge>),
    },
    { header: c.pcColMarkets, cell: (r) => r.markets ?? "—" },
    { header: c.pcColCurrency, cell: (r) => r.currency ?? "—" },
    { header: c.pcColSettleCycle, cell: (r) => r.settleCycle ?? "—" },
    {
      header: c.pcColRate,
      numeric: true,
      /*
       * **「未配置」不能显示成 0%。** 后端在没有任何版本时返回 null，
       * 而 0% 会让人以为「这个通道不收手续费」——那是一句假话，
       * 真实情况是结算时根本取不到版本。
       */
      cell: (r) => (r.currentRate
        ? pct(r.currentRate.rateBp)
        : <Badge tone="warning">{c.pcNoRate}</Badge>),
    },
    {
      header: "",
      cell: (r) => (canEdit ? (
        <Button variant="ghost" onClick={() => toggle.mutate(r)} disabled={toggle.isPending}>
          {r.enabled ? c.pcDisabled : c.pcEnabled}
        </Button>
      ) : null),
    },
  ];

  const picked = rows.find((r) => r.payChannel === current);
  const rateColumns: Column<PayChannelRateVersion>[] = [
    { header: c.pcFieldPayMethod, cell: (r) => anyLabel(r.payMethod) },
    { header: c.pcFieldLegalForm, cell: (r) => anyLabel(r.legalForm) },
    { header: c.frColRate, cell: (r) => pct(r.rateBp), numeric: true },
    {
      header: c.frColEffectiveFrom,
      cell: (r) => (r.effectiveFrom === 0
        ? c.frInitial : fmtTime(new Date(r.effectiveFrom).toISOString())),
    },
    {
      header: c.frColState,
      cell: (r) => (r.effectiveFrom > Date.now()
        ? <Badge tone="warning">{c.frScheduled}</Badge>
        : <Badge tone="success">{c.frActive}</Badge>),
    },
    { header: c.frColRemark, cell: (r) => r.remark ?? "—" },
  ];

  return (
    <div className="space-y-4">
      {!canEdit && (
        <ReadOnlyNotice what={c.rateReadOnlyWhat} perm="finance:rate:update" note={c.rateReadOnlyNote} />
      )}

      <Notice tone="info">{c.pcNotice}</Notice>

      <ConfigCard title={c.pcTitle}>
        <DataTable columns={columns} rows={rows} rowKey={(r) => r.payChannel} loading={channels.isLoading} />
        <p className="mt-2 text-xs text-muted-foreground">{c.pcDisableHint}</p>
        {rows.some((r) => !r.currentRate) && (
          <p className="mt-1 text-xs text-muted-foreground">{c.pcNoRateHint}</p>
        )}
        {picked && !picked.supportsSubsidy && (
          <p className="mt-1 text-xs text-muted-foreground">{c.pcSubsidyNo}</p>
        )}
      </ConfigCard>

      {canEdit && (
        <ConfigCard title={c.pcAddRateTitle}>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <Label>{c.pcFieldChannel}</Label>
              <select
                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                value={current}
                onChange={(e) => setChannel(e.target.value)}
              >
                {rows.map((r) => (
                  <option key={r.payChannel} value={r.payChannel}>{r.name || r.payChannel}</option>
                ))}
              </select>
            </div>
            <div>
              <Label>{c.frFieldRate}</Label>
              <Input value={rate} onChange={(e) => setRate(e.target.value)} inputMode="numeric" />
            </div>
            <div>
              <Label>{c.pcFieldPayMethod}</Label>
              <Input value={payMethod} onChange={(e) => setPayMethod(e.target.value)} placeholder="JSAPI" />
            </div>
            <div>
              <Label>{c.pcFieldLegalForm}</Label>
              <Input value={legalForm} onChange={(e) => setLegalForm(e.target.value)} placeholder="ENTERPRISE" />
            </div>
            <div>
              <Label>{c.pcFieldMinFee}</Label>
              <Input value={minFee} onChange={(e) => setMinFee(e.target.value)} inputMode="numeric" />
            </div>
            <div>
              <Label>{c.frFieldFrom}</Label>
              <Input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="sm:col-span-2">
              <Label>{c.frFieldRemark}</Label>
              <Input value={remark} onChange={(e) => setRemark(e.target.value)} />
            </div>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{c.pcAnyHint}</p>
          <p className="mt-1 text-xs text-muted-foreground">{c.frFromHint}</p>
          <div className="mt-3">
            <Button onClick={() => addRate.mutate()} disabled={!rate || addRate.isPending}>
              {addRate.isPending ? c.frAdding : c.frAdd}
            </Button>
          </div>
        </ConfigCard>
      )}

      {picked && (
        <ConfigCard title={fill(c.pcHistoryTitle, { ch: picked.name || picked.payChannel, n: picked.rates.length })}>
          <DataTable columns={rateColumns} rows={picked.rates} rowKey={(r) => r.rateNo} />
        </ConfigCard>
      )}
    </div>
  );
}
