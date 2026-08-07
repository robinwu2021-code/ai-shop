/** 产品分期（对齐 docs/requirements/需求矩阵-三端.md 的 P0/P1/P2 优先级）。
 *
 * Phase 1 = 矩阵 P0 一期（§八 M1-1 ~ M1-6 六个批次）
 * Phase 2 = 矩阵 P1 二期
 * Phase 3 = 矩阵 P2 增强
 *
 * 切换：.env.local 改 NEXT_PUBLIC_CURRENT_PHASE=2 后重启 dev server。
 *
 * ⚠️ phase 只是**徽章**（这是第几期），能不能点由 NavLeaf.ready 单独表达。
 * 两件事混在一个字段里，界面上就分不出「灰是因为排期靠后」还是「灰是因为还没做」。
 */
export type Phase = 1 | 2 | 3;

export const CURRENT_PHASE: Phase =
  (Number(process.env.NEXT_PUBLIC_CURRENT_PHASE || "1") as Phase) || 1;

/**
 * 分期的 i18n key（中英各一份在 `lib/i18n/messages/*.ts` 的 `phase.p1/p2/p3`）。
 * 这里只给 key，不存文案 —— 存了就会出现"中文存一份、翻译再存一份"两处对不上。
 */
export const PHASE_KEY: Record<Phase, string> = { 1: "phase.p1", 2: "phase.p2", 3: "phase.p3" };

/** 判断某功能是否被当前阶段屏蔽（phase > CURRENT_PHASE）。 */
export function isPhaseLocked(phase: Phase | undefined): boolean {
  return !!phase && phase > CURRENT_PHASE;
}
