"use client";

// 商品域平台统计（M4）。
//
// **此前这个域一个统计数字都没有**，而商品是这个平台的主体：骨架（类目、规格库、
// 标准品）画得最全，却答不出「画的这些骨架有多少真的被用上了」。
//
// 每一格都给**分子/分母**而不是只给百分比：73 个类目用了 14 个，与
// 7300 个用了 1400 个，同样是 19%，但该做的事完全不同。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import type { ProductStats } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HelpNote } from "@/components/ui/help-note";
import { Skeleton } from "@/components/ui/misc";
import type { ProductsCopy } from "./copy";

export function StatsTab({ c }: { c: ProductsCopy }) {
  const q = useQuery({ queryKey: ["product-stats"], queryFn: () => api.productStats({ days: 7 }) });

  if (q.error != null && !q.isPending) {
    return (
      <Card><CardContent className="txt-body text-[var(--destructive-ink)]">{c.statsLoadFailed}</CardContent></Card>
    );
  }

  return (
    <>
      <HelpNote title={c.statsHelpTitle}>{c.statsHelp}</HelpNote>

      <div className="grid gap-4 lg:grid-cols-2">
        <RatioCard title={c.statsCategoryTitle} note={c.statsCategoryNote}
          used={q.data?.categoriesUsed} total={q.data?.categories}
          usedLabel={c.statsCategoryUsed} loading={q.isPending} c={c} />

        <RatioCard title={c.statsBarcodeTitle} note={c.statsBarcodeNote}
          used={q.data?.skusWithBarcode} total={q.data?.skus}
          usedLabel={c.statsBarcodeUsed} loading={q.isPending} c={c}
          extra={q.data && (
            <Line label={c.statsCodeUsed} value={fill(c.statsOfN,
              { n: q.data.skusWithCode, total: q.data.skus })} />
          )} />

        <RatioCard title={c.statsSpecTitle} note={c.statsSpecNote}
          used={q.data?.specDimsBound} total={q.data?.specDims}
          usedLabel={c.statsSpecUsed} loading={q.isPending} c={c} />

        <Card>
          <CardHeader><CardTitle>{c.statsAuditTitle}</CardTitle></CardHeader>
          <CardContent className="grid gap-2">
            {q.isPending ? <Skeleton className="h-20" /> : q.data && (
              <>
                {/*
                  * 通过率的分母只算「审完的」—— 把待审算进去，194 件积压会把
                  * 通过率压到 2%，而那说的是积压不是质量，两件事混在一个数里
                  */}
                <Line label={c.statsPassRate}
                  value={passRate(q.data) ?? c.statsPassRateNone} strong />
                <Line label={c.statsApproved} value={q.data.auditApproved} />
                <Line label={c.statsRejected} value={q.data.auditRejected} />
                <Line label={c.statsPending} value={q.data.auditPending} />
                {/* 吞吐与上面三个累计数不是一回事：那是「现在是什么状态」，这是「最近做了多少」 */}
                <Line label={fill(c.statsThroughput, { n: q.data.auditDays })}
                  value={q.data.auditActions} />
                <p className="mt-1 txt-caption text-muted-foreground">{c.statsAuditNote}</p>
              </>
            )}
          </CardContent>
        </Card>
      </div>
    </>
  );
}

/** 审完的里面通过了多少。一件都没审完时返回 null —— 0% 会被读成「全被驳回了」 */
function passRate(s: ProductStats): string | null {
  const done = s.auditApproved + s.auditRejected;
  return done === 0 ? null : `${Math.round((s.auditApproved * 100) / done)}%`;
}

function RatioCard({ title, note, used, total, usedLabel, loading, extra, c }: {
  title: string;
  note: string;
  used?: number;
  total?: number;
  usedLabel: string;
  loading: boolean;
  extra?: React.ReactNode;
  c: ProductsCopy;
}) {
  return (
    <Card>
      <CardHeader><CardTitle>{title}</CardTitle></CardHeader>
      <CardContent className="grid gap-2">
        {loading ? <Skeleton className="h-20" /> : (
          <>
            {/* 百分比给个大字，但分子分母一定同时在 —— 只有比率答不出该做什么 */}
            <div className="txt-display tabular-nums">
              {total ? `${Math.round(((used ?? 0) * 100) / total)}%` : "—"}
            </div>
            <Line label={usedLabel} value={fill(c.statsOfN, { n: used ?? 0, total: total ?? 0 })} />
            {extra}
            <p className="mt-1 txt-caption text-muted-foreground">{note}</p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Line({ label, value, strong }: {
  label: string; value: React.ReactNode; strong?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="txt-body text-muted-foreground">{label}</span>
      <span className={strong ? "txt-title tabular-nums" : "txt-body tabular-nums"}>{value}</span>
    </div>
  );
}
