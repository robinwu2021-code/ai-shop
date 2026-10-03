// 活动的「玩法」模板（TDD-营销域-详细设计 §1.2）。
//
// **玩法不是类型，是模板**：每个玩法 = 预填好的触发 × 优惠，外加「规则」那一组要显示哪几行。
// 后端只认 `triggerType × benefitType`；加一个玩法 = 在这里加一条，不改算价。
//
// 放在 packages/shared 而不是 b-app 页面里：运营端建平台活动（P3）要用同一份，
// 两端各写一份的话，同一个玩法在两边会慢慢长成两个样子。
//
// ⚠️ **反查要同时看触发、优惠与受众**：三种减钱玩法共用 `CUT`、特价与秒杀共用
// `GOODS × PRICE`、新客立减与立减只差受众 —— 只按一个字段反查的话，
// 打开一个「立减」会显示成「满 0 减」，而没有任何一处报错（新值漏进老分支）。

/** 「规则」那一组里可能出现的行 */
export type RuleField =
  | "threshold"   // 满多少元
  | "qtyN"        // 满多少件
  | "amount"      // 减多少元
  | "price"       // 改成多少元（特价 / 秒杀 / 成团价 / 集单价）
  | "buyN"        // 买几件
  | "giftM"       // 送几件
  | "groupN"      // 几人成团
  | "groupHours"  // 成团时限（小时）
  | "cutoffTime"  // 每天几点截单
  | "pickup"      // 提货日与时刻
  | "minQty"      // 起订量
  | "decideHours"; // 未达起订的处理时限

/** 活动的触发。与后端 `PmtActivity.TRIGGER_*` 同一套取值 */
export type ActivityTrigger = "NONE" | "AMOUNT" | "QTY" | "GOODS" | "GROUP" | "CUTOFF" | "COMBO";

/** 活动的优惠。`COMBO` = 自己组合：条件与优惠都在组合行里，可以几样叠加 */
export type PlayBenefit = "CUT" | "PRICE" | "GIFT" | "COMBO";

/** 玩法开出的实例：拼团开出团，社区集单开出期 */
export type PlayInstance = "GROUP" | "PERIOD";

export interface PlayTemplate {
  /** 模板键。i18n 取 `plays.<key>.name` / `plays.<key>.desc` */
  key: string;
  triggerType: ActivityTrigger;
  benefitType: PlayBenefit;
  /** 规则组显示哪几行，按顺序 */
  rules: RuleField[];
  /** 要不要选商品（改单价、送商品、拼团、集单都要） */
  needsGoods: boolean;
  /** 预设受众。新客立减 = 立减 + 非会员（取值只有 `NON_MEMBER`） */
  audience?: string;
  /** 时间那一行是否固定（集单天天开，不给「一次 / 长期 / 每周」；取值只有 `ALWAYS_ON`） */
  fixedSchedule?: string;
  /** 有实例：GROUP 开出团，CUTOFF 开出期 */
  instance?: PlayInstance;
}

export const PLAY_TEMPLATES: readonly PlayTemplate[] = [
  { key: "CUT", triggerType: "AMOUNT", benefitType: "CUT", rules: ["threshold", "amount"], needsGoods: false },
  { key: "CUT_QTY", triggerType: "QTY", benefitType: "CUT", rules: ["qtyN", "amount"], needsGoods: false },
  { key: "CUT_ANY", triggerType: "NONE", benefitType: "CUT", rules: ["amount"], needsGoods: false },
  { key: "NEW_CUSTOMER", triggerType: "NONE", benefitType: "CUT", rules: ["amount"], needsGoods: false,
    audience: "NON_MEMBER" },
  { key: "PRICE", triggerType: "GOODS", benefitType: "PRICE", rules: ["price"], needsGoods: true },
  { key: "GIFT", triggerType: "QTY", benefitType: "GIFT", rules: ["buyN", "giftM"], needsGoods: true },
  { key: "GROUP", triggerType: "GROUP", benefitType: "PRICE", rules: ["groupN", "price", "groupHours"],
    needsGoods: true, instance: "GROUP" },
  { key: "BATCH", triggerType: "CUTOFF", benefitType: "PRICE",
    rules: ["cutoffTime", "pickup", "price", "minQty", "decideHours"],
    needsGoods: true, fixedSchedule: "ALWAYS_ON", instance: "PERIOD" },
  // 玩法面板的最后一项（原型 s11）：条件与优惠自己搭，规则组换成两张可增删的清单
  { key: "COMBO", triggerType: "COMBO", benefitType: "COMBO", rules: [], needsGoods: false },
] as const;

export function playOf(key: string): PlayTemplate | undefined {
  return PLAY_TEMPLATES.find((p) => p.key === key);
}

/**
 * 从库里那一行反推玩法。**触发、优惠、受众三样一起判**，理由见文件头。
 * 认不出的返回空 —— 调用方显示「自定义」，而不是硬猜成列表里的第一个。
 */
export function playOfActivity(a: {
  triggerType?: string | null;
  benefitType: string;
  audiences?: ReadonlyArray<{ type: string }>;
}): PlayTemplate | undefined {
  const trigger = a.triggerType || "NONE";
  const nonMember = (a.audiences ?? []).some((x) => x.type === "NON_MEMBER");
  const hits = PLAY_TEMPLATES.filter((p) => p.triggerType === trigger && p.benefitType === a.benefitType);
  if (hits.length <= 1) return hits[0];
  return hits.find((p) => (p.audience === "NON_MEMBER") === nonMember) ?? hits[0];
}
