// 三级导航 SSOT：L1 模块(section) → L2 分组(group) → L3 子功能(leaf)。
// 依据 docs/requirements/需求矩阵-三端.md §六「平台端矩阵」逐行对照，勿凭记忆增删
// —— nav.test.ts 断言矩阵里每个 P-x.y 模块都被至少一个叶子覆盖。
//
// - L1 可见性 = canModule(section.module)；权限码模块前缀见 lib/permissions.ts
// - L3 可见性 = leaf.perm ? can(perms, perm) : 跟随 section
//   判权入参是**后端下发的 perms**，不是 role —— role 只用于展示与分组
// - soon = 待建：灰显不可点，不产生 404 入口（脚手架阶段绝大多数叶子都是 soon）
// - phase = 产品分期徽章（1=矩阵 P0 一期 / 2=P1 二期 / 3=P2 增强）
// - ready = 就绪度门禁，与 phase 分开：见 NavLeaf.ready
// - 深链沿用 ?tab= / ?view=；本文件为纯数据 + 纯函数（无 React），可单测。
import type { Role } from "./auth";
import { can, canModule } from "./permissions";
import type { Phase } from "./phase";
import { isPhaseLocked } from "./phase";

export const NAV_PREFS_STORAGE_KEY = "shop-ops-nav-prefs";

// 布局常量（px）
export const RAIL_WIDTH = 56;
export const RAIL_EXPANDED_WIDTH = 168;
export const PANEL_WIDTH = 176;

export interface NavLeaf {
  href: string; // 详情/深链（可含 ?tab= / ?view=，可跨 section）
  label: string;
  perm?: string; // 细粒度权限码；无则跟随所属 section 的 canModule
  soon?: boolean; // 待建：灰显不可点
  phase?: Phase; // 产品分期徽章（缺省=1）
  /**
   * 就绪度覆盖：本叶**前端静态功能完整且已实机验证**，无视 phase 直接解锁。
   *
   * 标 ready 的条件（三条全满足，沿用 powerbank/ops-web 的口径）：
   *   1. 该页写操作在 mock 层**真落库**（重开能读回），非伪实现
   *   2. 状态机/校验在 mock 层强制，非法迁移抛错
   *   3. **浏览器实机验证过**该功能的关键路径，不是只跑了单测
   *
   * 后端贯通状态另行追踪 —— 当前全站对着 mock 开发，ready ≠ 后端已通。
   * 注意：ready 只放宽 phase，不放宽 perm 与 soon。
   */
  ready?: boolean;
  /**
   * L2 分组标题。同一 group 的**相邻**叶子共用一个小标题；不设 group 的叶子平铺。
   * 约束：同 group 的叶子必须在 children 中相邻（nav.test.ts 保证）。
   */
  group?: string;
  /** 覆盖的矩阵条目（如 "P-11.1"）。仅供 nav.test.ts 做覆盖率校验与人工回溯。 */
  matrix?: string;
}

/** L1 导航项：一个 section = 一个权限模块 = 一个页面（children 是它的 tab/view 深链）。 */
export interface NavSection {
  key: string;
  label: string;
  icon: string; // lucide 图标名
  module: string; // 权限码模块前缀（canModule 过滤）
  href: string; // section 首页
  match?: string[]; // 路径归属前缀（默认 = href 的 path 部分）
  /**
   * 整 section 待建：Rail 上灰显不可点。
   * ⚠️ **页面还没建就必须标它** —— 静态导出下点进去是 404，而 sectionDefaultHref
   * 在没有可点叶子时会落回 section.href（那个路由并不存在）。nav.test.ts 锁这条。
   */
  soon?: boolean;
  phase?: Phase;
  pinBottom?: boolean; // Rail 固定底部
  children?: NavLeaf[];
}

