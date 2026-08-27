// 端点 → 主语 / 行业关系码的**登记表**。行业化终局选型（P3）的输入。
//
// 写法照 `perm-endpoint-map.mjs`：规则从**具体到一般**，第一条命中的生效，
// 顺序敏感 —— 每组内部都是「特例在前、兜底在后」。
//
// 三条写在前面的取舍：
//
// 1. **关系码落到接口，不落到模块**。一个模块 18 个接口里 17 个相同 1 个结构差异，
//    与 18 个全结构差异，指向的方案完全不同 —— 按模块打码这两种情况长得一模一样。
//    所以这里的规则可以按路径前缀写，但**凡是组内不一致的，必须拆成多条规则**。
//
// 2. **只给现有 299 条打码**。行业包**新增**的接口不在这里 ——
//    它们没有「相对零售的差异」可言（零售没有那个接口）。
//    这也意味着本表天然**低估**行业差异，见 `gen-industry-endpoint-inventory.mjs` 的补充度量。
//
// 3. **判据来自两份 TDD，不是拍脑袋**：
//    餐饮 → docs/technical/design/TDD-餐饮包-场景与工作流.md
//    美业 → docs/technical/design/TDD-美业包-场景与工作流.md
//    改关系码要同时改那两份，否则文档与统计会静默分岔。

/** 关系码。零售是基线，恒为 S。 */
export const CODES = {
  S: "完全相同",
  P: "参数化差异：同一接口多几个字段或过滤",
  V: "视图差异：写完全相同，读模型不同",
  X: "结构差异：流程不同，必须分开",
  N: "该行业不适用",
};

/** 主语规则：[路径正则, subject]。第一条命中的生效。 */
export const SUBJECT_RULES = [
  // ⚠️ 必须在 store 之前：真实路径是 /biz/stores/{storeNo}/appointment-slots，
  // 按前缀分组会把它算进 store，于是「预约能力对外暴露了几个端点」这个数是错的。
  [/appointment-slots/, "slot"],
  [/^\/(biz|mp)\/inventory/, "stock"],
  [/^\/(biz|mp)\/(cart|order|invoice|quote)/, "order"],
  [/^\/(biz|mp)\/after-sale/, "after_sale"],
  [/^\/(biz|mp)\/(goods|spec|spu-std|sku-identity|category|topics|search|pickable-props|my-spec-dims|store-spec-dims)/, "goods"],
  [/^\/(biz|mp)\/(store|stores|cross-store)/, "store"],
  [/^\/(biz|mp)\/(merchant|entity|entities|qualifications|deposit|plan|context)/, "merchant"],
  [/^\/(biz|mp)\/(members|member-|my-memberships|customers)/, "member"],
  [/^\/(biz|mp)\/(coupon|activit|campaign)/, "coupon"],
  [/^\/(biz|mp)\/(pickup|delivery|ticket)/, "fulfillment"],
  [/^\/(biz|mp)\/settle/, "settle"],
  [/^\/(biz|mp)\/(staff|auth|role)/, "staff"],
  [/^\/(biz|mp)\/group/, "group"],
  [/^\/(biz|mp)\/(message|push-token)/, "message"],
  [/^\/(biz|mp)\/(geo|regions|communities|community)/, "geo"],
  [/^\/(biz|mp)\/review/, "review"],
  [/^\/(biz|mp)\/appointment-slots/, "slot"],
  [/^\/(biz|mp)\/(risk|attribution)/, "risk"],
];

/**
 * 关系码规则：[方法或 *, 路径正则, food, beauty, 理由]。
 * 理由**只在不显然时写** —— 与 perm-endpoint-map 同一条规矩。
 */
