"use client";

// 单商品全链路状态（M5）。
//
// 「审核到哪了、建账了吗、有库存吗、卖了多少」此前要在四个页面之间跳着看，
// 而它们各自的主键还不一样（商品池按 goodsNo、库存流水按 ownerId + itemId）。
// 这个抽屉把那四跳收成一次请求。
//
// **卡点用词与「链条画像」同一套** —— 那一页答「今天该找哪家商家」，
// 这里答「这一件货现在到底怎么了」。两处分叉就是两套结论。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/misc";
import type { ProductsCopy } from "./copy";

export function GoodsChainDrawer({ c, goodsNo, onClose }: {
  c: ProductsCopy;
  goodsNo: string | null;
  onClose: () => void;
}) {
  const q = useQuery({
    queryKey: ["goods-chain", goodsNo],
    queryFn: () => api.goodsChain(goodsNo!),
    enabled: !!goodsNo,
  });
  const d = q.data;

  return (
    <Drawer open={!!goodsNo} onOpenChange={(o) => !o && onClose()}
            title={d?.title ?? goodsNo ?? ""} desc={goodsNo ?? undefined}>
      {q.isPending && <Skeleton className="h-40" />}
      {/* 取不到要说取不到：一个空抽屉与「这件货什么都没有」长得一样 */}
      {q.error != null && !q.isPending && (
        <p className="txt-body text-[var(--destructive-ink)]">{c.chainLoadFailed}</p>
      )}
      {d && (
        <>
          <DrawerSection first title={c.chainSecState}>
            <Field label={c.chainAudit}>{d.auditStatus ?? "—"}</Field>
            <Field label={c.chainOnSale}>{d.onSale ? c.chainYes : c.chainNo}</Field>
            <Field label={c.chainStuck}>
              {d.stuckAt
                ? <Badge tone={d.stuckAt === "IN_AUDIT" ? "warning" : "danger"}>{stuckLabel(d.stuckAt, c)}</Badge>
                : <span className="text-[var(--success-ink)]">{c.chainClear}</span>}
            </Field>
          </DrawerSection>

          <DrawerSection title={c.chainSecAccount}>
            {/*
              * 建账数与规格数**并排给**：bookedSkus < skuCount 是「投影只搬过去一半」，
              * 而它在商家端的表现是「有些规格盘得着、有些盘不着」，极难自查。
              * 只给一个「已建账」的对勾，这种情况就永远看不见了。
              */}
            <Field label={c.chainSkus}>{d.skuCount}</Field>
            <Field label={c.chainBooked}>
              <span className="tabular-nums">{d.bookedSkus} / {d.skuCount}</span>
              {d.bookedSkus < d.skuCount && (
                <Badge tone="danger" className="ms-2">{c.chainPartial}</Badge>
              )}
            </Field>
          </DrawerSection>

          <DrawerSection title={c.chainSecStock}>
            <Field label={c.chainOnHand}>{d.onHand}</Field>
            <Field label={c.chainAvailable}>{d.available}</Field>
            <Field label={c.chainSold}>{d.soldCount}</Field>
          </DrawerSection>
        </>
      )}
    </Drawer>
  );
}

/**
 * 卡点的中文名。**不给的话界面上直接显示 `NO_ACCOUNT` 这种原始枚举** ——
 * 这个仓库刚为同一类问题修过一次（单据列表把 SCRAP 当文案显示给商家看）。
 *
 * 认不出的码原样显示：后端将来加一档新枚举时，页面上出现一个陌生的词
 * 好过什么都不显示 —— 前者看得出「有新东西没接」，后者看起来像没问题。
 */
function stuckLabel(s: string, c: ProductsCopy): string {
  const map: Record<string, string> = {
    NO_GOODS: c.chainStuckNoGoods,
    IN_AUDIT: c.chainStuckInAudit,
    NOT_ON_SALE: c.chainStuckNotOnSale,
    NO_ACCOUNT: c.chainStuckNoAccount,
    NO_INBOUND: c.chainStuckNoInbound,
    STALE_LEDGER: c.chainStuckStaleLedger,
  };
  return map[s] ?? s;
}
