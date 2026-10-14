// 消息、卡包、团长 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, findGoodsSeed, persist, toGoods } from "@shared/mock/db";
import type { ShopApi } from "../contract";

export const messageMock: Pick<ShopApi,
  "messageList"
  | "readMessage"
  | "subscribeReport"
  | "unreadMessages"
  | "registerPushToken"
  | "unregisterPushToken"
  | "readAllMessages"
  | "myCards"
> = {
  // ---------------------------------------------------------------- 消息
  async messageList() {
    return delay([...db.messages].sort((a, b) => b.at - a.at));
  },

  async readMessage(messageNo) {
    const m = db.messages.find((x) => x.messageNo === messageNo);
    if (m) m.read = true;
    persist();
    return delay([...db.messages]);
  },

  async subscribeReport() {
    // mock 世界没有微信授权额度这回事，收下即可
    return delay(undefined);
  },

  async unreadMessages() {
    return delay(db.messages.filter((m) => !m.read).length);
  },

  // mock 世界没有真设备（H5 下 getPushDevice 恒为 null，这两个压根不会被调到）
  async registerPushToken() {
    return delay(undefined);
  },

  async unregisterPushToken() {
    return delay(undefined);
  },

  async readAllMessages() {
    db.messages.forEach((m) => (m.read = true));
    persist();
    return delay([...db.messages]);
  },

  // ---------------------------------------------------------------- 卡包
  async myCards() {
    // 标题按当前语言重算（同购物车）
    db.cards = db.cards.map((c) => ({
      ...c,
      title: toGoods(findGoodsSeed(c.goodsNo)).title,
    }));
    return delay([...db.cards]);
  },

  // ---------------------------------------------------------------- 团长

  /** 团长视角：本自提点的订单。真实后端按 pickupNo + 团长归属过滤 */

  /**
   * 分拣单：按 SKU 汇总。
   * 团长到货那天照着这个点数 —— 所以是「商品维度」而不是「订单维度」，
   * 按订单列会让人在几十个包裹之间反复翻找同一个商品。
   */

  /** 到货：批量把备货中的订单推到「已到自提点」，用户此时收到到货通知 */
};
