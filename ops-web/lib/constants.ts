// 全站业务常量（零硬编码约定：数字/字符串不许散落在页面里）。
// 判据：**同一个值出现在第二个文件时**就该搬进来 —— 不是所有字面量都要进这里，
// 那会变成一个谁也读不懂的数字仓库。

/** 列表页默认每页条数。改这里全站生效；某页确有理由用别的值，在该页写明理由。 */
export const PAGE_SIZE = 10;

/** 搜索输入防抖（ms）。列表页的关键词筛选统一用它。 */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * 金额的最小货币单位换算比（分 → 元）。
 * ⚠️ 只在 `lib/utils.ts#money` 里用。页面拿到的金额一律是最小单位整数，
 * 自己除以 100 就会绕开 Intl 本地化 —— 多市场（P-17.1.3）下必错。
 */
export const MINOR_UNIT = 100;

/** 默认货币。多市场时由数据带 currency 覆盖（矩阵 P-17.1.3）。 */
export const DEFAULT_CURRENCY = "CNY";

/**
 * 临时自提点（NEIGHBOR）职业化风控阈值：近 30 天承接 ≥ 该次数触发人工复核。
 * ADR-005 §F6 的**建议值**，风控要调就改这里（不要散在查询条件里）。
 */
export const NEIGHBOR_RISK_ACCEPT_COUNT = 3;

/** 逾期处置的最小宽限小时数。到点即作废必产生客诉，所以不允许配 0。 */
export const MIN_OVERDUE_GRACE_HOURS = 1;

/** 评分三维权重之和（P-13.1.4）。和不为它就是配置错误，两侧都校验。 */
export const SCORE_WEIGHT_TOTAL = 100;

/** 售后赔付三方比例之和（P-6.1.4）。 */
export const LIABILITY_SHARE_TOTAL = 100;

/** 极速退的最小时限（小时）。0 小时等于关掉，但开关看起来还是"已启用"。 */
export const MIN_FAST_REFUND_HOURS = 1;

/** 报价改价次数上限（ADR-003：不禁止改价，但超阈即锁，改价已公示给参团用户）。 */
export const MAX_QUOTE_PRICE_CHANGES = 3;

/**
 * 商家毁约次数上限：累计达到即限制报价。
 * 计数**取商家档案的 `breachCount`**（P-11.1.5 信用档案）而不是数报价表 ——
 * 毁约可能发生在报价之外（如成团后不发货），只数报价会漏。
 */
export const MAX_MERCHANT_BREACH = 3;

/** 分账指令重试上限（P-12.1.3）。无限重试会把一个坏账刷成一堆日志。 */
export const MAX_SPLIT_RETRY = 3;

/** 超时兜底最少天数（P-12.1.4）。太短会把还在正常重试的单提前解冻回平台。 */
export const SETTLE_FREEZE_MIN_DAYS = 7;

/**
 * 未支付订单自动关单的时限下限（分钟）。
 * **关得越快掉单越多**：用户在收银台上停留几分钟很常见，订单被关掉而渠道又扣了款，
 * 就是一条 CHANNEL_ONLY 差异。5 分钟是能覆盖正常支付流程的最小值。
 */
export const MIN_UNPAID_CLOSE_MINUTES = 5;

/** 自动关单时限上限（分钟）。超过一天不关单，库存会被长期占住。 */
export const MAX_UNPAID_CLOSE_MINUTES = 1440;

/**
 * 各状态允许停留的时长（分钟），超过即进异常单队列。
 *
 * 按状态分别给而不是给一个统一值：待支付 15 分钟就该关单，而"已送达待自提"
 * 放一天很正常 —— 一刀切会把正常单刷进异常队列，队列一旦变噪音就没人看了。
 * 终态（COMPLETED / CANCELLED）不设时限。
 */
export const STUCK_MINUTES: Record<string, number> = {
  WAIT_PAY: 15,
  // 已付款待发货：备货本身要时间，30 分钟一刀切会把正常单刷进异常队列
  PAID: 120,
  SHIPPED: 240,
  ARRIVED: 1440,
  REFUNDED: 2880,
};

/** 运费模板首重下限（克）。首重 0 克意味着"拿起来就收首重费"，是配置错误而不是策略。 */
export const MIN_FIRST_WEIGHT_GRAM = 100;

/**
 * 店铺主页至少要启用的板块数。
 * 只剩店招的店铺页等于一张裸列表 —— 那不是"极简模板"，是配置漏了。
 */
export const MIN_ENABLED_SECTIONS = 2;

/** 单笔提现下限（分）。低于它的提现，渠道手续费比本金还贵。 */
export const MIN_WITHDRAW_AMOUNT = 1000;

/** 大额提现复核阈值（分）。超过它必须写复核说明 —— 大额是最容易被冒用的口子。 */
export const WITHDRAW_REVIEW_THRESHOLD = 500_000;

/** 代扣税率上限（万分比）。超过 45% 的代扣一定是配置错误。 */
export const MAX_TAX_RATE = 4500;

/**
 * 会员折扣的下限（万分比）。5000 = 5 折。
 * 比这更狠的会员折扣会把毛利打穿 —— 会员卡的月费远补不回来。
 */
export const MIN_MEMBER_DISCOUNT = 5000;

/** 榜单最多取前多少名。超过 50 名的榜没人往下翻，只会拖慢首页。 */
export const MAX_RANKING_SIZE = 50;
