/**
 * 开单页的日期快捷：今天 / 昨天 / 前天 + 兜底滚轮。
 *
 * <h2>为什么抽出来</h2>
 * 进货与报损两页都要它。抄一份的代价不是重复代码，是**它们会各自漂** ——
 * 「昨天怎么算」这种判据一旦有两份，其中一份迟早被人改成
 * `Date.now() - 86400e3`，而那时症状是补记的单记到了前天，
 * 没有任何地方会报错。扫码那一段吃过一样的亏（见 biz-item-picker 的注释）。
 *
 * <h2>按本地日历日算，不按 24 小时减</h2>
 * `Date.now() - 86400e3` 在夏令时与闰秒那两天会错一天 —— 这个项目现在
 * 只跑中国大陆（没有夏令时），但把「昨天」定义成「减 86400 秒」本身就是错的，
 * 换个市场就炸。`setDate(-n)` 由运行时按日历退，没有这个问题。
 */
import { computed, type Ref } from "vue";

/** `YYYY-MM-DD`，本地日历日 */
export function isoDay(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() - offsetDays);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export interface QuickDate {
  value: string;
  label: string;
}

/**
 * @param t     调用方的 `useI18n().t` —— **词条键由调用方给**：
 *              进货与报损各有自己的命名空间，把键写死在这里等于
 *              让共享件认识调用方
 * @param keys  三枚的词条键，顺序是 今天 / 昨天 / 前天
 */
export function useQuickDates(t: (k: string) => unknown, keys: [string, string, string]) {
  return computed<QuickDate[]>(() =>
    [0, 1, 2].map((n) => ({ value: isoDay(n), label: String(t(keys[n]!)) })),
  );
}

/** 当前选的是不是那三枚之一 —— 不是的话「选日期」那一枚要亮着并显示真实日期 */
export function useIsQuick(quick: Ref<QuickDate[]> | { value: QuickDate[] }, picked: Ref<string>) {
  return computed(() => quick.value.some((d) => d.value === picked.value));
}
