// 进销存（P-18）—— `/ops/inventory/**`。**独立模块**：与商品域不共契约文件，
// 它将来要能单独交付。
import type { InvBalanceRow, InvHealthRow, InvLedgerPage, InvReconReport, InvCredential, InvCredentialIssued } from "@/lib/types";

export interface InventoryApi {
  // **库存本身仍然只读**：运营不改商家库存 —— 改了之后「这个数是谁改的」
  // 就多一个答案，而商家不会知道。
  //
  // 开放对接的钥匙是**唯一的例外**，而它改的不是货、是**谁能读这些货**：
  // 那本来就是平台该管的事（对方换了对接商、密钥泄露，处置只能在这儿）。
  // 所以它挂的是 `merchant:mode:update` 而不是看库存那个码。

  /**
   * 库存健康度：负库存 / 零库存仍在架 / 长期未动销。
   *
   * 这三类商品**正在给买家制造失败的下单** —— 点进去、加购、然后发现买不了。
   */
  listInvHealth(q?: { kind?: InvHealthRow["kind"]; limit?: number }): Promise<InvHealthRow[]>;

  /**
   * **某一个商家**的库存待办（健康度页点进一行之后看的）。
   *
   * `type` 默认 `todo`（只给有标记的：缺货 / 滞销）· `all` 全部 · `reserved` 有预留。
   * `entityNo` 必填 —— 这一页的前提就是「已经知道要看谁」。
   */
  listInvBalances(q: { entityNo: string; type?: string; size?: number }): Promise<InvBalanceRow[]>;

  /**
   * 商家台账（只读）。客服回答「我的货怎么少了」时的依据。
   *
   * **`entityNo` 必填**：台账天然是「看某一个商家」的东西 —— 客服手上永远
   * 先有一个商家，再有那句「我的货怎么少了」。后端也是这么定的，不传就是 400。
   *
   * `cursor` 传上一页返回的 `nextCursor` —— **游标不是页码**，也不要自己拿
   * 最后一行的 id 去推：同一毫秒有多笔时会漏行，而漏的那几行不会有任何报错。
   */
  listInvLedger(q: { entityNo: string; itemId?: string; cursor?: number; size?: number }): Promise<InvLedgerPage>;

  /**
   * 库存对差。**这是 G3 闸门的数据来源**：`clean` 连续 N 天为真才准切真相源。
   *
   * 直接切等于「切换那天开始超卖」，而无从回溯是从哪一刻起的。
   */
  getInvRecon(q?: { limit?: number }): Promise<InvReconReport>;

  /** 某个商家发过哪些开放对接的钥匙。**吊销过的也在列** */
  listInvCredentials(q: { entityNo: string }): Promise<InvCredential[]>;

  /**
   * 签发。**返回体里的 `appSecret` 是它唯一一次明文出现** ——
   * 这个响应关掉就再也拿不回来，只能吊销重发。界面必须让人当场复制走。
   */
  issueInvCredential(body: {
    entityNo: string; name: string; scopes: string; expiresAt?: string | null;
  }): Promise<InvCredentialIssued>;

  /** 吊销。**发得出、收不回的钥匙是半截功能** */
  revokeInvCredential(credentialId: string): Promise<void>;
}
