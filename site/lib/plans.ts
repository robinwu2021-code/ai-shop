/**
 * 订阅档位 —— 官网这一份是**展示层**，真源在数据库。
 *
 * 额度与能力位的真源是 `sys_merchant_plan_def` 的三档种子
 * （backend/.../db/migration/V150__merchant_plan.sql），运营可以不发版就调。
 * 官网上写的数字与商家进后台看到的对不上，是最难解释的一种不一致 ——
 * 所以 lib/plans.test.ts 直接去解析那份迁移，对不上就红。
 *
 * ⚠️ **`dbName` 与 `name` 现在是两套叫法**（2026-08-20 拍板：官网用「免费版/专业版/旗舰版」）。
 * B 端「我的套餐」页渲染的是后端下发的 `planName`，也就是 `dbName` ——
 * 商家在官网看到「专业版」，进后台看到「成长版」。要消掉这一层翻译，
 * 得改 `sys_merchant_plan_def.name`（一条 UPDATE 迁移）。在那之前，
 * 下面的 `dbName` 是**故意留着的**：它让这个分叉在代码里有名有姓，
 * 而不是散落在客服的口头解释里。
 */
export type PlanCode = "FREE" | "PRO" | "CHAIN";

export type Plan = {
  code: PlanCode;
  /** 官网展示名 */
  name: string;
  /** 数据库 `sys_merchant_plan_def.name`，即商家在 B 端看到的名字 */
  dbName: string;
  /** 一句话：这一档卖给谁 */
  who: string;
  storeQuota: number;
  staffQuota: number;
  crossStoreStats: boolean;
  /** 0 = 这一档不提供试用 */
  trialDays: number;
  /** 价格未定（是商务决策，不在工程范围）—— 页面统一写「联系报价」*/
  priceNote: string;
};

export const PLANS: readonly Plan[] = [
  {
    code: "FREE",
    name: "免费版",
    dbName: "孵化版",
    who: "单店经营，由老板本人管理",
    storeQuota: 1,
    staffQuota: 0,
    crossStoreStats: false,
    trialDays: 0,
    priceNote: "长期免费",
  },
  {
    code: "PRO",
    name: "专业版",
    dbName: "成长版",
    who: "开设第二、三家门店，并有员工分工",
    storeQuota: 3,
    staffQuota: 3,
    crossStoreStats: true,
    trialDays: 14,
    priceNote: "联系报价",
  },
  {
    code: "CHAIN",
    name: "旗舰版",
    dbName: "连锁版",
    who: "多店多人协作，依据数据做经营决策",
    storeQuota: 10,
    staffQuota: 15,
    crossStoreStats: true,
    trialDays: 14,
    priceNote: "联系报价",
  },
];

/**
 * 权益对照表的行。
 *
 * `planned: true` = **设计定了但库里还没有这个能力位**（`sys_merchant_plan_def`
 * 一期只有 `cross_store_stats` 一个）。页面必须打「规划中」角标 ——
 * 商家照着页面签了约却导不出账，是最难解释的一种投诉。
 */
export type Entitlement = {
  label: string;
  /** 每档的值：true/false 渲染成 ✓/—，字符串直接显示 */
  values: Record<PlanCode, string | boolean>;
  planned?: boolean;
  note?: string;
};

export const ENTITLEMENTS: readonly Entitlement[] = [
  {
    label: "门店数",
    values: Object.fromEntries(PLANS.map((p) => [p.code, `${p.storeQuota} 家`])) as Record<
      PlanCode,
      string
    >,
    note: "仅统计营业中的门店，停用门店不占用额度。",
  },
  {
    label: "子账号数",
    values: Object.fromEntries(
      PLANS.map((p) => [p.code, p.staffQuota === 0 ? false : `${p.staffQuota} 个`]),
    ) as Record<PlanCode, string | boolean>,
    note: "不含老板本人。角色绑定在「人 × 门店」上，同一人在不同门店可担任不同角色。",
  },
  {
    label: "跨店总览与对比",
    values: Object.fromEntries(PLANS.map((p) => [p.code, p.crossStoreStats])) as Record<
      PlanCode,
      boolean
    >,
    note: "各店当日单量、销售额与待办并排呈现；可按销售额 / 订单数 / 复购率 / 评分 / 缺货数对比。",
  },
  {
    label: "免费试用",
    values: Object.fromEntries(
      PLANS.map((p) => [p.code, p.trialDays === 0 ? false : `${p.trialDays} 天`]),
    ) as Record<PlanCode, string | boolean>,
    note: "一个经营主体限试用一次，到期不自动扣费。",
  },
  {
    label: "单店经营全套",
    values: { FREE: true, PRO: true, CHAIN: true },
    note: "商品、订单、履约、售后、评价、营销、客户、结算 —— 免费版同样全量提供。",
  },
  {
    label: "交易费率",
    values: { FREE: "同一套", PRO: "同一套", CHAIN: "同一套" },
    note: "费率不作为档位差异项。自带客流零佣金对所有档位同等适用 —— 按档位区分费率等同于变相恢复抽佣。",
  },
  {
    label: "数据留存",
    values: { FREE: "90 天", PRO: "1 年", CHAIN: "3 年" },
    planned: true,
  },
  {
    label: "对账与客户导出",
    values: { FREE: false, PRO: true, CHAIN: true },
    planned: true,
  },
  {
    label: "专属客服通道",
    values: { FREE: false, PRO: false, CHAIN: true },
    planned: true,
  },
];

/** 到期与降级：这套规则本身就是卖点，藏在条款里等于没有 */
export const LIFECYCLE = [
  {
    k: "升档立即生效",
    v: "你此刻就需要开第四家门店，不应等到下个周期。",
  },
  {
    k: "降档 / 退订于到期时生效",
    v: "已付周期不予退款，但也不会立即收回已购买的能力。",
  },
  {
    k: "到期先进入 7 天宽限期，能力全部保留",
    v: "扣款失败、负责人出差、漏续等情形与经营本身无关，不应当日即影响营业。",
  },
  {
    k: "宽限期结束为降级，而非关店",
    v: "超出额度的门店转为只读：不再接收新订单，但历史订单可查、未完成的订单照常核销。关店影响的是消费者，而欠费是你与平台之间的事。",
  },
  {
    k: "转为只读的门店按固定规则确定",
    v: "保留默认门店，其余按建店时间倒序处理。规则预先写死，不由系统推断，也不在该时点要求你临时抉择。",
  },
  {
    k: "补缴后原样恢复",
    v: "门店与授权配置全部保留，无需重新建店或重新授权。数据不作删除 —— 降级影响的是可查询的时间范围，而非是否留存。",
  },
];