export const NAV: NavSection[] = [
  // ── P-16.1 数据看板：无子功能，内容区全宽 ────────────────────────────────
  {
    key: "dashboard", label: "经营看板", icon: "LayoutDashboard", module: "dashboard", href: "/",
  },

  // ── P-11 商家治理（一期 M1-2 的平台侧主角）───────────────────────────────
  {
    key: "merchant", label: "商家治理", icon: "Store", module: "merchant", href: "/merchants",
    children: [
      { href: "/merchants", label: "入驻审核", perm: "merchant:apply:audit", group: "入驻与资质", matrix: "P-11.1", ready: true },
      { href: "/merchants?tab=list", label: "商家档案", perm: "merchant:merchant:read", group: "入驻与资质", matrix: "P-11.1", ready: true },
      { href: "/merchants?tab=categories", label: "类目授权", perm: "merchant:category:grant", group: "入驻与资质", matrix: "P-11.1" },
      // 准入与保证金：页面早就有这个 tab，菜单里一直漏登记 —— 于是它只能靠手改 URL 进去。
      // 放在本组末尾而不是 verify 后面：同 group 的叶子必须相邻（nav.test.ts 锁这条）
      // 用专属的 admission 码而不是 merchant:merchant:read：Perms.ROLE_PERMS 把这两个码
      // 给了**财务**，用商家读权限的话正好反过来 —— 财务看不到，商家运营却看得到。
      // （该码 2026-08-12 才补进 UI_PERM_MAP；此前未登记，can() 会判所有人无权限）
      { href: "/merchants?tab=admission", label: "准入与保证金", perm: "merchant:admission:read", group: "入驻与资质", matrix: "P-11.1" },
      { href: "/merchants?tab=verify", label: "认证标管理", perm: "merchant:verify:grant", group: "信用与处置", matrix: "P-11.1" },
      { href: "/merchants?tab=credit", label: "信用档案", perm: "merchant:merchant:read", group: "信用与处置", matrix: "P-11.1" },
      { href: "/merchants?tab=ban", label: "违规处置与封禁", perm: "merchant:merchant:ban", group: "信用与处置", matrix: "P-11.1" },
    ],
  },

  // ── P-10 门店主页治理（一期主获客路径）──────────────────────────────────
  {
    key: "store", label: "门店主页", icon: "LayoutTemplate", module: "store", href: "/stores",
    children: [
      { href: "/stores", label: "店招公告审核", perm: "store:page:audit", group: "模板与合规", matrix: "P-10.1", ready: true },
      // 主页模板配置依赖 C 端门店主页（C-ST-01）定稿，先做模板等于两头返工
      { href: "/stores?tab=template", label: "主页模板配置", perm: "store:page:read", group: "模板与合规", matrix: "P-10.1" },
      { href: "/stores?tab=qrcode", label: "店铺码生成导出", perm: "store:qrcode:export", group: "获客", matrix: "P-10.1", ready: true },
      { href: "/stores?tab=effect", label: "获客效果看板", perm: "store:page:read", group: "获客", matrix: "P-10.1", ready: true },
    ],
  },

  // ── P-3 商品与类目 ──────────────────────────────────────────────────────
  {
    key: "product", label: "商品与类目", icon: "Package", module: "product", href: "/products",
    children: [
      // 属性模板（3.1.2）与类目多语言（3.1.3）是类目行上的字段，在类目详情里看，不单独成页。
      { href: "/products", label: "三级类目树", perm: "product:category:read", group: "类目", matrix: "P-3.1", ready: true },
      // 商品审核（3.2.2）= 商品池按「待审核」筛；多语言文案审核（3.2.5）与多市场定价（3.2.6）
      // 都在商品抽屉里 —— 审文案时看不到商品本身是没法审的。
      { href: "/products?tab=skus", label: "商品池与审核", perm: "product:sku:read", group: "商品", matrix: "P-3.2", ready: true },
      // 待审队列单独成 tab（页面早有，菜单漏登记）。与「商品池与审核」的区别是
      // 那边是全量池按状态筛，这边只有待审队列 —— 审核员日常只用这一个
      { href: "/products?tab=audit", label: "商品审核队列", perm: "product:sku:audit", group: "商品", matrix: "P-3.2", ready: true },
      { href: "/products?tab=stock", label: "预售额度与超卖", perm: "product:stock:update", group: "库存与预售", matrix: "P-3.3", ready: true },
    ],
  },

  // ── P-4 交易订单 ────────────────────────────────────────────────────────
  {
    key: "order", label: "交易订单", icon: "ReceiptText", module: "order", href: "/orders",
    children: [
      { href: "/orders", label: "订单检索", perm: "order:order:read", group: "订单", matrix: "P-4.1", ready: true },
      { href: "/orders?tab=exception", label: "异常单处理", perm: "order:order:modify", group: "订单", matrix: "P-4.1" },
      { href: "/orders?tab=proxy", label: "代客下单/取消", perm: "order:order:proxy", group: "订单", matrix: "P-4.1" },
      { href: "/orders?tab=pay", label: "支付流水核对", perm: "order:pay:read", group: "支付", matrix: "P-4.2" },
      { href: "/orders?tab=repair", label: "掉单补偿", perm: "order:pay:repair", group: "支付", matrix: "P-4.2" },
      { href: "/orders?tab=close", label: "关单策略配置", perm: "order:pay:repair", group: "支付", matrix: "P-4.2" },
    ],
  },

  // ── P-5 履约调度（一期 M1-3，与 B 端核销台成对交付）──────────────────────
  {
    key: "fulfillment", label: "履约调度", icon: "Truck", module: "fulfillment", href: "/fulfillment",
    children: [
      { href: "/fulfillment", label: "到货批次与配车", perm: "fulfillment:batch:read", group: "到货与分拣", matrix: "P-5.1", ready: true },
      { href: "/fulfillment?tab=sorting", label: "按自提点汇总分拣", perm: "fulfillment:batch:read", group: "到货与分拣", matrix: "P-5.1", ready: true },
      { href: "/fulfillment?tab=redeem", label: "核销监控与逾期", perm: "fulfillment:redeem:read", group: "核销", matrix: "P-5.1", ready: true },
      { href: "/fulfillment?tab=overdue", label: "逾期规则配置", perm: "fulfillment:rule:update", group: "核销", matrix: "P-5.1", ready: true },
      { href: "/fulfillment?tab=express", label: "快递与轨迹", perm: "fulfillment:logistics:read", group: "物流", matrix: "P-5.2" },
      { href: "/fulfillment?tab=freight", label: "运费模板与超区", perm: "fulfillment:rule:update", group: "物流", matrix: "P-5.2" },
      { href: "/fulfillment?tab=carrier", label: "第三方运力配置", perm: "fulfillment:logistics:read", group: "物流", matrix: "P-5.2", phase: 2, ready: true },
    ],
  },

  // ── P-6 售后治理 ────────────────────────────────────────────────────────
  {
    key: "aftersale", label: "售后治理", icon: "Undo2", module: "aftersale", href: "/after-sales",
    children: [
      { href: "/after-sales", label: "售后工单池", perm: "aftersale:ticket:read", group: "处置", matrix: "P-6.1", ready: true },
      // 责任判定（6.1.4）并入裁决抽屉：判了责任才谈得上赔付归属，拆成两页会出现
      // 「裁决完了忘了判责」的空档。
      { href: "/after-sales?tab=intervene", label: "平台介入裁决", perm: "aftersale:ticket:handle", group: "处置", matrix: "P-6.1", ready: true },
      { href: "/after-sales?tab=fastrefund", label: "极速退阈值配置", perm: "aftersale:refund:approve", group: "规则", matrix: "P-6.1", ready: true },
      // 跨 section 深链：E4 的执行面在资金域（P-12 已交付），售后这边只负责打标记。
      // 保留入口是因为客服/财务是从售后单找过去的，不该让他们自己去猜在哪个菜单里。
      { href: "/finance?tab=refund-back", label: "退款回退分账", perm: "finance:settle:execute", group: "规则", matrix: "P-6.1", ready: true },
    ],
  },

  // ── P-7 营销活动 ────────────────────────────────────────────────────────
  {
    key: "marketing", label: "营销活动", icon: "Ticket", module: "marketing", href: "/marketing",
    children: [
      // 2026-08-06 合并：券的「发放/预算/核销效果」都是**同一行券上的动作与列**，
      // 拆三个菜单会让运营为了看一张券的效果在三页之间跳。四类活动同表（见页面注释）。
      { href: "/marketing", label: "券模板", perm: "marketing:coupon:read", group: "优惠券", matrix: "P-7.1", ready: true },
      { href: "/marketing?tab=issues", label: "发放记录", perm: "marketing:coupon:read", group: "优惠券", matrix: "P-7.1", ready: true },
      { href: "/marketing?tab=campaigns", label: "活动", perm: "marketing:campaign:update", group: "活动", matrix: "P-7.2", ready: true },
      { href: "/marketing?tab=slots", label: "首页楼层与 Banner", perm: "marketing:slot:update", group: "内容位", matrix: "P-7.3", ready: true },
      { href: "/marketing?tab=member", label: "会员卡与权益", perm: "marketing:member:update", group: "会员", matrix: "P-7.4", phase: 2, ready: true },
    ],
  },

  // ── P-8 团购与求团 ──────────────────────────────────────────────────────
  {
    key: "group", label: "团购与求团", icon: "Users", module: "group", href: "/groups",
    children: [
      // 团模板审核与团监控是同一张列表的两种看法（筛状态即可），合并为一个叶子
      { href: "/groups", label: "商家团", perm: "group:campaign:audit", group: "商家团", matrix: "P-8.1", ready: true },
      { href: "/groups?tab=demands", label: "需求单池与指派", perm: "group:demand:read", group: "求团撮合", matrix: "P-8.2", ready: true },
      { href: "/groups?tab=quotes", label: "改价留痕与毁约", perm: "group:demand:read", group: "求团撮合", matrix: "P-8.2", ready: true },
    ],
  },

  // ── P-9 增长与归因（B1 未拍板，见矩阵 §九）──────────────────────────────
  {
    key: "growth", label: "增长与归因", icon: "TrendingUp", module: "growth", href: "/growth",
    children: [
      // 优先级/窗口期/冲突处置/新客口径都是同一张规则表上的字段，拆四个菜单
      // 会让人以为它们能分别生效 —— 实际改任何一个都影响同一套归因。
      { href: "/growth", label: "归因规则", perm: "growth:attribution:read", group: "归因引擎", matrix: "P-9.1", ready: true },
      { href: "/growth?tab=traces", label: "归因链路审计", perm: "growth:attribution:read", group: "归因引擎", matrix: "P-9.1", ready: true },
      { href: "/growth?tab=fission", label: "邀请有礼配置", perm: "growth:fission:update", group: "裂变活动", matrix: "P-9.2", ready: true },
    ],
  },

  // ── P-12 结算与资金 ─────────────────────────────────────────────────────
  {
    key: "finance", label: "结算与资金", icon: "Wallet", module: "finance", href: "/finance",
    children: [
      // 分账指令/重试/报备状态都是结算单**行上的动作与列**，不单独成页
      { href: "/finance", label: "结算单与分账", perm: "finance:settle:read", group: "分账结算", matrix: "P-12.1", ready: true },
      { href: "/finance?tab=splits", label: "分账明细", perm: "finance:settle:read", group: "分账结算", matrix: "P-12.1", ready: true },
      // E4：队列由售后单的 refundSplitPending 派生（P-6.1 打标记，这里消费）
      { href: "/finance?tab=refund-back", label: "退款回退分账", perm: "finance:settle:execute", group: "分账结算", matrix: "P-12.1", ready: true },
      { href: "/finance?tab=rates", label: "分档费率与服务费", perm: "finance:rate:update", group: "费率", matrix: "P-12.1", ready: true },
      { href: "/finance?tab=withdraw", label: "提现审批", perm: "finance:withdraw:approve", group: "提现与税", matrix: "P-12.2", phase: 2, ready: true },
      { href: "/finance?tab=invoice", label: "发票与个税", perm: "finance:invoice:read", group: "提现与税", matrix: "P-12.2", phase: 2, ready: true },
    ],
  },

  // ── P-13 评价治理 ───────────────────────────────────────────────────────
  {
    key: "review", label: "评价治理", icon: "Star", module: "review", href: "/reviews",
    children: [
      { href: "/reviews", label: "评价审核", perm: "review:review:audit", group: "审核", matrix: "P-13.1", ready: true },
      { href: "/reviews?tab=appeals", label: "恶意差评申诉裁决", perm: "review:review:audit", group: "审核", matrix: "P-13.1", ready: true },
      // 刷评识别（13.1.5）并入审核队列的筛选项：发现刷评后要做的动作就在那条队列里，
      // 单独一页会变成"看得见但没法处置"的孤岛。
      { href: "/reviews?tab=score", label: "评分算法参数", perm: "review:score:update", group: "评分", matrix: "P-13.1", ready: true },
    ],
  },

  // ── P-14 消息与客服 ─────────────────────────────────────────────────────
  {
    key: "message", label: "消息与客服", icon: "MessageSquare", module: "message", href: "/messages",
    children: [
      // 频控（14.1.4）与模板、推送任务同页：它是发送前的闸门，单独成页就没人看了。
      // 代客留痕（14.2.3）在工单抽屉里 —— 它是工单处理的一部分，不是独立台账。
      { href: "/messages", label: "消息模板与推送", perm: "message:template:read", group: "触达", matrix: "P-14.1", ready: true },
      { href: "/messages?tab=tickets", label: "客服工单与代客留痕", perm: "message:ticket:read", group: "客服", matrix: "P-14.2", ready: true },
      { href: "/messages?tab=faq", label: "帮助中心维护", perm: "message:faq:update", group: "客服", matrix: "P-14.2", ready: true },
    ],
  },

  // ── P-2 社区与网点 ──────────────────────────────────────────────────────
  {
    key: "community", label: "社区与网点", icon: "MapPin", module: "community", href: "/communities",
    // 2026-08-06 合并：原先按矩阵 L4 逐条铺了 6 个叶子，实现时收成 3 个。
    // 开城开关(2.1.2)、围栏(2.1.3)、启停迁移(2.2.2)、服务费费率(2.2.4) 都是**同一行数据上的
    // 字段与动作**，不是独立页面 —— 拆成菜单会让运营在两个页面之间来回找同一个自提点。
    // 需求没有丢：它们落在列表列与行内操作上，matrix 编号仍由下面三个叶子承载。
    children: [
      { href: "/communities", label: "社区网格", perm: "community:community:read", group: "社区网格", matrix: "P-2.1", ready: true },
      // 商家提报的社区：页面早有这个 tab，菜单里一直漏登记
      { href: "/communities?tab=applies", label: "商家提报", perm: "community:community:read", group: "社区网格", matrix: "P-2.1", ready: true },
      { href: "/communities?tab=pickups", label: "自提点", perm: "community:pickup:read", group: "自提点", matrix: "P-2.2", ready: true },
      { href: "/communities?tab=neighbor", label: "临时点监控", perm: "community:pickup:read", group: "自提点", matrix: "P-2.2", ready: true },
    ],
  },

  // ── P-15 素材与内容 ─────────────────────────────────────────────────────
  {
    key: "content", label: "素材与内容", icon: "Images", module: "content", href: "/contents",
    children: [
      // 分发范围是素材行上的字段，不是另一张表：一份素材投给谁，和素材本身是一件事
      { href: "/contents", label: "素材中心与分发", perm: "content:material:read", group: "素材", matrix: "P-15.1", ready: true },
      { href: "/contents?tab=audit", label: "种草内容审核", perm: "content:material:audit", group: "内容", matrix: "P-15.2", phase: 2, ready: true },
      { href: "/contents?tab=rank", label: "榜单与问答", perm: "content:material:update", group: "内容", matrix: "P-15.2", phase: 2, ready: true },
    ],
  },

  // ── P-16.2 风控 ─────────────────────────────────────────────────────────
  {
    key: "risk", label: "风控", icon: "ShieldAlert", module: "risk", href: "/risk",
    children: [
      // 三类识别（刷单/异常裂变/恶意退款）同表用 type 筛：拆三个菜单会让
      // 「这个用户同时命中几类」看不出来，而那恰恰最该优先处理。
      { href: "/risk", label: "风险事件", perm: "risk:rule:read", group: "识别", matrix: "P-16.2", ready: true },
      { href: "/risk?tab=blacklist", label: "黑名单与申诉", perm: "risk:blacklist:update", group: "处置", matrix: "P-16.2", ready: true },
      { href: "/risk?tab=rules", label: "拦截规则配置", perm: "risk:rule:update", group: "处置", matrix: "P-16.2", ready: true },
    ],
  },

  // ── P-1 账号与权限 ──────────────────────────────────────────────────────
  {
    key: "iam", label: "员工与权限", icon: "UserCog", module: "iam", href: "/iam",
    children: [
      // 数据域授权（1.1.3）是员工行上的动作，二次校验（1.1.5）是动作上的一层 ——
      // 两者都不单独成页：拆出去就会出现"改完角色忘了配数据域"的空档。
      { href: "/iam", label: "员工账号与数据域", perm: "iam:staff:read", group: "账号", matrix: "P-1.1", ready: true },
      { href: "/iam?tab=roles", label: "角色与权限", perm: "iam:role:grant", group: "账号", matrix: "P-1.1", ready: true },
      // 菜单顺序单独成页，而不是塞在角色抽屉里 —— **调序是全局的，与角色无关**。
      // 放在抽屉里等于暗示「这个顺序属于这个角色」，而且预置角色的抽屉写着「只读」，
      // 旁边却有能点的上移下移，自相矛盾。
      // 复用 iam:role:grant：能配权限的人才该动菜单结构，不为它新增一个码。
      { href: "/iam?tab=menu", label: "菜单顺序", perm: "iam:role:grant", group: "账号", matrix: "P-1.1", ready: true },
      { href: "/iam?tab=audit", label: "操作审计日志", perm: "iam:audit:read", group: "审计", matrix: "P-1.1", ready: true },
    ],
  },

  // ── P-17 系统配置（固定在 Rail 底部）────────────────────────────────────
  {
    key: "system", label: "系统配置", icon: "Settings", module: "system", href: "/system", pinBottom: true,
    children: [
      // 皮肤/回落语言/规则文案都是"下发给 C 端的东西"，放同一页；市场与开关各自成 tab
      { href: "/system", label: "外观与规则文案", perm: "system:theme:update", group: "外观与语言", matrix: "P-17.1", ready: true },
      { href: "/system?tab=market", label: "市场/货币/汇率", perm: "system:param:read", group: "外观与语言", matrix: "P-17.1", ready: true },
      { href: "/system?tab=flags", label: "开关与灰度", perm: "system:param:read", group: "运行配置", matrix: "P-17.1", ready: true },
      // 下面三个页面早有 tab、菜单一直漏登记 —— 都是「平台一共允许经营什么」这组配置。
      // perm 用 read 码而不是各自的写码（env:switch / category:manage）：
      // 叶子的 perm 决定**能不能看见这个入口**，写权限由页面内部各自判。
      // 用写码的话，有权查看配置但无权改的人在菜单里根本找不到这一页。
      { href: "/system?tab=industry", label: "行业与小微白名单", perm: "system:param:read", group: "经营范围", matrix: "P-17.1", ready: true },
      { href: "/system?tab=authCode", label: "经营授权码", perm: "system:param:read", group: "经营范围", matrix: "P-17.1", ready: true },
      { href: "/system?tab=scope", label: "经营范围开关", perm: "system:param:read", group: "经营范围", matrix: "P-17.1", ready: true },
    ],
  },
];

