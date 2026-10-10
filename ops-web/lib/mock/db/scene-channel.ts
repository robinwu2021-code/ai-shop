import type { SceneChannelCell } from "@/lib/types";

/**
 * 场景×通道矩阵。**这 16 格是照 `schema-test.sql` 的真实种子抄的**，不是编的 ——
 * 场景码、受众（`C_USER` / `B_STAFF`，不是 BUYER/MERCHANT）、默认开关、
 * 推送等级（`SUB_ORDER_PAID` 对商家是 `RING`，其余 `NORMAL`）都与后端一致。
 *
 * <p>mock 自己编一套场景码的代价：界面在 mock 下好看，接真后端时**一格都对不上** ——
 * 而这一屏的全部意义就是「哪个事件走哪些通道」。
 *
 * <p>`locked` 由 channel 推出：INAPP 恒锁（站内信是事实记录，运营不可关），
 * 与后端 `SceneChannelVO.of` 的算法一致。
 */
export const sceneChannels: SceneChannelCell[] = [
  { scene: "ORDER_PAID", audience: "C_USER", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "ORDER_PAID", audience: "C_USER", channel: "PUSH", enabled: false, pushLevel: "NORMAL", locked: false },
  { scene: "ORDER_ARRIVED", audience: "C_USER", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "ORDER_ARRIVED", audience: "C_USER", channel: "WXSUB", enabled: true, pushLevel: "NORMAL", locked: false },
  { scene: "ORDER_ARRIVED", audience: "C_USER", channel: "PUSH", enabled: true, pushLevel: "NORMAL", locked: false },
  { scene: "SUB_ORDER_COMPLETED", audience: "C_USER", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "SUB_ORDER_COMPLETED", audience: "C_USER", channel: "PUSH", enabled: false, pushLevel: "NORMAL", locked: false },
  { scene: "AFTER_SALE_REFUNDED", audience: "C_USER", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "AFTER_SALE_REFUNDED", audience: "C_USER", channel: "WXSUB", enabled: true, pushLevel: "NORMAL", locked: false },
  { scene: "AFTER_SALE_REFUNDED", audience: "C_USER", channel: "PUSH", enabled: false, pushLevel: "NORMAL", locked: false },
  { scene: "SUB_ORDER_PAID", audience: "B_STAFF", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "SUB_ORDER_PAID", audience: "B_STAFF", channel: "PUSH", enabled: true, pushLevel: "RING", locked: false },
  { scene: "AFTER_SALE_APPLIED", audience: "B_STAFF", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "AFTER_SALE_APPLIED", audience: "B_STAFF", channel: "PUSH", enabled: true, pushLevel: "NORMAL", locked: false },
  { scene: "REVIEW_CREATED", audience: "B_STAFF", channel: "INAPP", enabled: true, pushLevel: "NORMAL", locked: true },
  { scene: "REVIEW_CREATED", audience: "B_STAFF", channel: "PUSH", enabled: true, pushLevel: "NORMAL", locked: false },
];
