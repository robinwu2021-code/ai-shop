// 「这家店还差什么才算开起来」——**判据只有这一份**。
//
// 与 `coverage.ts` 同一个形状：工作台上的告警读它，别的页面将来也读它。
// 两处各写一遍的代价在 coverage 那边已经付过一次 ——
// 说的和做的正好相反，而两边都不报错。

/**
 * 收款账户这件事**办完了没有**。
 *
 * <b>判据不是「有没有一张生效的卡」，是「还要不要他再做点什么」</b> ——
 * 所以待审（PENDING）算办完了：他该做的做完了，剩下的是运营审。
 * 这时候还亮一条告警，就成了他点不掉的噪音。
 * （与 `includedAreas` 把 PENDING 算进范围是同一条道理。）
 *
 * 被驳回的（REJECTED）不算：驳回原因在那一页上，而他得知道要回去重填。
 * 停用的（DISABLED）同样不算 —— 那是换卡时的旧记录。
 *
 * @param accounts 该主体的收款账户；空或未加载都当作「没办」
 */
export function payoutReady(accounts?: readonly { status?: string | null }[] | null): boolean {
  return (accounts ?? []).some((a) => a?.status === "ACTIVE" || a?.status === "PENDING");
}