// ── 纯函数 helper（无 React 依赖，可单测） ──────────────────────────────

/** trailingSlash:true 下 pathname 带尾斜杠，比较前归一化。 */
export const normPath = (p: string) => p.replace(/\/+$/, "") || "/";

/** 拆 href 为 path + tab + view。 */
export function leafParts(href: string): { path: string; tab: string | null; view: string | null } {
  const [path, qs] = href.split("?");
  const sp = new URLSearchParams(qs);
  return { path: normPath(path), tab: sp.get("tab"), view: sp.get("view") };
}

/**
 * L1 可见性 = canModule。
 * ai-shop 的 11 个角色**都是平台内部岗位**，不存在"外部伙伴只能看自己那几页"的门户概念
 * —— 那是上游项目的场景，提取时已删（见 components/README.md 的去留判据）。
 * 本项目的受限视角由**数据域**表达（矩阵 §2.3），不是由另一套菜单表达。
 */
export function visibleSections(perms: string[] | undefined,
                                serverHrefs?: Set<string>,
                                nav: NavSection[] = NAV): NavSection[] {
  /*
   * **服务端菜单一旦到手就以它为准**（它是按 sys_role_point 算出来的），
   * 而且它**包含后端未实现的项** —— 那些项要渲染成灰显、不可点。
   *
   * 走 can() 的话它们在这一步就被过滤掉了，灰显分支永远走不到 ——
   * 第一次接前端时就是这么漏的：分支写好了，而上游先把数据筛没了。
   */
  if (serverHrefs?.size) {
    return nav.filter((s) => !(s.children?.length)
        || s.children.some((l) => serverHrefs.has(l.href)));
  }
  return nav.filter((s) => canModule(perms, s.module));
}