export const RULES = [
  // ── order：差异最集中的一组 ────────────────────────────────────────────
  // 购物车：餐饮必须台账维度（一桌多人同时点，基座是 userNo 维度）；
  // 美业一次预约一到三项、一个人选，预约单自带项目行，用不上共享车。
  ["*", /^\/mp\/cart/, "X", "X", "两个行业都不用基座购物车，理由各不相同（餐饮台账维度 / 美业预约单自带行）"],
  // 下单：餐饮走 /x/food/place（台账编排），美业到店才下单（避免幽灵订单）
  ["POST", /^\/mp\/order$/, "X", "X", "下单入口在行业包，编排完再调 CoreOrderApi"],
  ["POST", /^\/mp\/order\/(create|submit)/, "X", "X"],
  // 订单读：写相同、返回要多桌号轮次 / 技师工单
  ["GET", /^\/mp\/order/, "V", "V", "读要显示桌号轮次 / 技师与工单，写完全相同"],
  ["GET", /^\/biz\/order/, "V", "V"],
  ["*", /^\/mp\/invoice/, "S", "S"],
  ["*", /^\/(biz|mp)\/order/, "S", "S"],
  ["*", /^\/biz\/quote/, "S", "S"],

  // ── goods：读要行业视图，写完全共用 ───────────────────────────────────
  // 规格模板、类目、标品是纯主数据，三行业一模一样
  ["*", /^\/(biz|mp)\/(spec|my-spec-dims|store-spec-dims|spu-std|sku-identity|pickable-props|category)/, "S", "S"],
  ["GET", /^\/(biz|mp)\/goods/, "V", "V", "餐饮要出品部门与沽清，美业要时长与可选技师；写（建品改价）一字不改"],
  ["GET", /^\/mp\/(topics|search)/, "V", "V"],
  ["*", /^\/(biz|mp)\/(goods|topics|search)/, "S", "S"],

  // ── slot：基座只有 1 个端点，且要泛化到资源级 ─────────────────────────
  ["*", /appointment-slots/, "P", "P", "要加 resource_no 维度；餐饮用于包间预定，美业是核心"],

  // ── fulfillment ───────────────────────────────────────────────────────
  // 核销票：美业到店核销走工单编排，餐饮自取直接用
  ["*", /^\/mp\/ticket/, "S", "V", "美业核销要连带耗卡与工单，读的东西不同"],
  ["*", /^\/(biz|mp)\/(pickup|delivery)/, "S", "S"],

  // ── member：客史是美业的读视图 ─────────────────────────────────────────
  ["*", /^\/biz\/customers/, "S", "V", "美业要客史（偏好、过敏史、到店记录）"],
  ["*", /^\/(biz|mp)\/(members|member-|my-memberships)/, "S", "S"],

  // ── store：营业时间对餐饮多一层时段菜单，但那是新接口不是改这条 ────────
  ["*", /^\/(biz|mp)\/(store|stores|cross-store)/, "S", "S"],

  // ── 三行业完全相同的几组（P2 §4 已经指出售后与结算整组落 S）───────────
  ["*", /^\/(biz|mp)\/after-sale/, "S", "S"],
  ["*", /^\/(biz|mp)\/settle/, "S", "S"],
  ["*", /^\/(biz|mp)\/review/, "S", "S"],
  ["*", /^\/(biz|mp)\/(coupon|activit|campaign)/, "S", "S"],
  ["*", /^\/(biz|mp)\/(geo|regions|communities|community)/, "S", "S"],
  ["*", /^\/(biz|mp)\/(message|push-token)/, "S", "S"],
  ["*", /^\/(biz|mp)\/(staff|auth|role)/, "S", "S"],
  ["*", /^\/(biz|mp)\/(merchant|entity|entities|qualifications|deposit|plan|context)/, "S", "S"],
  ["*", /^\/(biz|mp)\/inventory/, "S", "S", "行业包只用「设库存」一个动作做沽清，其余 26 条原样共用"],
  ["*", /^\/(biz|mp)\/(risk|attribution)/, "S", "S"],

  // ── 与业务形态无关的横切：看板、上传、帮助 ─────────────────────────────
  ["*", /^\/biz\/upload/, "S", "S"],
  ["*", /^\/biz\/dashboard/, "V", "V", "看板指标要按行业换（翻台率 / 到店率 / 复购），接口形状不变"],
  ["*", /^\/mp\/(help|my-coupons)/, "S", "S"],

  // ── group：平台形态，不属于任何行业 ───────────────────────────────────
  ["*", /^\/(biz|mp)\/group/, "N", "N", "社区团购是平台形态，两个行业都不做 —— 排除出分母，见零售基线 §7"],
];

export function subjectOf(path) {
  for (const [re, s] of SUBJECT_RULES) if (re.test(path)) return s;
  return "other";
}

export function relationOf(method, path) {
  for (const [m, re, food, beauty, why] of RULES) {
    if ((m === "*" || m === method) && re.test(path)) return { food, beauty, why };
  }
  return { food: "?", beauty: "?", why: "未登记 —— 必须补一条规则" };
}
