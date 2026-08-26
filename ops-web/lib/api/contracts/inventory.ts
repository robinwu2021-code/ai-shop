// 进销存（P-18）—— `/ops/inventory/**`。**独立模块**：与商品域不共契约文件，
// 它将来要能单独交付。
import type { InvHealthRow, InvLedgerRow, InvReconReport } from "@/lib/types";

export interface InventoryApi {
  // 三个都只读：运营不改商家库存 —— 改了之后「这个数是谁改的」就多一个答案，而商家不会知道。

  /**
   * 库存健康度：负库存 / 零库存仍在架 / 长期未动销。
   *
   * 这三类商品**正在给买家制造失败的下单** —— 点进去、加购、然后发现买不了。
   */
  listInvHealth(q?: { kind?: InvHealthRow["kind"]; limit?: number }): Promise<InvHealthRow[]>;

  /**
   * 商家台账（只读）。客服回答「我的货怎么少了」时的依据。
   *
   * `cursor` 传上一页最后一行的 `id` —— **游标不是页码**：
   * 时间游标会因时钟回拨漏行，而漏的那几行不会有任何报错。
   */
  listInvLedger(q: { ownerId?: string; itemId?: string; cursor?: number; size?: number }): Promise<InvLedgerRow[]>;

  /**
   * 库存对差。**这是 G3 闸门的数据来源**：`clean` 连续 N 天为真才准切真相源。
   *
   * 直接切等于「切换那天开始超卖」，而无从回溯是从哪一刻起的。
   */
  getInvRecon(q?: { limit?: number }): Promise<InvReconReport>;
}
