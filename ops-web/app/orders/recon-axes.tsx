"use client";

// 四条对账轴的总览。
//
// **这一屏要回答的不是「今天有没有差异」，是「哪一类对不上、以及哪一类根本没在看」。**
//
// ⚠️ 两处必须分得开，因为它们在页面上长得一样、含义却完全相反：
//   · **零差异** vs **这条轴今天没跑成**（error 非空）
//   · **零差异** vs **这条轴查不到那一类**（coverage.complete = false）
//
// 四条轴今天都只有 A 侧（我方自查）—— 渠道账单、分账查询、银行流水三种外部数据
// 一个都没接。所以「今天没有差异」对四条轴都是假话，而这一屏的职责就是把这句话说出来。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ReconAxisReport } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import type { OrdersCopy } from "./copy";

export function ReconAxes({ c }: { c: OrdersCopy }) {
  const axes = useQuery({ queryKey: ["recon-axes"], queryFn: () => api.reconAxes() });
  const rows = axes.data ?? [];

  if (axes.isLoading) {
    return <div className="text-[13px] text-muted-foreground">{c.axesLoading}</div>;
  }
  if (!rows.length) return null;

  const broken = rows.filter((r) => r.error);

  return (
    <div className="mb-4 space-y-3">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.axesTitle}</h3>
        <span className="text-[12px] text-muted-foreground">{c.axesSubtitle}</span>
      </div>

      {/*
        有轴没跑成时先把它顶到最上面。**一条没跑成的轴等于今天这一类没人看** ——
        而它在下面的卡片里只是一个小标签，扫一眼很容易漏掉。
      */}
      {broken.length > 0 && (
        <Notice tone="danger">
          {c.axesBroken.replace("{axes}", broken.map((r) => c[`axisName_${r.axis}` as keyof OrdersCopy] ?? r.axis).join("、"))}
        </Notice>
      )}

      <div className="grid gap-2 sm:grid-cols-2">
        {rows.map((r) => <AxisCard key={r.axis} c={c} r={r} />)}
      </div>
    </div>
  );
}

function AxisCard({ c, r }: { c: OrdersCopy; r: ReconAxisReport }) {
  const name = (c[`axisName_${r.axis}` as keyof OrdersCopy] as string) ?? r.axis;
  return (
    <div className={`rounded-card border p-3 ${r.error ? "border-destructive bg-destructive-tint/20" : "border-border"}`}>
      <div className="flex items-baseline justify-between gap-2">
        <span className="font-semibold">{name}</span>
        {r.error
          // ⚠️ 「没跑成」不能只是灰掉 —— 它比「有差异」更该被处理
          ? <Badge tone="danger">{c.axisFailed}</Badge>
          : r.outcome && r.outcome.opened > 0
            ? <Badge tone="warning">{c.axisOpened.replace("{n}", String(r.outcome.opened))}</Badge>
            : <Badge tone="muted">{c.axisClean}</Badge>}
      </div>

      {r.outcome && (
        <div className="mt-1 text-[12px] tabular-nums text-muted-foreground">
          {c.axisCounts
            .replace("{scanned}", String(r.outcome.scanned))
            .replace("{resolved}", String(r.outcome.resolved))
            .replace("{deferred}", String(r.outcome.deferred))}
        </div>
      )}

      {r.error && <div className="mt-1 font-mono text-[11px] text-destructive-text">{r.error}</div>}

      {/*
        覆盖范围**永远显示**，不折叠、不藏在 tooltip 里。
        藏起来的话，读的人看到「零差异」就走了 —— 而那正是要防的。
      */}
      {!r.coverage.complete && (
        <div className="mt-2 border-t border-border pt-2 text-[12px] leading-[1.55] text-muted-foreground">
          {r.coverage.note}
        </div>
      )}
    </div>
  );
}
