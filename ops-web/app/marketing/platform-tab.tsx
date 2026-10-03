"use client";

// 平台活动（原型 s29 · 详细设计 §1.6）。
//
// 规则部分与商家活动**同一个模型**：玩法、门槛、减多少、活动时间。
// 多出来的只有三样 —— **出资**（全额 / 一半 / 不出）、**预算**、**报名门槛**。
// 发布之后商家已经照着这套规则报名、算过「最多承担」：后端只放开报名截止与预算，这里同样只放开这两项。
//
// 现只收减钱类玩法（满减 / 满件减 / 立减）：改单价类（秒杀 / 特价）的平台补贴要跟着单价走，
// 算价那一侧还没有把它落进平台出资 —— 放出去的话平台那份钱没人记账。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import { notify } from "@/lib/notify";
import { money } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import type { OpsPlatformActivity, OpsPlatformDraft } from "@/lib/types";
import type { MarketingCopy } from "./copy";

interface Form {
  activityNo?: string;
  published: boolean;
  name: string;
  triggerType: "AMOUNT" | "QTY" | "NONE";
  threshold: string;
  cut: string;
  startDay: string;
  endDay: string;
  deadlineDay: string;
  shareBp: number;
  budget: string;
  minRating: string;
  noViolation: boolean;
  categories: string;
  cities: string;
}

