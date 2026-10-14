// 选一条线上可售规则（TDD-商品纳入进销存开关 §4）。
// 两处在用：库存设置页的「本店默认 / 各类目」、编辑商品页的「这一件」——
// 同一件事在两个页面里分别写一遍，往后加一种规则就会有一处漏掉。
import { ruleNeedsParam } from "@/shared/inv-mode";
import { pick, prompt } from "@ai-shop/ui/prompt";
import type { SellRuleType } from "@shared/types";

type T = (key: string, named?: Record<string, unknown>) => unknown;

const RULE_TYPES: SellRuleType[] = ["ALL", "RESERVE", "RATIO", "CAP", "MANUAL"];

/**
 * @param canInherit 非本店级的那两级可以「跟随上一级」
 * @param cur        当前生效的这一级设过的规则；没设过传 undefined
 * @returns 取消返回 null
 */
export async function pickSellRule(
  t: T,
  canInherit: boolean,
  cur?: { ruleType: SellRuleType; param: number },
): Promise<{ ruleType: SellRuleType; param: number } | null> {
  const types: SellRuleType[] = canInherit ? ["INHERIT", ...RULE_TYPES] : RULE_TYPES;
  const i = await pick({
    title: String(t("stockSync.rules")),
    items: types.map((x) => String(t(`stockSync.ruleOpt.${x}`))),
    selected: Math.max(0, types.indexOf(cur?.ruleType ?? (canInherit ? "INHERIT" : "ALL"))),
  });
  const type = i === null ? undefined : types[i];
  if (!type) return null;
  if (!ruleNeedsParam(type)) return { ruleType: type, param: 0 };
  const v = await prompt({
    title: String(t(`stockSync.param.${type}`)),
    value: cur?.ruleType === type ? String(cur.param) : "",
    type: "number",
    maxlength: 5,
  });
  if (v === null) return null;
  return { ruleType: type, param: Math.max(0, Math.floor(Number(v.trim()) || 0)) };
}
