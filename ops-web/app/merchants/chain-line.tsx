"use client";

// 商家档案抽屉里的「链条一行」（M5，商家全景的另一半）。
//
// 那个抽屉原先覆盖主体、履约、人员、门店，**唯独缺商品与进销存那一段** ——
// 而缺口清单里「一家商家的全景」说的正是那件事：今天要在四个档案 + 商品池 +
// 库存流水之间跳，各自的主键还不一样。
//
// **不另开接口**：M1 的链条画像已经把这六个数算好了，这里按商家号挑出那一行。
// 另算一遍的话，全景与画像迟早对同一家给出两种说法。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Field } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/misc";
import Link from "next/link";
import type { MerchantsCopy } from "./copy";

export function MerchantChainLine({ merchantNo, c }: { merchantNo: string; c: MerchantsCopy }) {
  const q = useQuery({
    queryKey: ["merchant-chain", "all"],
    queryFn: () => api.merchantChain({ limit: 500 }),
  });
  const row = q.data?.find((r) => r.entityNo === merchantNo);

  if (q.isPending) {
    return <Field label={c.chainLineLabel}><Skeleton className="h-5 w-48" /></Field>;
  }
  // 画像里没有这一家：可能刚建、也可能被数据域挡住 —— 都不该显示成一串 0
  if (!row) {
    return <Field label={c.chainLineLabel}><span className="text-muted-foreground">—</span></Field>;
  }

  return (
    <Field label={c.chainLineLabel}>
      <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Num label={c.chainGoods} v={row.goods} />
        <Num label={c.chainPending} v={row.pendingAudit} />
        <Num label={c.chainOnSale} v={row.onSale} />
        <Num label={c.chainItems} v={row.items} />
        {row.stuckAt
          ? <Badge tone={row.stuckAt === "IN_AUDIT" ? "warning" : "danger"}>{stuckLabel(row.stuckAt, c)}</Badge>
          : <span className="txt-caption text-[var(--success-ink)]">{c.chainHealthy}</span>}
        {/* 给一条过去的路：全景只说「卡在哪」，处置在画像那一页 */}
        <Link href="/merchants?tab=chain" className="txt-caption underline">{c.chainLineMore}</Link>
      </span>
    </Field>
  );
}

function Num({ label, v }: { label: string; v: number }) {
  return (
    <span className="txt-caption">
      <span className="text-muted-foreground">{label}</span>
      <span className="ms-1 tabular-nums">{v}</span>
    </span>
  );
}

/** 与链条画像用同一批词条 —— 同一件事在两处不能有两个说法 */
function stuckLabel(s: string, c: MerchantsCopy): string {
  const map: Record<string, string> = {
    NO_GOODS: c.chainNoGoods, IN_AUDIT: c.chainInAudit, NOT_ON_SALE: c.chainNotOnSale,
    NO_ACCOUNT: c.chainNoAccount, NO_INBOUND: c.chainNoInbound, STALE_LEDGER: c.chainStaleLedger,
  };
  return map[s] ?? s;
}
