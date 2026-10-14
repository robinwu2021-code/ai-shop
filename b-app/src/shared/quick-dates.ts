/**
 * 开单页的日期。
 *
 * <h2>按本地日历日算，不按 24 小时减</h2>
 * `Date.now() - 86400e3` 在夏令时与闰秒那两天会错一天 —— 这个项目现在
 * 只跑中国大陆（没有夏令时），但把「昨天」定义成「减 86400 秒」本身就是错的，
 * 换个市场就炸，而症状是补记的那张单记到了前天。
 * `setDate(-n)` 由运行时按日历退，没有这个问题。
 *
 * <h2>为什么抽出来</h2>
 * 进货与报损两页都要它。抄一份的代价不是重复代码，是**它们会各自漂** ——
 * 其中一份迟早被人改成上面那种减法，而那时没有任何地方会报错。
 */

/** `YYYY-MM-DD`，本地日历日 */
export function isoDay(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() - offsetDays);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
