"use client";

// 费率（后端 stl_fee_rule）。
//
// 这一块此前是**纯 mock**：契约是一维（只按流量来源）、单值、原地改，
// 而后端从未实现过它 —— 守卫清单里 `fee-rule` 一直挂在「整域未开工」。
// 现在后端落地的形状不同，页面跟着改成真实形状：
//
//   · **二维**：经营模式 × 流量来源。两者正交，只按经营模式分档，
//     等哪天要给自营也区分客流就得改表结构，而费率表最不该改结构。
//   · **只增不改**：调费率是插一个新版本，旧版本永久保留。原地改只能回答
//     「现在是多少」，而真正会被问到的是「上个月那批单当时按什么费率算的」。
//
// 所以这个页面没有「保存」，只有「新增版本」，外加一张历史表。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { BusinessMode, FeeTrafficSource, FeeRuleVersion } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { FinanceCopy } from "./copy";

/** 费率以万分比存，展示成百分比 —— 财务说的是「5%」不是「500 个万分点」。 */
const pct = (bp: number) => `${(bp / 100).toFixed(2)}%`;

const MODES: BusinessMode[] = ["THIRD_PARTY", "SELF_OPERATED"];
const SOURCES: FeeTrafficSource[] = ["MERCHANT_OWNED", "PLATFORM"];

export function FeeRuleTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const rules = useQuery({ queryKey: ["fee-rules"], queryFn: () => api.listFeeRules() });
  const effective = useQuery({ queryKey: ["fee-rates-now"], queryFn: () => api.effectiveFeeRates() });

  const [mode, setMode] = useState<BusinessMode>("THIRD_PARTY");
  const [source, setSource] = useState<FeeTrafficSource>("PLATFORM");
  const [rate, setRate] = useState("");
  const [from, setFrom] = useState("");
  const [remark, setRemark] = useState("");

  const add = useMutation({
    mutationFn: () =>
      api.addFeeRule({
        businessMode: mode,
        trafficSource: source,
        rateBp: Number(rate),
        // 留空 = 立即生效；填了就是预约生效
        effectiveFrom: from ? Date.parse(from) : undefined,
        remark: remark || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fee-rules"] });
      qc.invalidateQueries({ queryKey: ["fee-rates-now"] });
      setRate("");
      setFrom("");
      setRemark("");
      notify.success(c.frToastAdded);
    },
  });

  const modeLabel: Record<BusinessMode, string> = {
    SELF_OPERATED: c.frModeSelfOperated,
    THIRD_PARTY: c.frModeThirdParty,
  };
  const sourceLabel: Record<FeeTrafficSource, string> = {
    MERCHANT_OWNED: c.trafficMerchantOwned,
    PLATFORM: c.trafficPlatform,
  };

  const columns: Column<FeeRuleVersion>[] = [
    { header: c.frColMode, cell: (r) => modeLabel[r.businessMode] },
    { header: c.frColSource, cell: (r) => sourceLabel[r.trafficSource] },
    { header: c.frColRate, cell: (r) => pct(r.rateBp), numeric: true },
    {
      header: c.frColEffectiveFrom,
      // 0 = 初始版本（「自古以来」），显示时间戳没有意义
      cell: (r) => (r.effectiveFrom === 0 ? c.frInitial : fmtTime(new Date(r.effectiveFrom).toISOString())),
    },
    {
      header: c.frColState,
      cell: (r) =>
        r.enabled !== 1 ? <Badge tone="muted">{c.frDisabled}</Badge>
          : r.effectiveFrom > Date.now() ? <Badge tone="warning">{c.frScheduled}</Badge>
            : <Badge tone="success">{c.frActive}</Badge>,
    },
    { header: c.frColRemark, cell: (r) => r.remark ?? "—" },
  ];

  return (
    <div className="space-y-4">
      {!canEdit && (
        <ReadOnlyNotice what={c.rateReadOnlyWhat} perm="finance:rate:update" note={c.rateReadOnlyNote} />
      )}

      <Notice tone="info">{c.frNotice}</Notice>

      <ConfigCard title={c.frEffectiveTitle}>
        <div className="grid grid-cols-2 gap-3">
          {MODES.flatMap((m) =>
            SOURCES.map((s) => (
              <div key={`${m}|${s}`} className="rounded-md border p-3">
                <div className="txt-caption text-muted-foreground">
                  {modeLabel[m]} · {sourceLabel[s]}
                </div>
                <div className="txt-strong text-lg">
                  {pct(effective.data?.[`${m}|${s}`] ?? 0)}
                </div>
              </div>
            )),
          )}
        </div>
        <p className="txt-caption text-muted-foreground mt-3">{c.frEffectiveHint}</p>
      </ConfigCard>

      {canEdit && (
        <ConfigCard title={c.frAddTitle}>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label>{c.frColMode}</Label>
              <FilterSelect
                aria-label={c.frColMode} value={mode} onChange={(v) => setMode(v as BusinessMode)}
                options={MODES.map((m) => ({ value: m, label: modeLabel[m] }))}
              />
            </div>
            <div className="space-y-1">
              <Label>{c.frColSource}</Label>
              <FilterSelect
                aria-label={c.frColSource} value={source}
                onChange={(v) => setSource(v as FeeTrafficSource)}
                options={SOURCES.map((s) => ({ value: s, label: sourceLabel[s] }))}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="fr-rate" required>{c.frFieldRate}</Label>
              <Input id="fr-rate" value={rate} onChange={(e) => setRate(e.target.value)} />
              <p className="txt-caption text-muted-foreground">= {pct(Number(rate) || 0)}</p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="fr-from">{c.frFieldFrom}</Label>
              <Input id="fr-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
              <p className="txt-caption text-muted-foreground">{c.frFromHint}</p>
            </div>
            <div className="space-y-1 col-span-2">
              <Label htmlFor="fr-remark">{c.frFieldRemark}</Label>
              <Input id="fr-remark" value={remark} onChange={(e) => setRemark(e.target.value)} />
              <p className="txt-caption text-muted-foreground">{c.frRemarkHint}</p>
            </div>
          </div>
          <div className="mt-3">
            <Button onClick={() => add.mutate()} disabled={!rate || add.isPending}>
              {add.isPending ? c.frAdding : c.frAdd}
            </Button>
          </div>
        </ConfigCard>
      )}

      <div>
        <div className="mb-2 txt-strong">{fill(c.frHistoryTitle, { n: rules.data?.length ?? 0 })}</div>
        <DataTable columns={columns} rows={rules.data ?? []} rowKey={(r) => r.ruleNo} />
      </div>
    </div>
  );
}