function day(ms: number | null | undefined, offsetDays = 0): string {
  const d = new Date((ms ?? Date.now()) + offsetDays * 86_400_000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const EMPTY: Form = {
  published: false, name: "", triggerType: "AMOUNT", threshold: "99", cut: "20",
  startDay: day(null, 7), endDay: day(null, 17), deadlineDay: day(null, 3), shareBp: 5000, budget: "50000",
  minRating: "4.5", noViolation: true, categories: "", cities: "",
};

function toForm(a: OpsPlatformActivity): Form {
  return {
    activityNo: a.activityNo, published: a.status !== "DRAFT", name: a.name,
    triggerType: (a.triggerType as Form["triggerType"]) || "NONE",
    threshold: a.triggerType === "QTY" ? String(a.triggerQty ?? "") : ((a.triggerAmountMinor ?? 0) / 100).toString(),
    cut: ((a.benefitAmountMinor ?? 0) / 100).toString(),
    startDay: day(a.startAt), endDay: day(a.endAt), deadlineDay: day(a.enrollDeadline),
    shareBp: a.platformShareBp, budget: a.budgetMinor == null ? "" : (a.budgetMinor / 100).toString(),
    minRating: a.enrollRule.minRating == null ? "" : String(a.enrollRule.minRating),
    noViolation: a.enrollRule.noViolation,
    categories: a.enrollRule.categoryNos.join(","), cities: a.enrollRule.cityCodes.join(","),
  };
}

const list = (s: string) => s.split(/[,，]/).map((x) => x.trim()).filter(Boolean);

function toDraft(f: Form, publish: boolean): OpsPlatformDraft {
  return {
    activityNo: f.activityNo,
    name: f.name.trim(),
    triggerType: f.triggerType,
    triggerAmountMinor: f.triggerType === "AMOUNT" ? Math.round(Number(f.threshold) * 100) : null,
    triggerQty: f.triggerType === "QTY" ? Number(f.threshold) : null,
    benefitAmountMinor: Math.round(Number(f.cut) * 100),
    startAt: new Date(`${f.startDay}T00:00:00`).getTime(),
    endAt: new Date(`${f.endDay}T23:59:59`).getTime(),
    enrollDeadline: new Date(`${f.deadlineDay}T23:59:59`).getTime(),
    platformShareBp: f.shareBp,
    budgetMinor: f.budget ? Math.round(Number(f.budget) * 100) : null,
    enrollRule: {
      minRating: f.minRating ? Number(f.minRating) : null,
      noViolation: f.noViolation,
      categoryNos: list(f.categories),
      cityCodes: list(f.cities),
    },
    publish,
  };
}

export function ruleText(c: MarketingCopy, a: Pick<OpsPlatformActivity, "triggerType" | "triggerAmountMinor" | "triggerQty" | "benefitAmountMinor">): string {
  const n = money(a.benefitAmountMinor ?? 0);
  if (a.triggerType === "AMOUNT") return fill(c.platformRuleAmount, { m: money(a.triggerAmountMinor ?? 0), n });
  if (a.triggerType === "QTY") return fill(c.platformRuleQty, { m: String(a.triggerQty ?? 0), n });
  return fill(c.platformRuleAny, { n });
}

export function PlatformTab({ c, canEdit }: { c: MarketingCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<Form | null>(null);

  const acts = useQuery({ queryKey: ["platform-activities"], queryFn: () => api.listPlatformActivities() });

  const save = useMutation({
    mutationFn: ({ f, publish }: { f: Form; publish: boolean }) => api.savePlatformActivity(toDraft(f, publish)),
    onSuccess: () => {
      notify.success(c.platformSaved);
      setForm(null);
      void qc.invalidateQueries({ queryKey: ["platform-activities"] });
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const shareLabel = (bp: number) =>
    bp >= 10000 ? c.platformShareAll : bp >= 5000 ? c.platformShareHalf : c.platformShareNone;

  const cols: Column<OpsPlatformActivity>[] = [
    { header: c.platformColName, cell: (a) => a.name },
    { header: c.platformColRule, cell: (a) => ruleText(c, a) },
    { header: c.platformColTime, cell: (a) => `${day(a.startAt)} ~ ${day(a.endAt)}` },
    { header: c.platformColDeadline, cell: (a) => day(a.enrollDeadline) },
    { header: c.platformColShare, cell: (a) => shareLabel(a.platformShareBp) },
    { header: c.platformColBudget, numeric: true,
      cell: (a) => (a.budgetMinor == null ? "—" : `${money(a.reservedMinor)} / ${money(a.budgetMinor)}`) },
    { header: c.platformColEnroll, cell: (a) => fill(c.platformEnrollCounts, { s: String(a.submitted), a: String(a.approved), r: String(a.rejected) }) },
    { header: c.platformColStatus, cell: (a) => (
      <Badge tone={a.status === "RUNNING" ? "success" : a.status === "DRAFT" ? "default" : "muted"}>
        {(c as unknown as Record<string, string>)["platformStatus_" + a.status] ?? a.status}
      </Badge>
    ) },
    { header: "", cell: (a) => (canEdit && a.status !== "ENDED"
      ? <Button size="sm" variant="outline" onClick={() => setForm(toForm(a))}>{c.platformEdit}</Button>
      : null) },
  ];

  const f = form;
  const set = (patch: Partial<Form>) => setForm((x) => (x ? { ...x, ...patch } : x));
  const locked = !!f?.published;

  return (
    <div className="space-y-3">
      <Notice>{c.platformNotice}</Notice>
      {canEdit && (
        <div className="flex justify-end">
          <Button onClick={() => setForm({ ...EMPTY })}>{c.platformNew}</Button>
        </div>
      )}
      <DataTable columns={cols} rows={acts.data ?? []} rowKey={(a) => a.activityNo}
        loading={acts.isLoading} error={acts.error} onRetry={() => acts.refetch()} empty={c.platformEmpty} />

      {f && (
        <Drawer
          open
          onOpenChange={(o) => !o && setForm(null)}
          title={f.activityNo ? c.platformEdit : c.platformNew}
          desc={f.activityNo}
          footer={
            <div className="flex gap-2">
              {!locked && (
                <Button variant="outline" loading={save.isPending} onClick={() => save.mutate({ f, publish: false })}>
                  {c.platformSaveDraft}
                </Button>
              )}
              <Button loading={save.isPending} onClick={() => save.mutate({ f, publish: true })}>{c.platformPublish}</Button>
            </div>
          }
        >
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="pa-name" required>{c.platformName}</Label>
              <Input id="pa-name" className="w-full" value={f.name} disabled={locked} maxLength={64}
                onChange={(e) => set({ name: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pa-play">{c.platformPlay}</Label>
              <Select id="pa-play" className="w-full" value={f.triggerType} disabled={locked}
                onChange={(e) => set({ triggerType: e.target.value as Form["triggerType"] })}>
                <option value="AMOUNT">{c.platformPlayAMOUNT}</option>
                <option value="QTY">{c.platformPlayQTY}</option>
                <option value="NONE">{c.platformPlayNONE}</option>
              </Select>
            </div>
            <div className="grid grid-cols-2 gap-3">
              {f.triggerType !== "NONE" && (
                <div className="space-y-1">
                  <Label htmlFor="pa-th" required>{f.triggerType === "QTY" ? c.platformThresholdQty : c.platformThreshold}</Label>
                  <Input id="pa-th" className="w-full" inputMode="decimal" value={f.threshold} disabled={locked} maxLength={10}
                    onChange={(e) => set({ threshold: e.target.value })} />
                </div>
              )}
              <div className="space-y-1">
                <Label htmlFor="pa-cut" required>{c.platformCut}</Label>
                <Input id="pa-cut" className="w-full" inputMode="decimal" value={f.cut} disabled={locked} maxLength={10}
                  onChange={(e) => set({ cut: e.target.value })} />
              </div>
            </div>
            {/* 两列：抽屉宽度下三列放不下「2026/09/26 + 日历图标」，年份会被截掉 */}
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="pa-start" required>{c.platformStart}</Label>
                <Input id="pa-start" type="date" className="w-full" value={f.startDay} disabled={locked}
                  onChange={(e) => set({ startDay: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="pa-end" required>{c.platformEnd}</Label>
                <Input id="pa-end" type="date" className="w-full" value={f.endDay} disabled={locked}
                  onChange={(e) => set({ endDay: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label htmlFor="pa-dl" required>{c.platformDeadline}</Label>
                <Input id="pa-dl" type="date" className="w-full" value={f.deadlineDay}
                  onChange={(e) => set({ deadlineDay: e.target.value })} />
              </div>
            </div>
            <div className="space-y-1">
              <Label>{c.platformShare}</Label>
              <div className="flex gap-2">
                {[10000, 5000, 0].map((bp) => (
                  <Button key={bp} size="sm" variant={f.shareBp === bp ? "default" : "outline"} disabled={locked}
                    onClick={() => set({ shareBp: bp })}>{shareLabel(bp)}</Button>
                ))}
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="pa-budget" required={f.shareBp > 0}>{c.platformBudget}</Label>
              <Input id="pa-budget" className="w-full" inputMode="decimal" value={f.budget} maxLength={12}
                onChange={(e) => set({ budget: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label>{c.platformRule}</Label>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="pa-rating">{c.platformMinRating}</Label>
                  <Input id="pa-rating" className="w-full" inputMode="decimal" value={f.minRating} disabled={locked} maxLength={4}
                    onChange={(e) => set({ minRating: e.target.value })} />
                </div>
                <label className="flex items-center gap-2 pt-6">
                  <Switch checked={f.noViolation} disabled={locked} onChange={(v) => set({ noViolation: v })} />
                  <span>{c.platformNoViolation}</span>
                </label>
              </div>
              <div className="space-y-1">
                <Label htmlFor="pa-cat">{c.platformCategories}</Label>
                <Input id="pa-cat" className="w-full" value={f.categories} disabled={locked} maxLength={200}
                  onChange={(e) => set({ categories: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="pa-city">{c.platformCities}</Label>
                <Input id="pa-city" className="w-full" value={f.cities} disabled={locked} maxLength={200}
                  onChange={(e) => set({ cities: e.target.value })} />
              </div>
            </div>
          </div>
        </Drawer>
      )}
    </div>
  );
}