/** L3 可见性 = leaf.perm ? can() : 跟随 section。phase-locked 叶子保留（灰显）。 */
export function visibleLeaves(section: NavSection, perms: string[] | undefined,
                              serverHrefs?: Set<string>): NavLeaf[] {
  if (serverHrefs?.size) {
    return (section.children ?? []).filter((l) => serverHrefs.has(l.href));
  }
  return (section.children ?? []).filter((l) => (l.perm ? can(perms, l.perm) : true));
}

/**
 * 把可见叶子按 group 聚成连续段（L2 分组）。
 * 只合并**相邻**同名 group（不跨段合并）——保证渲染顺序 = 数据顺序，不隐式重排。
 */
export function groupedLeaves(leaves: NavLeaf[]): { group?: string; leaves: NavLeaf[] }[] {
  const out: { group?: string; leaves: NavLeaf[] }[] = [];
  for (const leaf of leaves) {
    const last = out[out.length - 1];
    if (last && last.group === leaf.group) last.leaves.push(leaf);
    else out.push({ group: leaf.group, leaves: [leaf] });
  }
  return out;
}

/** 叶子是否被分期屏蔽 = 未就绪 且 超出当前分期。 */
export function isLeafLocked(leaf: NavLeaf): boolean {
  if (leaf.ready) return false;
  return isPhaseLocked(leaf.phase);
}

