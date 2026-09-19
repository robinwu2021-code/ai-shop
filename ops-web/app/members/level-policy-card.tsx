"use client";

// 会员分层口径（原型 o02）。挂在「触达健康度」tab 顶部，不另开菜单叶子：
// 它回答的是「商家那边的『沉睡 24 人』是怎么算出来的」，与触达是同一件事的上游。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { MEMBERS_COPY } from "./copy";

type Copy = (typeof MEMBERS_COPY)["zh"];
type Form = { sleepDays: string; loyalD90Orders: string; regularD90Orders: string };

const KEY = ["ops-member-level-policy"];

export function LevelPolicyCard({ c }: { c: Copy }) {
  const qc = useQueryClient();
  // 改它要平台参数权限：口径一改，全平台所有商家的「沉睡」人数跟着变
  const canEdit = useCan()("system:param:update");
  const policy = useQuery({ queryKey: KEY, queryFn: () => api.getLevelPolicy() });
  const [form, setForm] = useState<Form | null>(null);

  const save = useMutation({
    mutationFn: () => {
      const v = {
        sleepDays: Number(form!.sleepDays),
        loyalD90Orders: Number(form!.loyalD90Orders),
        regularD90Orders: Number(form!.regularD90Orders),
      };
      // 前端先挡一次，文案说清楚为什么 —— 后端同样会拒，但只回一个 400
      const ok = v.sleepDays >= 7 && v.sleepDays <= 365 && v.regularD90Orders >= 1
        && v.loyalD90Orders <= 99 && v.regularD90Orders < v.loyalD90Orders;
      return ok ? api.saveLevelPolicy(v) : Promise.reject(new Error(c.lvInvalid));
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: KEY }); setForm(null); notify.success(c.lvSaved); },
  });

  const p = policy.data;
  const run = p?.lastRun;

  return (
    <Card>
      <CardHeader><CardTitle>{c.lvTitle}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {form ? (
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <Label htmlFor="lv-sleep">{c.lvSleep}</Label>
              <Input id="lv-sleep" type="number" className="w-24" value={form.sleepDays}
                onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, sleepDays: v }); }} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="lv-loyal">{c.lvLoyal}</Label>
              <Input id="lv-loyal" type="number" className="w-24" value={form.loyalD90Orders}
                onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, loyalD90Orders: v }); }} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="lv-regular">{c.lvRegular}</Label>
              <Input id="lv-regular" type="number" className="w-24" value={form.regularD90Orders}
                onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, regularD90Orders: v }); }} />
            </div>
            <Button size="sm" loading={save.isPending} onClick={() => save.mutate()}>{c.lvSave}</Button>
            <Button size="sm" variant="ghost" onClick={() => setForm(null)}>{c.lvCancel}</Button>
          </div>
        ) : (
          <div className="flex items-center justify-between gap-3">
            <span className="txt-body">
              {p ? fill(c.lvSummary, { s: p.sleepDays, l: p.loyalD90Orders, r: p.regularD90Orders }) : ""}
            </span>
            {canEdit && p && (
              <Button size="sm" variant="outline" onClick={() => setForm({
                sleepDays: String(p.sleepDays),
                loyalD90Orders: String(p.loyalD90Orders),
                regularD90Orders: String(p.regularD90Orders),
              })}>{c.lvEdit}</Button>
            )}
          </div>
        )}
        {p && (
          <p className="txt-caption text-muted-foreground">
            {run
              ? fill(c.lvLastRun, { t: fmtTime(run.at), n: run.changed, z: run.newlySleeping, ms: run.tookMs })
              : c.lvNeverRun}
          </p>
        )}
        <p className="txt-caption text-muted-foreground">{c.lvHint}</p>
      </CardContent>
    </Card>
  );
}
