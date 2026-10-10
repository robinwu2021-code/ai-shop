/**
 * 把文字识别出的范围项并进现有清单（TDD-经营范围文字录入 AC5/AC6）。纯函数。
 *
 * 合并口径与选择器的 addArea / toggleExclude **同一套**，不另立规则：
 *   · 同一个对象只留一条（后来的方向赢）—— 又纳入又排除的矛盾在输入端消掉；
 *   · 纳入一个区划时收掉它底下已有的区划项（父子只留父）；
 *   · 说了「不限」：清掉所有纳入项、保留排除（「全国，新疆西藏除外」）；
 *   · 「替换」：只留这次识别出的。
 */
import type { AreaLevel, AreaMode, ServiceArea } from "@shared/types";

const REGION = new Set<string>(["PROVINCE", "CITY", "DISTRICT", "STREET"]);
const isExclude = (a: ServiceArea) => a.mode === "EXCLUDE";
const sameKey = (a: ServiceArea, b: ServiceArea) => a.level === b.level && a.refCode === b.refCode;

export interface ParsedPick {
  mode: AreaMode;
  level: string;
  refCode: string;
  name: string;
}

export interface MergeResult {
  areas: ServiceArea[];
  /** 因「不限」或「替换」被清掉的纳入项条数 —— 确认表上要明说，不能悄悄删 */
  droppedIncludes: number;
}

export function mergeParsed(
  existing: ServiceArea[],
  picks: ParsedPick[],
  opts: { replace: boolean; unlimited: boolean },
): MergeResult {
  let base = opts.replace ? [] : existing.slice();
  const includesBefore = existing.filter((a) => !isExclude(a)).length;
  if (opts.unlimited) base = base.filter(isExclude);
  for (const p of picks) {
    const next: ServiceArea = {
      level: p.level as AreaLevel,
      refCode: p.refCode,
      name: p.name,
      mode: p.mode === "EXCLUDE" ? "EXCLUDE" : "INCLUDE",
    };
    base = base.filter((a) => !sameKey(a, next));
    if (!isExclude(next) && REGION.has(next.level)) {
      base = base.filter((a) => !(REGION.has(a.level) && a.refCode.startsWith(next.refCode)));
    }
    base.push(next);
  }
  const keptOld = base.filter((a) => !isExclude(a) && existing.some((e) => sameKey(e, a) && !isExclude(e))).length;
  return { areas: base, droppedIncludes: opts.replace || opts.unlimited ? Math.max(0, includesBefore - keptOld) : 0 };
}