/** section 是否被产品分期屏蔽（整 section phase 或所有叶子均被锁）。 */
export function isSectionLocked(section: NavSection, perms: string[] | undefined): boolean {
  if (isPhaseLocked(section.phase)) return true;
  const leaves = visibleLeaves(section, perms);
  return leaves.length > 0 && leaves.every((l) => isLeafLocked(l));
}

/** section 的路径归属前缀（含子路径如 /merchants/detail）。 */
function sectionMatchPrefixes(section: NavSection): string[] {
  return section.match ?? [leafParts(section.href).path];
}

/**
 * 由 pathname 反推当前 section：最长前缀匹配；"/" 仅精确匹配。
 * 不做 RBAC 过滤——URL 已到达即需正确归属（页面自身有权限兜底）。
 */
export function findActiveSection(pathname: string, perms?: string[],
                                  serverHrefs?: Set<string>,
                                  nav: NavSection[] = NAV): NavSection | undefined {
  const p = normPath(pathname);
  /*
   * **同样要吃服务端菜单**：这里漏传的话，二级导航拿不到 section，
   * 于是整块不渲染 —— 而上面的一级导航已经显示了那个分区。
   * 症状是「点进去左边空了一栏」，实测撞到过：可见性判断散在三个函数里，
   * 补了两个漏了第三个。
   */
  const pool = serverHrefs?.size ? visibleSections(perms, serverHrefs, nav)
      : (perms?.length ? visibleSections(perms, undefined, nav) : nav);
  let best: { section: NavSection; len: number } | undefined;
  for (const section of pool) {
    for (const prefix of sectionMatchPrefixes(section)) {
      const hit = prefix === "/" ? p === "/" : p === prefix || p.startsWith(prefix + "/");
      if (hit && (!best || prefix.length > best.len)) best = { section, len: prefix.length };
    }
  }
  return best?.section;
}

