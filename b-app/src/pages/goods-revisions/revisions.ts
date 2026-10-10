/**
 * 提交历史列表的派生量（纯函数）。
 *
 * <p><b>为什么抽出来</b>：这一页最容易出的错是**把「最新」当成「线上在售」** ——
 * 存了草稿还没发布时，最新那一版买家看不到。这条判断留在 `.vue` 里就没有测试
 * 看得见（b-app 的 vitest 不装 vue 插件、只收 `tests/` 下的纯函数），
 * 而写错了界面照样渲染，只是把买家看不到的那一版标成了「线上在售」。
 */
import type { GoodsRevision, GoodsRevisionStatus } from "@/api/contract";

/**
 * 线上在售那一版的版本号。**不取 `rows[0]`** —— 列表是按版本号倒序的，
 * 而最新那一行常常是未发布的草稿。没发布过回 null。
 */
export function onlineRevisionOf(rows: GoodsRevision[]): number | null {
  return rows.find((r) => r.status === "ONLINE")?.revisionNo ?? null;
}

/**
 * 编辑页横幅要的两个号：「草稿 vN 未发布 · 线上在售 vM」。
 *
 * <p>**两个都有才回** —— 只有草稿（从没发布过的新商品）时说「线上在售 v?」
 * 是假话；横幅那边据此退回不带号的旧文案，宁可少说一句。
 */
export function draftVersionsOf(rows: GoodsRevision[]): { n: number; m: number } | null {
  const draft = rows.find((r) => r.status === "DRAFT");
  const online = rows.find((r) => r.status === "ONLINE");
  return draft && online ? { n: draft.revisionNo, m: online.revisionNo } : null;
}

/**
 * 状态 chip 的修饰类。四种状态必须分得开 ——
 * 「线上在售」是这一页唯一要一眼找到的那行。
 */
export function statusChipOf(status: GoodsRevisionStatus): string {
  if (status === "ONLINE") return "sh-chip--success";
  if (status === "DRAFT") return "sh-chip--warning";
  if (status === "REJECTED") return "sh-chip--danger";
  return "";
}
