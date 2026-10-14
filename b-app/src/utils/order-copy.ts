import type { Order } from "@shared/types";
import { datetime } from "@shared/utils/datetime";
import type { ComposerTranslation } from "vue-i18n";

/**
 * 把一笔订单拼成**发给供应商**的纯文本，供 B 端「复制订单信息」用
 * （TDD-虹选鲜果运营落地 §5）。
 *
 * <p>给供应商的是**拣货发货**要素：商品 + 数量 + 收件人 + 地址。
 * **刻意不含售价 / 金额** —— 那是商家的毛利，复制给供应商等于把定价漏出去。
 * 要给价的场景（代销对账）是另一件事，到时另拼，不在这里混。
 *
 * <p>字段取订单详情里的**快照**，不另查，与页面所见一致（含手机号的脱敏程度，
 * 后端已按履约方式定好，端上不二次打码）。
 *
 * <p><b>没有的段跳过，不显示「null」</b>：自提单没有收件人，收货/地址两行不出现。
 * 赠品也要发货，所以包含在内并标「赠品」，否则供应商会漏发。
 */
export function buildOrderCopyText(order: Order, t: ComposerTranslation): string {
  const lines: string[] = [
    `${t("order.no")}: ${order.orderNo}`,
    `${t("order.createdAt")}: ${datetime(order.createdAt)}`,
  ];
  const r = order.receiver;
  const who = [r?.name, r?.phone].filter(Boolean).join(" ");
  if (who) {
    lines.push(`${t("order.receiver")}: ${who}`);
  }
  if (r?.address) {
    lines.push(`${t("order.copyAddr")}: ${r.address}`);
  }
  lines.push(`${t("order.items")}:`);
  for (const it of order.items) {
    const spec = it.spec ? ` ${it.spec}` : "";
    const gift = it.isGift ? `（${t("order.copyGift")}）` : "";
    lines.push(`  · ${it.title}${spec} ×${it.qty}${gift}`);
  }
  return lines.join("\n");
}