/**
 * 当前 section 的可见叶子中，命中项下标：
 * 先按 path+query 精确匹配；section 首页（无 tab/view）默认高亮首个可点叶子。未命中返回 -1。
 */
export function activeLeafIndex(
  leaves: NavLeaf[], pathname: string, tab: string | null, view: string | null,
): number {
  const p = normPath(pathname);
  const exact = leaves.findIndex((l) => {
    if (l.soon || isLeafLocked(l)) return false;
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  if (exact >= 0) return exact;
  if (!tab && !view) {
    return leaves.findIndex((l) => !l.soon && !isLeafLocked(l) && leafParts(l.href).path === p);
  }
  return -1;
}

/** section 的默认落地地址：首个可点叶子（排除 soon 和 phase-locked），无则 section 首页。 */
export function sectionDefaultHref(section: NavSection, perms: string[] | undefined): string {
  const leaf = visibleLeaves(section, perms).find((l) => !l.soon && !isLeafLocked(l));
  return leaf?.href ?? section.href;
}

/** 叶子是否不可点：待建 或 分期锁定（渲染层统一判定）。 */
export function isLeafDisabled(leaf: NavLeaf): boolean {
  return !!leaf.soon || isLeafLocked(leaf);
}

/**
 * 按 URL 判断当前路由是否被产品分期锁定（页面级兜底用）。
 * 返回锁定它的 Phase，未锁定返回 undefined。
 */
export function routeLockedPhase(
  pathname: string, tab: string | null, view: string | null, perms: string[] | undefined,
): Phase | undefined {
  const section = findActiveSection(pathname, perms);
  if (!section) return undefined;
  if (isPhaseLocked(section.phase)) return section.phase;
  const p = normPath(pathname);
  const leaves = visibleLeaves(section, perms);
  let leaf = leaves.find((l) => {
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  if (!leaf && !tab && !view) {
    leaf = leaves.find((l) => leafParts(l.href).path === p) ?? leaves[0];
  }
  return leaf && isLeafLocked(leaf) ? leaf.phase : undefined;
}

/**
 * 面包屑：L1 › 分组 › 子功能。
 * 分组是视觉聚类不是可导航节点，仅作不可点的中间项；叶子无 group 时退化为两级。
 *
 * **整条不可点**（2026-08-12 评审结论）：三级信息在壳里都已有更强的呈现 ——
 * L1 = Rail 高亮项（带图标），L2 = SecondaryNav 的分组小标题（当前叶子就高亮在其下），
 * L3 = TabHeader 的 h1（那个 h1 还能点开切 tab）。面包屑给链接只是把同一个跳转
 * 开第二个入口，且 L1 点了落在与点 Rail 完全相同的页面 —— 看着能点却什么也没多做，
 * 是误导。这里只做「你在哪儿」的指示器，不做导航。
 */
export function breadcrumb(
  pathname: string, tab: string | null, view: string | null, perms: string[] | undefined,
  serverHrefs?: Set<string>, nav: NavSection[] = NAV,
): string[] {
  const section = findActiveSection(pathname, perms, serverHrefs, nav);
  if (!section) return [];
  const crumbs = [section.label];
  const leaves = visibleLeaves(section, perms);
  const idx = activeLeafIndex(leaves, pathname, tab, view);
  if (idx >= 0) {
    const leaf = leaves[idx];
    if (leaf.group) crumbs.push(leaf.group);
    if (leaf.label !== section.label) crumbs.push(leaf.label);
  }
  return crumbs;
}

/**
 * 服务端菜单对静态 nav 的**文案覆盖层**。key = href（section 用 section.href）。
 *
 * 只覆盖「展示信息」：名字、分组、图标、排序。**不覆盖结构** ——
 * 有哪些 section、叶子挂在谁下面、权限码是什么，仍由 nav.ts 决定。
 */
export interface NavOverlayEntry {
  name?: string;
  group?: string;
  icon?: string;
  sort?: number;
}
/**
 * **section 与 leaf 分两张表**，不能合成一张按 href 索引的扁平表。
 *
 * 因为 section 的 href 与它默认叶子的 href **是同一个串**（`/merchants` 既是
 * 商家治理这个分区，也是「入驻审核」这个叶子）。合成一张表时后写的那条会盖掉前一条，
 * 表现是分区名变成了它第一个叶子的名字 —— Rail 上「商家治理」显示成「入驻审核」。
 */
export interface NavOverlay {
  sections?: Record<string, NavOverlayEntry>;
  leaves?: Record<string, NavOverlayEntry>;
}

/**
 * 用服务端菜单覆盖静态 nav 的文案与顺序。**纯函数，可单测。**
 *
 * <h3>为什么是「覆盖」而不是「换源」</h3>
 * 库里（`sys_function` / `sys_function_point`）已经存着菜单与 tab 的全部展示信息，
 * 而且是 `gen-perm-seed.mjs` 从这份 nav.ts 生成的 —— 两边同源。
 * 让服务端全权接管的代价是：静态导出的 SPA 首屏要等接口回来才有菜单（会闪），
 * 且面包屑/命令面板/分期门禁/sectionDefaultHref 这些纯函数全要改成异步数据驱动。
 *
 * <p>所以取中间：**拿到就用库里的，没拿到就用本地的**。运营在配置页改了菜单名，
 * 前端下一次拉 `/ops/menu` 就变；接口挂了则退回本地文案，菜单不会突然变空
 * —— 后者用户读作「系统坏了」，比文案旧一会儿坏得多。
 *
 * @param nav     静态菜单树（默认 {@link NAV}）
 * @param overlay href → 覆盖项；`undefined` 或空表示原样返回**同一个引用**
 *                （让 useMemo 的下游不必要地重渲染是这里最容易犯的错）
 */
export function overlayNav(nav: NavSection[], overlay: NavOverlay | undefined): NavSection[] {
  const secOv = overlay?.sections ?? {};
  const leafOv = overlay?.leaves ?? {};
  if (Object.keys(secOv).length === 0 && Object.keys(leafOv).length === 0) return nav;
  /**
   * 按服务端 sort 重排；**没有 sort 的项跟着它前面那一项走**。
   *
   * 此前的规则是「全部兄弟都有 sort 才排，否则原样返回」—— 看着安全，
   * 实测是个静默失效：`/ops/menu` 只返回**有菜单功能点**的分区，
   * 而「经营看板」没有子功能，于是它不在服务端菜单里；少这一个，
   * 整个 L1 排序就被整体丢弃 —— 运营在配置页点了上移，界面纹丝不动，
   * 而没有任何报错。
   *
   * 现在：缺 sort 的项取「前一项的有效序 + 极小量」，从而**留在它原来的邻居旁边**，
   * 其余按服务端顺序。首项缺 sort 时排在最前（哨兵值），与它在 nav.ts 里的位置一致。
   */
  const sorted = <T>(items: T[], table: Record<string, NavOverlayEntry>, keyOf: (t: T) => string) => {
    if (!items.some((it) => table[keyOf(it)]?.sort !== undefined)) return items;
    let prev = Number.MIN_SAFE_INTEGER;
    const keyed = items.map((it, i) => {
      const s = table[keyOf(it)]?.sort;
      prev = s ?? prev + 1e-6;
      if (s === undefined && process.env.NODE_ENV !== "production") {
        console.warn(`[nav] ${keyOf(it)} 在服务端菜单里没有 sort —— 它会跟着前一项走。`
          + "如果它本该可调序，看看 /ops/menu 为什么没返回它");
      }
      return { it, i, k: prev };
    });
    return keyed.sort((a, b) => (a.k - b.k) || (a.i - b.i)).map((x) => x.it);
  };
  const out = nav.map((s) => {
    const ov = secOv[s.href];
    const children = s.children?.map((l) => {
      const lv = leafOv[l.href];
      return lv ? { ...l, label: lv.name ?? l.label, group: lv.group ?? l.group } : l;
    });
    return {
      ...s,
      label: ov?.name ?? s.label,
      icon: ov?.icon ?? s.icon,
      children: children && sorted(children, leafOv, (l) => l.href),
    };
  });
  return sorted(out, secOv, (s) => s.href);
}

/**
 * 页面 tab 的标签源 —— **nav.ts 是 tab 名的 SSOT**。
 *
 * <p>此前页面在自己的 copy.ts 里另写一份 tab 文案，与菜单叶子各说各的：
 * 菜单写「店招公告审核」，页面标题写「合规审核」，同一个东西在同一屏上两个名字
 * （2026-08-12 全量核查：38 处不一致）。而两处都"看着对"，没人会发现。
 *
 * 现在页面只声明**有哪些 tab、什么顺序**，名字一律回 nav.ts 取。
 *
 * <p>对齐规则与 {@link activeLeafIndex} 一致：**第一个 key 对应不带 `?tab=` 的那条叶子**
 * （section 首页即默认 tab）。找不到对应叶子返回 `undefined`，由调用方决定怎么办
 * —— 不在这里编一个兜底名字，那只会把「漏登记」变成一个看不出来的问题。
 *
 * @param path 页面路径（不含 query），如 `/stores`
 * @param keys 页面的 tab key，顺序即展示顺序
 */
export function navTabs(
  path: string, keys: readonly string[], nav: NavSection[] = NAV,
): { key: string; label: string | undefined }[] {
  return keys.map((key, i) => ({ key, label: leafForTab(nav, path, key, i)?.label }));
}

/**
 * (path, tabKey) → 对应的菜单叶子。**第一个 key 对应不带 `?tab=` 的那条**
 * （section 首页即默认 tab），与 {@link activeLeafIndex} 同一条对齐规则。
 *
 * 抽出来是因为 {@link navTabs}（取名字）与 {@link visibleTabKeys}（判权限）
 * 必须用**同一套对齐**：两处各写一遍的话，某个 tab 会出现「名字取到了、权限判错了」
 * 这种只在特定角色下才显形的错。
 */
function leafForTab(
  nav: NavSection[], path: string, key: string, index: number,
): NavLeaf | undefined {
  const p = normPath(path);
  return nav.flatMap((s) => s.children ?? []).find((l) => {
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    return parts.tab === key || (index === 0 && parts.tab === null && parts.view === null);
  });
}

/**
 * 本页有哪些 tab 是**当前这个人有权限看**的。
 *
 * <h3>与菜单同一口径 —— 而且是同一个函数</h3>
 * 判定直接调 {@link visibleLeaves}，不另写一套 `can()`。
 * 另写一套的结果一定是两边分岔：菜单藏了、tab 还在（2026-08-12 实测就是这样，
 * `/system` 菜单 3 条而 tab 6 条），而**没有权限的人照样点得动那个 tab**，
 * 只靠接口 403 兜底。
 *
 * <h3>两条 fail-open</h3>
 * 1. **判不了就不判**：`perms` 与 `serverHrefs` 都为空时（还没登录完 / 接口没回来），
 *    原样返回。`can(undefined, x)` 恒 false，不特判的话整条 tab 会凭空消失，
 *    而用户读作「功能坏了」。
 * 2. **一个都不剩时只留默认那一个**：这说明这人本就不该进这个页面，菜单已经拦住了。
 *    给白页不如让页面正常渲染、由接口去拒绝（至少有错误码可查）；
 *    但**不能原样返回全部** —— 那等于把他无权的功能名字一条条摊给他看。
 */
export function visibleTabKeys(
  path: string, keys: readonly string[],
  perms: string[] | undefined, serverHrefs?: Set<string>, nav: NavSection[] = NAV,
): string[] {
  if (!serverHrefs?.size && !perms?.length) return [...keys];
  const p = normPath(path);
  const section = nav.find((s) => leafParts(s.href).path === p);
  if (!section) return [...keys];
  const visible = new Set(visibleLeaves(section, perms, serverHrefs).map((l) => l.href));
  const order = new Map((section.children ?? []).map((l, i) => [l.href, i]));
  const kept = keys
    .map((key, i) => ({ key, leaf: leafForTab(nav, path, key, i) }))
    // 菜单里查无此条：交给守卫去红，不在运行时把它藏掉
    .filter(({ leaf }) => (leaf ? visible.has(leaf.href) : true))
    /*
     * **按菜单顺序出，不按页面 TAB_KEYS 的顺序**。
     *
     * 顺序的真源是库（sys_function_point.sort），运营在配置页调完序，
     * 菜单与 tab 条要一起变。跟着页面里写死的数组走的话，调完序两边就不一致了
     * —— 而那正是这一整轮在收口的那类问题。
     * 查不到叶子的 key 排在最后：它本来就不该存在，别让它插在中间。
     */
    .sort((a, b) => (order.get(a.leaf?.href ?? "") ?? Number.MAX_SAFE_INTEGER)
                  - (order.get(b.leaf?.href ?? "") ?? Number.MAX_SAFE_INTEGER))
    .map(({ key }) => key);
  return kept.length ? kept : keys.slice(0, 1);
}

/** 命令面板的一条候选。label/section/group 都是 nav.ts 的**中文源串**，渲染前过 tNav。 */
export interface NavSearchEntry {
  href: string;
  label: string;
  section: string; // 所属 L1 的 label（= label 本身时表示这条就是 L1）
  group?: string; // L2 分组
  isSection: boolean;
}

/**
 * 命令面板（⌘K）的候选集：可见的 L1 + 可见且**可点**的 L3。
 *
 * 与 SecondaryNav 的取舍不同：那里待建/锁定项要渲染成灰显（藏起来运营就不知道
 * 平台规划了这个功能）；这里**直接排除**它们 —— 面板是「去某处」的工具，
 * 列出一条去不了的结果只是让人多按一次方向键。功能的可发现性由常驻面板负责，
 * 两者分工不同，不要为了"一致"把灰条塞进搜索结果。
 *
 * 权限与后端实现状态全部沿用 visibleSections/visibleLeaves（含 serverHrefs 口径），
 * 不在这里另写一套判断 —— 另写一套的结果一定是两边迟早不一致。
 */
export function navSearchEntries(
  perms: string[] | undefined, serverHrefs?: Set<string>, nav: NavSection[] = NAV,
): NavSearchEntry[] {
  const out: NavSearchEntry[] = [];
  for (const section of visibleSections(perms, serverHrefs, nav)) {
    if (section.soon || isPhaseLocked(section.phase)) continue;
    const leaves = visibleLeaves(section, perms, serverHrefs).filter((l) => !isLeafDisabled(l));
    // L1 自己也可搜：落地页与点 Rail 一致（无子功能的 section 用 section.href）
    out.push({
      href: leaves[0]?.href ?? section.href,
      label: section.label, section: section.label, isSection: true,
    });
    for (const leaf of leaves) {
      if (leaf.label === section.label) continue; // 与 L1 那条重复
      out.push({ href: leaf.href, label: leaf.label, section: section.label, group: leaf.group, isSection: false });
    }
  }
  return out;
}

/**
 * 面板的匹配规则：查询按空白分词，**每个词**都要在候选串里出现（大小写不敏感）。
 *
 * 分词而非整串包含，是为了让「商家 档案」「merchant profile」这种
 * 跨层级的输入也能命中 —— 候选串由调用方拼成「L1 分组 叶子」，
 * 词序不该决定成败。
 */
export function matchesQuery(haystack: string, query: string): boolean {
  const tokens = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (!tokens.length) return true;
  const hay = haystack.toLowerCase();
  return tokens.every((tk) => hay.includes(tk));
}
