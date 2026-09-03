// 商家主体：证照与进件、员工与角色、套餐、结算与欠款、跨店汇总
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type {
  CATEGORY_TYPE,
  SERVICE_SCOPE,
} from "@shared/utils/constants";
import type { CurrencyCode, ServiceScope } from "./core";
import type { PickupFeeMode } from "./fulfillment";
import type { Goods } from "./product";
import type { Review } from "./review";
import type { Store, StoreRole, StoreStatus } from "./store";
import type { AfterSaleStatus, AfterSaleType, Order } from "./trade";
import type { GrantType } from "./user";

/** 商家的一条发分服务费记录：一单一条，来自 `stl_bill.points_fee_minor` */
export interface MerchantPointsRecord {
  /** 结算单号 */
  settleNo: string;
  /** 关联子单，商家据此对到具体订单 */
  subOrderNo: string;
  /** 本单发放的积分数 */
  points: number;
  /** 本单的发分服务费（分）。**这是商家唯一感知到的积分成本** */
  feeMinor: number;
  /** 账期 `YYYYMM` */
  period: string;
  /** 计提时间（支付成功时），不是分账时间 —— 两者相差一个售后期 */
  at: number;
}
/**
 * 商家的积分成本视图。**单位是钱，不是分**。
 *
 * 商家只感知**一件事**：开了积分，每笔订单要付一笔发分服务费。
 * 他**看不到**用户抵了多少分、平台补了多少、资金池 —— 对他而言订单就是全额，
 * 收到的是「订单金额 − 各项费用」（V34）。
 *
 * 所以这里没有 income/net：商家侧不存在「积分兑付进账」这个概念。
 */
export interface MerchantPointAccount {
  /** 本期发分服务费支出（分）。**商家唯一感知到的积分成本** */
  periodExpenseMinor: number;
  /** 当前账期标识，如 `2026-08` */
  period: string;
  /** 本店积分是否生效 —— 全局 AND 社区 AND 主体非小微 AND 本店开关 */
  enabled: boolean;
  /**
   * 不生效的原因，直接展示给商家。
   *
   * 小微主体要说「升级为个体工商户后可开启」，不能说「本店未开启积分」——
   * 后者会让商家去开一个他根本开不了的开关。
   */
  disabledReason?: string;
  /** 平台按行业强制开，商家不可自行关闭 */
  forced: boolean;
}
// ---------------------------------------------------------------- 商家
//
// 数据模型从一开始就按**多商家**建：merchantNo 贯穿商品/订单/评价/结算。
// 一期平台方是唯一入驻方，所有数据都挂在它名下 —— 二期开放第三方入驻是配置变更，不是重构。
// 形态与拆分时机见 docs/technical/ADR/ADR-001。

/**
 * 商家主体类型 —— **权威口径取通道侧**（ADR-010）。
 *
 * 主体类型的唯一硬约束来自支付通道：能不能进件、要什么资质、钱打到个人还是对公。
 * 展示名反而可以随便改。让权威贴着约束走，映射就只需要一个方向。
 *
 * 规则（要不要执照、受不受行业白名单限制、结算账户形态）在
 * `sys_merchant_subject` 表里，随通道调整；**这里只管取值域**。
 * 端上取 `GET /common/master-data`，不要在页面里写死。
 *
 * <p><b>不叫 `SubjectType`</b>：那个名字在平台端已经是**风控主体**
 * （DEVICE/MERCHANT/USER）。两个不同的概念同名，读代码的人迟早会把
 * 一个当成另一个 —— 类型对齐守卫正是为此存在的。
 */
/**
 * 资金路径：**钱先进谁的账户**。与 `mch_entity.funds_mode` 同值。
 *
 * ⚠️ **与「经营模式」（谁是销售主体）正交，不要合并** ——
 * 合成一个枚举后，「直连 + 自营」（钱进商家户却说平台是卖方）
 * 这种非法组合在类型上就是可表达的（同 ADR-013 教训）。
 *
 * 结算侧「要不要给积分补差」判的是**这一个**：
 * 钱在商家二级户才需要补进去，钱在平台户是平台自己少收。
 */
export type FundsMode = "AGGREGATED" | "DIRECT";
export type MerchantSubject = "NATURAL_PERSON" | "INDIVIDUAL" | "ENTERPRISE";
/**
 * 经营资格（轴①，法定）：**决定能不能交易**，与通道无关。
 *
 * - `REGISTERED` 已办市场主体登记（有营业执照）
 * - `EXEMPT` 依法免登记（电商法 §10 四类情形）—— **是合法经营者，不是无资质**
 * - `UNREGISTERED` 应登记而未登记 —— 违法经营，平台不得提供交易能力
 */
export type BizQualification = "REGISTERED" | "EXEMPT" | "UNREGISTERED";
/**
 * 免登记情形（电商法 §10）。
 *
 * ⚠️ **只有 `PETTY` 受 10 万元/年 约束**，其余三类无金额上限 ——
 * 农户卖 50 万自产柿饼仍然免登记。四类混起来监控会误伤前三类。
 */
export type ExemptType = "AGRI" | "HANDCRAFT" | "SERVICE" | "PETTY";
/**
 * @deprecated 用 {@link MerchantSubject}。旧取值 `PLATFORM/COMPANY/INDIVIDUAL` 已废弃 ——
 * 其中 `PLATFORM`（平台自营）**不是一种主体类型**：平台自营的主体也是个企业，
 * 「自营」是归属标记，混进主体枚举里让这一列同时承担了两件事。
 * 一期没有真实的自营商家，暂不为它单开字段。
 */
export type MerchantType = MerchantSubject;
/** 商品卡/详情上挂的商家简要信息 */
export interface MerchantBrief {
  /** 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） */
  merchantNo: string;
  /**
   * 这单是不是**平台自营**（销售主体是平台）。
   *
   * **必须显示出来 —— 电商法 §37 要求平台以显著方式区分标记自营业务，
   * 不得误导消费者。这是法定义务，不是产品选择。**
   *
   * 而它同时是资金模式合法性的一部分：归集路径下平台是销售主体，
   * 页面上却让消费者以为在跟商家交易，四流就不一致了（ADR-017 §3.4）。
   *
   * ⚠️ 自营时**商家信息照常展示**（供货商、产地、门店、评分）——
   * 要禁的是把销售方指给商家的**表述**，不是商家信息本身。
   * 见 `packages/shared/tests/seller-statement.test.ts` 的禁用词表。
   */
  selfOperated?: boolean;
  /** 店铺名 */
  name: string;
  /** 店铺 logo URL */
  logo: string;
  /** 综合评分，0–5，保留一位小数。**0 分要配合 `ratingCount` 一起看** */
  rating: number;
  /**
   * 计入评分的评价条数。
   *
   * **没有它就分不清「0 分」和「还没人评过」** —— 而这两件事对买家是相反的信号：
   * 一家 0 分的店是被人打差评打出来的，一家没人评过的店只是新开的。
   * 端上按 `ratingCount === 0` 显示「暂无评价」，不要显示 0 颗星。
   */
  ratingCount: number;
  /** 是否通过资质认证 */
  verified: boolean;
  /** 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 */
  breachCount: number;
}
/** 消费过的商家（「我买过的」列表用） */
export interface VisitedMerchant extends Merchant {
  /** 在该商家的下单次数 */
  orderCount: number;
  /** 最近一次下单时间 */
  lastOrderAt: number;
}
export interface Merchant extends MerchantBrief {
  /** 商家类型：平台自营 / 企业 / 个体 */
  type: MerchantType;
  /** 店铺简介 */
  desc: string;
  /**
   * 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。
   * 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。
   */
  serviceScope: ServiceScope;
  /** 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 */
  serviceCommunityNos: string[];
  /** 覆盖哪个城市。**仅 scope=CITY 时有意义** */
  serviceCityCode?: string;
  /** 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 */
  distance?: number;
  /** 累计订单量（评分权重之一） */
  salesCount: number;
  /** 参与评分的评价条数 */
  ratingCount: number;
  /** 在售商品数 */
  goodsCount: number;
  /** 店铺地址。纯线上商家可能没有 */
  address?: string;
  /** 营业时间文案 */
  openHours?: string;
  /** 入驻时间 */
  /** 入驻时间 */
  joinedAt: number;
  /** 店铺标签，如「生鲜」「次日达」。展示用，不参与筛选 */
  tags: string[];
  /** 分维度评分：商品/服务/时效 */
  scores: { goods: number; service: number; speed: number };
}
// ---------------------------------------------------------------- 订单


/* ────────────────────────────────────────────────────────────────────────────
 * 以下这些类型此前都是**内联在 interface 里的字面量联合** —— 值是对的，
 * 但没有单一声明处：对账工具扫不到、别处要用只能再抄一遍、改名时必漏。
 * `CATEGORY_TYPE` 出事前正是这个状态。
 *
 * 具名化的过程本身就暴露了三对**异名同义**（都是内联时看不见的）：
 *   · `feeMode` 与 ops-web 的 `PickupFeeMode` 同值
 *   · `subjectType`（风控）与 ops-web 的 `SubjectType` 同值
 *   · `type: "REFUND_ONLY" | "RETURN_REFUND"` 就是已有的 `AfterSaleType`
 * ──────────────────────────────────────────────────────────────────────────── */

/** 结算账户形态。个人 openid 收款 / 对公商户号收款（ADR-002 §5） */
export type SettleAccountType = "PERSONAL_BANK_CARD" | "MERCHANT_ID";
/**
 * 支付**进件**状态（`MerchantPayment.applyStatus`）。
 *
 * ⚠️ 此前叫 `ApplyStatus`，与入驻审核的 `ApplyStatus` 同名不同义 ——
 * `ACTIVE`/`FROZEN` 两个值就是证据：审核不会有这两个态。
 */
export type PaymentApplyStatus = "NONE" | "APPLYING" | "ACTIVE" | "REJECTED" | "FROZEN";
/**
 * 增值包订阅状态。
 *
 * `GRACE`（宽限期，7 天）**能力全保留** —— 到期当天就压店的话，
 * 一次忘记续费等于让他的店在客户面前消失，而他往往正在门店里忙。
 */
export type PlanStatus = "ACTIVE" | "GRACE" | "EXPIRED";
/** 员工账号状态 */
export type StaffStatus = "ACTIVE" | "DISABLED";
/**
 * 门店角色（B 端）。**一人一店可持有多个**，权限取并集。
 *
 * 分界线画在「出错的后果」上，而不是功能重要性 ——
 * 履约被拆成三种活，因为它们面对的对象不同：分拣对货、核销对顾客、发货对收件人。
 * 拆开之后理货员与配送员才装得下。判断依据见
 * `docs/requirements/三端角色权限功能对齐清单.md` §4。
 *
 * ⚠️ `CS` 与运营端的 `Role.CS` **同名不同义**：这个是商家自己雇的客服（只管自己店），
 * 那个是平台客服（跨商家、能仲裁）。
 *
 * 老板不在这里 —— 他是 `isOwner`，不需要逐店授权。
 */
export type StaffRole =
  | "MANAGER" // 店长：除结算与员工管理外的经营权限
  | "CLERK" // 店员：收银台 —— 核销、到货分拣、发货、改库存
  | "PICKER" // 理货员：只到货分拣，不核销（那要面对顾客）、不看金额
  | "COURIER" // 配送员：只自送，看不到金额与全店订单
  | "CS"; // 线上客服：回评价、处理售后、看单。不碰货、不碰钱
/**
 * 我在**当前门店**能做什么（`GET /biz/context`）。B 端每次会话恢复与切门店后都要重取。
 *
 * @property merchantNo 我所属的主体
 * @property currentStoreNo 当前门店。切门店由 `X-Store-No` 决定，不是本地推的
 * @property owner 是否主体属主。属主的 `perms` 是 `["*"]`
 * @property storeNos 我能碰数据的门店。老板是主体全部，员工只有被授权的那几家
 * @property pickupNos 我能核销的自提点，按门店算出来
 * @property groupNos 我发起的团
 * @property staffRoles 我在当前门店持有的角色，**只用于展示**。判权一律看 perms。
 *   比 {@link StaffRole} 多一个 `OWNER`：那个类型是「可以授予的角色」（授权面板用），
 *   而属主的身份不来自逐店授权，是 `mch_account.is_owner`。同名不同集合，
 *   混用的表现是授权面板里冒出一个点了会报错的「老板」选项
 * @property perms 当前门店上的权限码并集。空数组 = 这家店没给我任何角色 = 零权限
 */
export interface BizScope {
  /** 当前用户所属的商家主体 */
  merchantNo: string;
  /**
   * 当前选中的门店。
   *
   * **切门店后要重新拉这个接口** —— 角色跟着门店走：同一个人可能在 A 店是店长、
   * B 店是店员，权限跟着变。不重拉的话，界面按上一家店的权限渲染。
   */
  currentStoreNo: string;
  /** 是不是老板（主体所有者）。老板不受门店角色限制 */
  owner: boolean;
  /** 我能管哪些门店。空 = 只能看当前这家 */
  storeNos: string[];
  /** 我能核销哪些自提点 */
  pickupNos: string[];
  /** 我发起了哪些团。**第三个作用域**，与门店 / 自提点正交 */
  groupNos: string[];
  /** 我在**当前门店**持有的角色（可多个）。老板恒为 `["OWNER"]` */
  staffRoles: (StaffRole | "OWNER")[];
  /**
   * 主体已获批的经营类目码（如 `["FRESH_VEG"]`）。
   *
   * **与门店货架是两件事**：这是平台批的证（能不能卖这一类），
   * 货架是商家自己摆的（店里怎么摆）。
   */
  categoryCodes?: string[];
  /**
   * 平台开关里与商家侧有关的那几个（后端 `/biz/context` 下发）。
   *
   * <p>`categoryGate`：类目资质校验**是否真的拦人**。
   *
   * <p>此前这是 `b-app/src/shared/flags.ts` 里的编译期常量，运营改一次开关要重新
   * 打包发版；更糟的是它与后端那份不同步时，症状是「点不动一个其实能按的按钮」
   * 或者「点下去吃一句说不清缘由的报错」—— 两种都难查，因为界面与后端各自看起来都对。
   *
   * <p>取不到时按 **false（不拦）** 处理：与后端默认值一致，且宁可放行也不要
   * 凭一个拿不到的开关把商家挡在门外。
   */
  switches?: Record<string, boolean>;
  /**
   * 这些角色合起来的权限码，**已取并集**（老板是 `["*"]`）。
   *
   * 端上照它裁剪入口，**不要自己按角色再推一遍** —— 两处各推一次迟早分岔，
   * 而分岔的表现是「看得见但点了报错」。
   */
  perms: string[];
}
/**
 * 结算流水状态。**与后端 `StlBill` 逐字一致**。
 *
 * > 2026-08-11 收敛：这里此前是 `PENDING/PARTIAL/DONE/EXPIRED` —— 一套后端从来没有过的词，
 * > 描述的是「周期账单」而不是「按子单的分账流水」。内联时对所有工具不可见，
 * > 具名化之后才暴露出来（见 enum-registry 里这条的 note）。
 *
 * - `PENDING` 待分账 · `SPLITTING` 分账中
 * - `SPLIT` **指令已发出，等通道确认** · `SPLIT_CONFIRMED` **已到账**（终态）
 * - `RETRYING` 失败重试中 · `MANUAL` 转人工（重试用尽，**不会自动再动钱**）
 * - `REVERSED` 已回退（退款前必须先回退分账）
 * - `OFFLINE_SETTLED` 当面收款，不走分账（钱从没进过平台）
 * - 自营轨道：`PENDING_RECON` 待对账 · `CONFIRMED` 已确认应付 · `PAID` 已付款（自营终态）
 *
 * ⚠️ **两条轨道互不相通**：第三方的单不会走到 `PAID`，自营的单不会走到 `SPLIT` ——
 * 它们的钱根本不是同一条路径下去的。此前 shared 这份漏了自营那三个值，
 * 于是 b-app 判 `status === "PAID"` 时类型说「不可能」，而后端一直在下发。
 *
 * ⚠️ **`SPLIT` 曾经同时表示「指令已发出」与「钱已到」**，而底下调的是桩实现 ——
 * 账面显示已分账而一分钱没动。2026-08-26 拆开：`SPLIT` 退回「已发出」，
 * 到账另立 `SPLIT_CONFIRMED`，且**只能由通道回执产生**。
 * 端上措辞跟着改：`SPLIT` 不能再叫「已结算」——
 * 商家拿它去对银行流水，对不上就来找客服，而客服看到的状态也是同一个词。
 */
export type SettleBillStatus =
  | "PENDING"
  | "SPLITTING"
  | "SPLIT"
  | "SPLIT_CONFIRMED"
  | "OFFLINE_SETTLED"
  | "PENDING_RECON"
  | "CONFIRMED"
  | "PAID"
  | "RETRYING"
  | "MANUAL"
  | "REVERSED";
/**
 * 入驻申请的审核状态。与库 `mch_entity_apply.status` 逐字一致。
 * ⚠️ 与 {@link MerchantStatus}（B 端「我能不能干活」的合并视图）不是一回事。
 */
export type MerchantApplyReviewStatus = "PENDING" | "REVIEWING" | "APPROVED" | "REJECTED";
/**
 * 订单状态。**这是后端真实下发的取值**，不是端上想象的流程。
 *
 * ⚠️ 曾经这里还有一个 `PREPARING`（备货中）—— 那是 mock 里多出来的一步，
 * 后端从付款直接到 `PAID`（待发货），没有独立的备货态。
 * 端上按一个后端永远不会给的值去筛，筛出来的就是空列表，而且不报错。
 *
 * ⚠️ 也曾有一个 `REFUNDING` —— 那是**售后单**的状态（{@link AfterSaleStatus}），
 * 不是订单的。订单只会到 `REFUNDED`。这个混淆的代价是两端的「售后」页签：
 * 它们按 `order.status === "REFUNDING"` 筛，而后端从不下发，
 * **b 端「售后中」页签与工作台售后待办数因此恒为空 / 恒为 0**。
 *
 * 一个订单可以「已完成」的同时挂着一张处理中的售后单 —— 两者并存，
 * 做成互斥的状态就必须二选一，而那是表达不了的。售后要从 `/mp/after-sale`
 * 与 `/biz/after-sale` 单独查。
 */
/**
 * 门店的预约时段与名额。
 *
 * **归属是门店不是商品**：能同时上几单取决于这家店有几个师傅，
 * 与卖的是保洁还是维修无关。
 *
 * `remaining` 是**派生值**（capacity - booked），后端算好下发 ——
 * 端上自己减的话就成了第三个可能与前两个对不上的数。
 *
 * ⚠️ 列表只是**那一刻**的快照，不是承诺：真正的判定在下单那条带条件的
 * UPDATE 里。两个人同时看到同一个「剩 1」，只有一个抢得到。
 */
/**
 * 商家收入按状态汇总。
 *
 * ⚠️ **四个数是四种状态，不是四个口袋** —— 它们加起来等于全部结算单。
 * 结算页此前只显示一个「商家实得」，读起来像已到手：商家拿它去对银行流水，
 * 对不上就来找客服，而客服看到的状态也只有一个词。
 */
export interface IncomeSummary {
  /** 已到账：通道回执确认过的 */
  receivedMinor: number;
  /** 已发起、等通道确认。**此前它混在「已到账」里**，而底下是桩 */
  inFlightMinor: number;
  /** 待结算 */
  pendingMinor: number;
  /** 当面收款：**他早就拿到了**，无需结算 */
  offlineMinor: number;
  /** 在途的结算单数。金额之外还要给条数 —— 一笔大的和十笔小的，商家的处理方式不同 */
  inFlightCount: number;
  /** 最早一笔在途的发起时刻。**「卡了多久」是商家真正想问的** */
  oldestInFlightAt?: number | null;
}
export interface MerchantCapability {
  /** 商家单号 */
  merchantNo: string;
  /** 商家名，展示用 */
  merchantName: string;
  /** 能否开票 */
  invoiceCapable: boolean;
  /** 该商家支持的支付方式；**空 = 未配置**（进件还没走完），不是「一种都不支持」 */
  payMethods: string[];
  /** 本期收款额度已用尽 —— 这家的货现在下不了单 */
  quotaExhausted: boolean;
  /** 加上本车这些货会超额 —— 还没用尽，但这一单过不去 */
  quotaWouldExceed: boolean;
  /**
   * 自送圆心的纬度（门店坐标，gcj02 ×1e6）。**可能为 null** —— 门店没在地图上标过点。
   *
   * 这三个字段是给结算页把**送不到的地址置灰**用的。端上判的口径必须与后端
   * `requireWithinDeliveryRadius` 完全一致，**包括三条放行**
   * （地址没坐标 / 门店没坐标 / 半径 ≤ 0）：端上比后端严，
   * 会把本来下得成的单挡在门外，而那种单用户永远查不出为什么下不了。
   */
  deliveryLatE6?: number | null;
  /** 自送圆心的经度（gcj02 ×1e6）。与纬度同生共死：一个为 null 就当没标过点 */
  deliveryLngE6?: number | null;
  /** 自送半径（米）。**null 或 ≤ 0 都表示「不限距离」**，一律放行 */
  deliveryRadiusM?: number | null;
}
// ================================================================ B 端（商家端）
// 说明：B 端复用 C 端的 Goods / Order / Review / Merchant 等主类型，
// 只在此追加「经营侧独有」的类型。两端共享同一份定义，避免契约漂移。

/**
 * 商家在 B 端的**综合状态**：既要表达「还没入驻成功」，也要表达「已经在经营」。
 *
 * ⚠️ 它是一个**展示用的合并视图**，底下是两条互不相干的生命周期：
 *   · 审核（`MerchantApplyStatus`）—— 商家还不存在时的事，归 `usr_merchant_apply`
 *   · 经营（ACTIVE / SUSPENDED）—— 商家已存在之后的事，归 `usr_merchant.status`
 *
 * B 端首页要在一个地方回答「我现在能不能做生意」，所以合并；
 * 但**库里绝不能合并** —— 一旦合并，「驳回一份申请」和「封禁一家店」就共用取值，
 * 而这两件事的操作人、审计口径、可逆性全都不同。
 */
export type MerchantStatus =
  | "NONE" // 未申请
  | "APPLYING" // 已提交，待审核（对应申请单 PENDING）
  | "REVIEWING" // 已受理，客服正在看 —— 让商家知道「有人在看」
  | "REJECTED" // 驳回，可补料重提
  | "PENDING_LICENSE" // 无证照先开店：能干活，但还不能开张营业（买家看不到）
  | "ACTIVE" // 正常经营
  | "SUSPENDED"; // 被封禁或被冻结 —— 见下方「为什么没有 FROZEN」
/*
 * **PENDING_LICENSE 与 SUSPENDED 的区别不是程度，是方向。**
 *
 * 被封的店「不能干活」；待补证照的店「能干活但不能开张」—— 他要进经营台
 * 录商品、配范围、加员工，把准备工作做完，只是买家还看不到他。
 * 所以它不能像 FROZEN 那样折叠进 SUSPENDED：折叠了 b-app 会按停业渲染整个工作台。
 *
 * ⚠️ 它也**不是 NONE**：那句「还没有开店 · 去入驻」对一个已经建好店、
 * 录了几十件商品的人来说，是在说他做的事不存在。
 */

/*
 * **为什么这里没有 FROZEN**（库里 mch_entity.status 是 ACTIVE/SUSPENDED/FROZEN）：
 * 后端在下发这一层就把 FROZEN 折叠进了 SUSPENDED（BizMerchantController.bizStatus），
 * 因为冻结与封禁对「我现在能不能干活」的答案一样。
 *
 * 补一个 FROZEN 进来是错的 —— 它永远不会被下发，只会变成一个筛不出东西的死分支。
 * 映射有测试：backend/.../portal/biz/MerchantStatusMappingTest.java
 */



/** 商家分层。为「流量起来后引入大商家」预留，一期只用 SMALL（ADR-004 §7） */
export type MerchantTier = "SMALL" | "MEDIUM" | "LARGE";
/** 登录后的商家会话 */
export interface MerchantProfile {
  /** 商家单号 */
  merchantNo: string;
  /** 店铺名 */
  name: string;
  /** 店铺 logo */
  logo: string;
  /** 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 */
  status: MerchantStatus;
  /** 主体类型 */
  subject: MerchantSubject;
  /** 商家分层。一期恒为 SMALL */
  tier: MerchantTier;
  /** 登录手机号，也是商家账号的主标识 */
  phone: string;
  /** 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） */
  isPickupPoint: boolean;
  /** 承接的自提点单号。`isPickupPoint=true` 时有值 */
  pickupNo?: string;
  /** 驳回原因，status=REJECTED 时有值 */
  rejectReason?: string;
  /** 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 */
  loginBy?: GrantType;
  /**
   * 资金路径。**B 端价格字段叫什么由它决定** ——
   * 归集（钱进平台账户）下平台是销售主体、最终售价平台定，商家填的是「期望收购价」；
   * 直连下他自己就是销售主体，那就是「售价」。
   *
   * 判据用它而不是门店的 `businessMode`：与积分能力同一根轴 —— **责任跟着钱走**。
   *
   * 还没进件的申请人为空：那时资金路径尚未确定，
   * 猜一个默认值会让他在入驻页看到一个还轮不到他的字段名。
   */
  fundsMode?: FundsMode;
}
export interface MerchantLoginResp {
  /** 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 */
  token: string;
  /** 商家档案 */
  merchant: MerchantProfile;
}
/**
 * 一条**结构化资质**。
 *
 * 与 `MerchantApplyReq.licenses`（纯图片 URL 数组）并存，两者都传。
 * 只有这一份带类型/证号/有效期 —— **审核通过时才转得进 `mch_qualification`**，
 * 而上架的两个闸门（资质过期、类目授权）读的就是那张表。
 * 光有图片 URL 填不出那些列，所以此前商家传的执照停在申请单里，两个闸门从不触发。
 */
/**
 * 资质类型码。取值同后端 `mch_qualification.qual_type`。
 *
 * ⚠️ **`BUSINESS_LICENSE` 是入驻校验的判据** —— 需要执照的档位必须含它，
 * 改名会让那条校验静默失效（找不到就当没传，然后放行）。
 */
export type QualificationType = "BUSINESS_LICENSE" | "FOOD_PERMIT" | "FOOD_WORKSHOP" | "OTHER";
export interface QualificationItem {
  /** 资质类型码 */
  type: QualificationType;
  /** 证照编号 */
  code: string;
  /** 证件照地址 */
  imageUrl: string;
  /**
   * 有效期截止（毫秒）。**长期有效传 `null`** ——
   * 不要用 0 或一个很大的数字冒充：过期扫描会把前者当成已过期、
   * 后者当成永不过期，两种都错且都不报错。
   */
  expireAt: number | null;
  /** 发证机关 */
  issuer?: string;
}
/**
 * 已登记的一张资质（`mch_qualification`）。
 *
 * 与 {@link QualificationItem} 的差别：那个是**入驻申请时提交的**（还没入库），
 * 这个是**已经登记在案的**（有编号、有状态、能被上架校验读到）。
 */
export interface Qualification {
  /** 资质记录号 */
  qualNo: string;
  /** 归属主体。端上其实用不到（只会看自己的），但后端在发 —— 声明出来免得契约守卫把它算成缺口 */
  entityNo?: string;
  /** 证件类型。**BUSINESS_LICENSE 是入驻校验的判据** */
  qualType: QualificationType;
  /** 证件名，如「食品经营许可证」。上架校验拿它与类目门槛的文案比对 */
  qualName: string;
  /** 证件编号，证上印的那一串 */
  qualNumber?: string | null;
  /** 证件照地址 */
  imageUrl?: string | null;
  /** 有效期截止（毫秒）。**空 = 长期有效**，与「已过期」是两回事 */
  expireAt?: number | null;
  /** VALID / EXPIRED / REVOKED */
  status: string;
}
/** 传一张证。`qualNo` 为空即新建 */
export interface QualificationSaveReq {
  /** 资质记录号。**为空 = 新增** */
  qualNo?: string;
  /** 证件类型。**BUSINESS_LICENSE 是入驻校验的判据** —— 需要执照的档位必须含它 */
  qualType: QualificationType;
  /** 证件名（「食品经营许可证」）。展示用 */
  qualName: string;
  /** 证件编号，证上印的那一串 */
  qualNumber?: string;
  /** 图片地址 */
  imageUrl?: string;
  /** 到期时刻（毫秒）。**到期前要提醒** —— 证件过期而没人发现，那家店会在某天突然卖不了货 */
  expireAt?: number | null;
  /** 传给哪张证照，可空 = 当前证照。多证照的老板从证照详情页进来时会带上它 */
  entityNo?: string;
}
/**
 * 门槛码字典的一条：这个码要哪一类证、对应哪些类目。
 *
 * `categoryNames` 由**应用层**拼（商家域不读商品域的类目，见 `CategoryUsagePort`
 * 的说明）—— 商家看的是「食品经营许可证能解锁：肉禽蛋、乳制品、熟食卤味」，
 * 而不是三个码。
 */
export interface AuthCodeInfo {
  /** 码值 */
  code: string;
  /** 名称 */
  name: string;
  /** 给人读的一句话，如「食品经营许可证」。空 = 这一类不需要证 */
  requiredQualification?: string | null;
  /** 机器判的类型，与 {@link QualificationType} 同值域。空 = 无需证件 */
  qualType?: QualificationType | null;
  /** 这个码能解锁的类目名。**由应用层拼** —— 商家看的是「食品经营许可证能解锁：肉禽蛋、乳制品」，不是三个码 */
  categoryNames: string[];
}
/** 「我的资质」这一页要的三份数据。 */
export interface MyQualifications {
  /** 我已经有的资质 */
  items: Qualification[];
  /** 已获授权的类目码。端上据此把「已解锁 / 待授权」标出来 */
  grantedCodes: string[];
  /** 平台的门槛码字典：哪些码要哪一类证 */
  catalog: AuthCodeInfo[];
}
export interface MerchantApplyReq {
  /** 拟用店铺名 */
  name: string;
  /** 主体类型。个人 → 个体户 → 企业，门槛前低后高 */
  subject: MerchantSubject;
  /** 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 */
  contactName: string;
  /** 联系手机号 */
  contactPhone: string;
  /** 主营类目 */
  category: string;
  /** 店铺简介 */
  desc: string;
  /** 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） */
  asPickupPoint?: boolean;
  /**
   * 结构化资质。**可选**：老版本端上还在只传 `licenses`，
   * 后端对未传该字段的请求跳过执照校验（见 `OpsServiceImpl.requireLicenseIfNeeded`）——
   * 校验必须晚于能满足它的 UI 上线，否则拦的不是坏商家，是所有人。
   */
  qualificationItems?: QualificationItem[];
  /**
   * 期望经营范围（ADR-009）。申请时可空，<b>审核通过时必须确定</b> ——
   * 否则商家上着架却对谁都不可见，且没有任何报错。
   */
  serviceScope?: ServiceScope;
  /** 期望覆盖的社区。scope=COMMUNITY 时审核通过必须非空 */
  communityNos?: string[];
  /**
   * 资质图片（营业执照/身份证）。**选填** —— 一期 EDI 不强制。
   *
   * 与下面的结算账户一样，属于**分账主体开户**而不是入驻申请本身（ADR-002）：
   * `usr_merchant_payment` 是独立一张表、有自己的 `apply_status`，就是这个道理。
   * 申请时能传就传，通过后在 B 端补也行 —— 逼一个还没通过审核的人先传营业执照，
   * 只会把人挡在门外。
   */
  licenses?: string[];
  /** 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5）。**选填**，同上 */
  settleAccountType?: SettleAccountType;
  /**
   * 行业（`sys_industry.industry`）。
   *
   * **它决定这家店能不能以小微主体进件** —— 微信的小微白名单是按行业给的，
   * 也是 `points_forced` 默认值的来源。
   *
   * 后端一直在收、库里一直有这一列，但契约没登记、端也没传，
   * 于是 `mch_entity.industry` 恒空：进件时才发现主体类型选错了，
   * 而那时商家已经开完店、上完架。
   */
  industry?: string;
}
/**
 * 平台主数据快照（`GET /common/master-data`）。
 *
 * 合成一个响应而不是三条接口，是因为它们在**同一屏上被同时用到**：
 * 「选行业 → 据此过滤可选主体 → 主体决定要不要传营业执照」。
 * 分三次请求会出现「行业回来了、主体还没回来」的中间态，
 * 而那个中间态里表单不知道该不该禁用某个选项。
 */
export interface MasterData {
  /** 可选行业。**决定能不能以小微主体进件**，也是 points_forced 默认值的来源 */
  industries: MasterDataIndustry[];
  /** 可选主体类型（法律形态）。决定资质要求与结算账户形态 */
  subjects: MasterDataSubject[];
  /** 可用支付通道与其能力位 */
  channels: MasterDataChannel[];
  /**
   * **这一期开放的经营范围档位**（`SERVICE_SCOPE` 的启用子集，运营在后台配）。
   *
   * 端上要照它渲染选项，**不要把三档写死**。写死的后果不是「多了个选项」：
   * 一期自营模式关掉了 `PLATFORM`，而 B 端照样把「全平台发货」摆在那里，
   * 商家点下去得到的是「当前不支持这个经营范围」—— 一个必被拒的选项，
   * 而他无从知道自己该选什么。2026-08-11 的端到端实测撞到过。
   *
   * 拿到 EDI 切平台模式时运营在后台放开，端上不发版就跟着变 ——
   * 这正是它下发而不是写死的理由。
   */
  serviceScopes: ServiceScope[];
}
export interface MasterDataIndustry {
  /** 行业码（`sys_industry.industry`），提交申请时回传的就是它 */
  industry: string;
  /** 展示名。**取服务端的**，不要在端上再维护一份翻译 */
  name: string;
  /** 该行业能否以小微主体进件。**false 时小微选项要禁用**，不是提交后才报错 */
  microAllowed: boolean;
}
export interface MasterDataSubject {
  /** 主体类型码 */
  subjectType: MerchantSubject;
  /** 展示名 */
  name: string;
  /** 要不要传营业执照 */
  needLicense: boolean;
  /** 是否受行业白名单管控（小微受管，其余不受） */
  industryGated: boolean;
  /** 该主体默认的结算账户形态：小微打个人，其余打对公 */
  settleAccountType: SettleAccountType;
}
export interface MasterDataChannel {
  /** 通道码（`sys_pay_channel.pay_channel`），如 WECHAT */
  payChannel: string;
  /** 展示名 */
  name: string;
  /** 通道是否可用。关掉时下单页不给这个支付方式，而不是点了才失败 */
  enabled: boolean;
  /** 该通道支持的支付方式，如 JSAPI / APP / H5 */
  payMethods: string[];
}
/**
 * 收款进件状态（每通道一条）。
 *
 * <p><b>它与入驻审核是两件事</b>：入驻过了店就能开、货能上架，
 * 但通道没批就收不了钱。合成一个「入驻进度」，商家问「我能收钱了吗」就没法回答。
 */
export interface PaymentApplyment {
  /** 通道码，如 WECHAT */
  payChannel: string;
  /** 通道展示名。取服务端的，端上不要再维护一份翻译 */
  channelName: string;
  /** NONE / APPLYING / ACTIVE / REJECTED / FROZEN */
  applyStatus: PaymentApplyStatus;
  /**
   * 这个通道现在能不能收钱。
   *
   * **照着它显示，不要自己去比 applyStatus** —— 比错的表现是
   * 「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。
   */
  canReceiveMoney: boolean;
  /** 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 */
  payMerchantNo?: string;
  /** 二级商户号掩码。完整号不回显 */
  subMchidMasked?: string;
  /** 结算账户形态：小微打个人（PERSONAL_BANK_CARD），其余打对公（MERCHANT_ID） */
  settleAccountType?: SettleAccountType;
  /** 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） */
  settleAccountMasked?: string;
  /** 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 */
  rejectReason?: string;
  /** 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 */
  missing: string[];
  /**
   * **有没有真的发给通道过。**
   *
   * <p>没有它，`APPLYING` 同时表示两件相反的事：入驻通过时建的占位（商家还没填过
   * 任何东西）与「已发给通道、在等回执」。都显示成「审核中」的话，新商家读到的是
   * 球在平台，而球其实在他自己脚下 —— 这正是「不能收钱」最常卡死的一步。
   */
  submitted: boolean;
  /** 提交进件的时间。没提交过为空 */
  appliedAt?: number;
  /** 通道开户完成的时间 —— 从这一刻起才真的能收钱 */
  activatedAt?: number;
  /**
   * 这条进件是**为哪家门店**做的；空 = 主体级默认号。
   *
   * 多门店商家会有多条「微信 · 已开通」，不显示门店就分不清哪条是哪家店 ——
   * 等于让他猜自己的钱打进了哪张卡。
   */
  storeNo?: string;
}
/**
 * 一张**证照**（营业执照）。库里叫 `mch_entity`，**对外一律叫「证照」**——
 * 老板不认识「主体」「实体」这两个词。
 */
export interface Entity {
  /** 商家主体号 */
  entityNo: string;
  /**
   * 执照上的名称。**待补证照时它是老板随手填的店名**——
   * 补齐执照、审核通过后被执照上的正式名称覆盖
   */
  name: string;
  /**
   * ACTIVE 营业中 / PENDING_LICENSE 待补证照 / SUSPENDED、BANNED 已停业。
   * **端上照它给下一步**：待补证照给「去补执照」，已停业给客服入口
   */
  status: MerchantStatus;
  /** 平台已认证。待补证照恒为 false —— 这个标是审核给的，不能自己开店就带上 */
  verified: boolean;
  /** 个体户 / 有限公司…… 待补证照时为空，那时还不知道是哪种 */
  legalForm?: string;
  /** 这张证照下**我能进**的门店数。老板 = 全部；店员 = 只数被授权到的那几家 */
  storeCount: number;
  /** 默认证照。不带 `X-Store-No` 时后端解析到的就是它 */
  isPrimary: boolean;
  /** 我是不是这张证照的持有人。**只有持有人能改资料、交执照、挂收款号** */
  canManage: boolean;
}
/**
 * 一张证照 + 它下面我能进的门店。门店选择器按这个分组渲染。
 *
 * **为什么分组而不是拍平**：两家店同名是常事（「文三路店」在两张执照下各有一家），
 * 拍平之后点哪个都不知道进了哪张证照，而进错的表现是「商品怎么全没了」。
 */
export interface EntityStores {
  /** 所属主体（一张证照） */
  entity: Entity;
  /** 这张证照下我能进的门店 */
  stores: Store[];
}
/**
 * 商家员工（B 端账号 + 他在各门店的角色）。
 *
 * <p>**逐店授权**：A 店店长可以同时是 B 店店员 —— 老店的店长去新店帮忙，
 * 但新店不归他管，这是小连锁的常态。
 */
export interface MerchantStaff {
  /** 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 */
  mchAccountNo: string;
  /**
   * 姓名（老板自己写的，如「小张」）。**认人靠它** ——
   * 一列号码谁也分不清。为空时端上回落 `loginPhone`。
   */
  displayName?: string;
  /**
   * 登录手机号，**完整、不脱敏**。
   *
   * 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）——
   * 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。
   */
  loginPhone: string;
  /** 老板。**不受门店授权限制**，他的店都归他管 */
  isOwner: boolean;
  /** ACTIVE / DISABLED */
  status: StaffStatus;
  /** 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" */
  roles: StoreRole[];
}
/**
 * 一条员工与授权的变更记录（B-11.10.3）。
 *
 * **授权变更是权限扩散的唯一入口** —— 加人、停用、给角色、撤角色。
 * 别的动作都有业务单据兜底，唯独这几个此前做完就没了：
 * 三个月后问「谁把张三提成了店长」，库里只有一行当前状态。
 */
export interface StaffLog {
  /** 操作人手机号（脱敏）。取不到当时身份时为空 —— 空就是空，不写「系统」 */
  actor?: string;
  /** 被操作员工的手机号（脱敏） */
  targetName?: string;
  /** STAFF_ADD / STAFF_ENABLE / STAFF_DISABLE / ROLE_GRANT / ROLE_REVOKE */
  action: string;
  /** 涉及门店的名字。加人与启停为空 */
  storeName?: string;
  /** 涉及的角色码。加人与启停为空 */
  role?: StaffRole;
  /** 人能读的一句话，直接展示 */
  detail?: string;
  /** 发生时间，毫秒时间戳 */
  at: number;
}
/**
 * 一个角色：6 个平台预置（只读）+ 商家自定义（V71）。
 *
 * **权限码的中文说明由后端给**（`permLabels`），前端不抄一份 ——
 * 抄的那份迟早与权限码本身漂开，而漂开的表现是
 * 「界面写着能改库存，实际打不通」。
 */
export interface MerchantRole {
  /** 角色码。预置是 `OWNER`/`MANAGER`… ，自定义是生成的业务键 —— **别拿它给店主看** */
  roleCode: string;
  /** 显示名。预置角色也有 —— 别拿 `MANAGER` 直接给店主看 */
  name: string;
  /** 平台预置：**只读**，要改先「复制为自定义角色」 */
  builtin: boolean;
  /** 这个角色带的权限码。老板那行是 `["*"]`（全部），别按长度当权限数 */
  perms: string[];
  /** 与 `perms` 一一对应的中文短说明 */
  permLabels: string[];
  /** 几个人在用。删除按钮据此禁用，并且要显示出来 */
  usedBy: number;
}
/**
 * 自定义角色**可以勾的一个权限点**。
 *
 * 为什么不让端上「把预置角色的权限并起来」当选项：那个并集**少一条** ——
 * `biz:finance` 只有老板有，而老板那行是 `*`。于是后端明明收这个码，
 * 界面上却勾不到，看起来像功能没做。
 */
export interface PermOption {
  /** 权限码，如 `biz:stock`。**只用于提交，不展示** */
  code: string;
  /** 中文短说明，兜底用。端上自己有中/英/阿三份文案 */
  label: string;
}
/**
 * 结算流水。**一个子订单一行**（ADR-002 §5），不是周期账单。
 *
 * > 2026-08-11 更正：这个类型此前描述的是一套「周期账单」（`billNo` / `periodStart`
 * > / `orderCount` / `settledMinor`），而后端 `/biz/settle/bills` 从来返回的都是
 * > 按子单一行的分账流水。**字段一个都对不上**，页面靠 mock 才看起来是好的 ——
 * > 连真后端会整片 undefined。与本轮反复撞到的「单看任一端都完整，断在两端之间」同形状。
 */
export interface SettleBill {
  /** 结算单号 */
  settleNo: string;
  /** 对应的子订单号 —— 分账以它为单位 */
  subOrderNo: string;
  /** 所属主单号 */
  orderNo: string;
  /** 主体号 */
  merchantNo: string;
  /** 结算基数（分）= 用户实付 + 平台补贴。**平台出资的优惠要补回给商家** */
  grossMinor: number;
  /** 平台佣金（分） */
  commissionMinor: number;
  /** 自提点履约服务费（分）。供货方付、承接方收，两个角色都是自己时账面抵消 */
  serviceFeeMinor: number;
  /** 商家实得（分）= 基数 − 佣金 − 服务费 */
  netMinor: number;
  /** 客流来源：MERCHANT_OWNED 自带客流（零佣金）/ PLATFORM */
  trafficSource?: string;
  /** 佣金费率快照（万分比）。费率会变，历史账不跟着变 */
  commissionRate: number;
  /** PENDING / SPLIT / RETRYING / MANUAL / REVERSED */
  status: SettleBillStatus;
  /** 生成时间 */
  createdAt: number;
  /** 分账完成时间；没分完为空 */
  splitAt?: number;
  /**
   * 这笔钱是**哪家店**挣的（统计维度）。空 = 存量主体级流水。
   *
   * 它**不决定钱打给谁** —— 打给谁看 `payMerchantNo`。
   * 两家店可以共用一个收款号（合并结算），也可以各配各的（分开结算）。
   */
  storeNo?: string;
  /** 这笔钱打给**哪个收款号**（结算维度，生成时快照）。空 = 当时进件还没走完 */
  payMerchantNo?: string;
  /**
   * T2 可结算时刻。**空 = 还不可结算**（未履约，或售后未闭环），不是「立刻可结」。
   *
   * 与下面三个一起回答商家问的第一个问题：**「这笔什么时候到」**。
   * 此前这一页只给金额，商家拿它去对银行流水，对不上就来找客服 ——
   * 而客服看到的也只有一个金额。
   */
  settleableAt?: number;
  /** T3 应结日（本批的）。空 = 还没入批 */
  dueAt?: number;
  /** 归属批次。空 = 还没入批 */
  batchNo?: string;
  /**
   * 本批当前状态。空 = 还没入批。
   *
   * **单据状态说「钱在哪」，批次状态说「流程走到哪」** —— 两个都要看：
   * 单子还是 PENDING 但批次已 RECONCILED，说明就快放了。
   */
  batchStatus?: SettleBatchStatus;
  /** 批次被挂起的原因，**直接展示给商家的原话**。空 = 没挂起 */
  batchBlockedReason?: string;
}
/** 账期批次状态。DRAFT 收单中 · COLLECTED 已截批 · RECONCILING 对账中 · BLOCKED 已挂起 · RECONCILED 待放款 · RELEASED 已放款 */
export type SettleBatchStatus =
  | "DRAFT"
  | "COLLECTED"
  | "RECONCILING"
  | "BLOCKED"
  | "RECONCILED"
  | "RELEASED";
/**
 * 我的账期批次。**商家问的是「这一批什么时候放、卡在哪」**。
 */
export interface MySettleBatch {
  /** 批次号。**与结算单上的批次号是同一个** —— 商家照着单子来问的就是它 */
  batchNo: string;
  /** 支付通道码。**不同通道账期不同，所以不合批** */
  payChannel: string;
  /** 本批采用的账期规则，如 T+1 / WEEKLY */
  settleCycle: string;
  /** T3 应结日 */
  dueAt: number;
  /** 实际放行时刻。空 = 还没放 */
  releasedAt?: number;
  /** 本批走到哪一步 */
  status: SettleBatchStatus;
  /** 本批装了几笔 */
  billCount: number;
  /** 本批应放款合计（分） */
  netMinor: number;
  /** 挂起原因，**原话展示**（含具体数字与阈值），不要在端上再拼一遍 */
  blockedReason?: string;
  /** 超时未处置将自动放行的时刻 */
  blockExpireAt?: number;
}
/**
 * 我的欠款。
 *
 * ⚠️ **与保证金方向相反**：保证金是商家自己的钱，欠款是欠平台的。
 * 端上不要把两者放在一起算成一个「账户余额」。
 */
export interface MyDebt {
  /** 当前欠款（分）。**0 = 没有欠款，整块不显示** —— 绝大多数商家从没欠过 */
  balanceMinor: number;
  /** 流水，时间倒序。**余额从流水推得出来**，对不上时信流水 */
  txns: MyDebtTxn[];
}
/**
 * 欠款流水类型。**方向不由它推** —— amountMinor 自带符号。
 * 与 ops-web 的 DebtTxnType 同名同值：两处声明，取值必须一起改。
 */
export type DebtTxnType =
  | "INCUR"
  | "OFFSET"
  | "DEPOSIT"
  | "WRITE_OFF";
export interface MyDebtTxn {
  /** 流水号 */
  txnNo: string;
  /** INCUR 产生 / OFFSET 货款抵扣 / DEPOSIT 保证金抵扣 / WRITE_OFF 核销 */
  txnType: DebtTxnType;
  /** **有符号**：产生为正、偿还为负 */
  amountMinor: number;
  /** 这一笔之后的欠款余额 */
  balanceAfterMinor: number;
  /** 源单类型，如 REFUND */
  sourceType?: string;
  /** 源单号 —— 商家问「为什么欠」，答案在这里 */
  sourceNo?: string;
  /** 从哪一批扣的 —— 商家问「扣哪了」，答案在这里 */
  batchNo?: string;
  /** 记这一笔的理由，**给商家看的原话** */
  reason?: string;
  /** 发生时刻（毫秒） */
  at: number;
}
/** 工作台待办。**数字即入口** —— 商家打开 App 只想知道「有几件事要我做」 */
export interface MerchantTodo {
  /** 待发货单数（EXPRESS 履约） */
  toShip: number;
  /** 待自送单数（商家自送履约） */
  toDeliver: number;
  /**
   * 待备货单数（自提单已付款，货还没送到自提点）。**按门店算**，这是供货方的活。
   *
   * 与 {@link toPick} 是同一批单的两头，**两个数不相等**：
   * 买家常常选别家的自提点。`toPick` 按自提点算（我要在点上分多少），
   * 这一个按门店算（我要送出去多少）。
   */
  toStock: number;
  /** 待核销单数（自提到货、买家还没来取） */
  toVerify: number;
  /** 待分拣单数（到货后按商品汇总点数） */
  toPick: number;
  /** 待处理售后单数 */
  afterSale: number;
  /** 待回复的评价数 */
  toReply: number;
  /** 可报价的求团需求数 */
  quotable: number;
}
export interface MerchantStats {
  /** 今日订单数（自然日，按市场本地时区切分） */
  todayOrders: number;
  /** 今日成交额（最小货币单位） */
  todayGmvMinor: number;
  /** 本月订单数 */
  monthOrders: number;
  /** 本月成交额（最小货币单位） */
  monthGmvMinor: number;
  /** 统计口径的币种 */
  currency: CurrencyCode;
  /** 店铺综合评分，0–5 */
  rating: number;
  /** 参与评分的评价条数 */
  ratingCount: number;
  /** 自带客流占比（trafficSource=MERCHANT_OWNED），决定费率档（ADR-004 §6） */
  ownedTrafficRate: number;
}
/**
 * 跨店总览的一行 —— 一家门店的今日 / 本月 / 三项待办（B-11.12.5）。
 *
 * <p>**没有单的门店也占一行（全零），不会从列表里消失**：
 * 一家今天还没开张的店从总览里不见了，店主的第一反应是「我的店呢」。
 * 零是一个答案，缺席不是。
 */
export interface CrossStoreRow {
  /** 门店号。点进去切门店时用它 */
  storeNo: string;
  /** 门店名。列表里认店靠它，不要拿门店号显示 */
  storeName: string;
  /** 是否默认店。**一个主体恰好一家**，界面上要标出来 */
  isDefault: boolean;
  /** ACTIVE 正常营业 / READONLY 已停用。停用的店仍在列表里 —— 看不见会被当成「店被删了」 */
  status: StoreStatus;
  /** 今日订单数（自然日，按市场本地时区切分） */
  todayOrders: number;
  /** 今日成交额（最小货币单位） */
  todayGmvMinor: number;
  /** 本月订单数 */
  monthOrders: number;
  /** 本月成交额（最小货币单位） */
  monthGmvMinor: number;
  /** 待发货单数（快递） */
  toShip: number;
  /** 待自送单数（商家自送） */
  toDeliver: number;
  /** 待备货单数（自提单已付款、货还没送到自提点）。按**门店**算 */
  toStock: number;
}
/**
 * 跨店总览（B-11.12.5）· `GET /biz/cross-store/overview`。
 *
 * <p>**只有门店维度的三项待办**：工作台上的 `toVerify`（待核销）与 `toPick`（待分拣）
 * 后端刻意不给 —— 那两个数是**自提点**维度且不限商家（一个自提点承接多家商家的货，
 * ADR-005）。摆进「门店」这一列，商家会读成「这家店的活」，点进去却是别人的货。
 *
 * <p>需要 `cross_store_stats` 能力位（PRO / CHAIN）。FREE 档访问会被后端以
 * `PLAN_CAPABILITY_REQUIRED`(70023) 拒绝 —— 端上要渲染**示例态 + 升档说明**，
 * 不是空白页也不是红色报错。
 */
export interface CrossStoreOverview {
  /** 统计口径的币种。与 `/biz/dashboard/stats` 同一个字段 */
  currency: CurrencyCode;
  /** 按店并列。顺序与门店列表一致（默认店在前），端上不必自己排 */
  stores: CrossStoreRow[];
}
/**
 * 跨店对比的一行 —— 窗口内这家店的销售额 / 订单 / 复购 / 缺货（B-11.12.6）。
 *
 * <p>⚠️ **这里没有评分**，它在 {@link CrossStoreCompare#rating} 上，是主体级的。
 */
export interface CrossStoreCompareRow {
  /** 门店号 */
  storeNo: string;
  /** 门店名 */
  storeName: string;
  /** 是否默认店 */
  isDefault: boolean;
  /** ACTIVE 正常营业 / READONLY 已停用 */
  status: StoreStatus;
  /** 窗口内订单数（不含已取消） */
  orders: number;
  /** 窗口内成交额（最小货币单位） */
  gmvMinor: number;
  /** 窗口内下过单的买家数（去重）。复购率的分母 */
  buyers: number;
  /** 其中下过 ≥2 单的买家数 */
  repeatBuyers: number;
  /** `repeatBuyers / buyers`，0–1。**分母为 0 时是 0**，一家还没开张的店显示 0% */
  repeatRate: number;
  /**
   * **这家店自己的**评分（V155，ADR-011：评价归门店）。
   *
   * ⚠️ 与顶层的 {@link CrossStoreCompare#rating} 是两个数：那个是主体整体分
   * （C 端商家卡上显示的那个），这个是「楼下那家」的分。两个都要显示 ——
   * 商家问「为什么我的店 4.9 而搜索里是 4.6」时，只有并排看得到才解释得通。
   */
  rating: number;
  /**
   * 计入这家店评分的条数。**0 = 暂无评价**，按条数判空而不是按分值 ——
   * 老评价没有门店归属，所以老店在第一条新评价到来之前也是 0。
   */
  ratingCount: number;
  /**
   * 该店可用量（stock − locked）≤ 0 的 SKU 数。
   * **只数已启用分店库存的 SKU** —— 一条店级行都没有的 SKU 走主体总量，不算这家店缺货。
   */
  outOfStockSkus: number;
}
/**
 * 跨店对比（B-11.12.6）· `GET /biz/cross-store/compare?days=30`。
 *
 * <p>门禁与 {@link CrossStoreOverview} 相同（`cross_store_stats` 能力位）。
 */
export interface CrossStoreCompare {
  /** **实际生效**的窗口天数（后端已夹在 1–365）。回显它，端上才知道传 99999 被截成了 365 */
  days: number;
  /** 统计口径的币种 */
  currency: CurrencyCode;
  /**
   * **主体整体评分**（各店的合成，也是 C 端商家卡上显示的那个）。
   * 每家店自己的分在 {@link CrossStoreCompareRow#rating} 上（V155 起）。
   *
   * 【历史】V155 之前 `rvw_review` 只有 `entity_no` 没有 `store_no`，
   * 门店维度的评分没有数据源，所以这个数只能放顶层。
   *
   * <p>渲染成一条「本店铺整体评分」的说明；对比表格里那一列用每行自己的
   * {@link CrossStoreCompareRow#rating}。**别拿这个数去填表格列** ——
   * 那样三家店会显示同一个数字，而这正是 V155 之前的样子。
   */
  rating: number;
  /** 计入评分的评价条数。0 = 还没人评过，显示「暂无评价」而不是 0 颗星 */
  ratingCount: number;
  /** 按店并列，顺序同门店列表 */
  stores: CrossStoreCompareRow[];
}
/**
 * 我的增值包（B-11.13，`GET /biz/plan`）。
 *
 * <p>与运营端那份（`MerchantPlanRow`）刻意是两个类型：运营看的是「这家商家买了什么」，
 * 商家看的是「我有什么、还差什么、能不能试」。挤成一个的结果是商家侧要接一堆
 * 用不上的字段（授予方、降级时间、额度来源），而它们每一个都会被端上误读成给他看的。
 */
export interface MerchantPlan {
  /** 档位码。**文案用 `planName`，不要按 code 自己映射** —— 运营改了名端上不会跟着变 */
  planCode: string;
  /** 档位显示名（「成长版」） */
  planName: string;
  /**
   * ACTIVE 生效中 / GRACE 宽限期（**能力全保留**，7 天）/ EXPIRED 已过期并降级。
   *
   * <p>GRACE 要显示成「即将到期，请尽快续费」而**不是**「已失效」：
   * 他的门店、子账号、跨店数据一样都没少，这时候说失效只会让他打客服电话。
   */
  status: PlanStatus;
  /** 订阅起始时间（毫秒）。null = 还没有过任何订阅 */
  startAt?: number | null;
  /** 到期时间（毫秒）。null = 不到期（免费档） */
  expireAt?: number | null;
  /** 生效门店额度 */
  storeQuota: number;
  /** 已用门店数。**后端算，只数营业中的店** —— 端上自己数会与建店那道闸的口径分岔 */
  storeUsed: number;
  /** 生效子账号额度 */
  staffQuota: number;
  /** 已用子账号数（不含老板本人） */
  staffUsed: number;
  /** 有没有跨店总览与对比 */
  crossStoreStats: boolean;
  /** 试用是否已用过。**一主体一次，永不回退** */
  trialUsed: boolean;
  /**
   * 可试用的目标档位码；null = 现在不能试用（已用过 / 已经是付费档 / 平台没配试用）。
   *
   * <p>端上按它决定要不要显示「免费试用」按钮 —— 不要自己用
   * `planCode === 'FREE' && !trialUsed` 推：那会漏掉「平台把试用天数配成 0」这种情况。
   */
  trialTier?: string | null;
  /** 试用天数，配合 `trialTier` 显示「免费试用 14 天」 */
  trialDays?: number | null;
  /**
   * 因降级被压成只读的门店名。
   *
   * <p>**只含平台压的那几家**，商家自己停用的不在里面 ——
   * 页面要写明是「哪几家」：只说「部分门店已停用」，他得自己一家家点开去找。
   */
  suspendedStores: string[];
  /** 三档对比，顺序即展示顺序（后端按 sort 排好） */
  tiers: PlanTier[];
}
/** 档位对比的一行。 */
export interface PlanTier {
  /** 档位码。**文案用 planName，不要按 code 自己映射** —— 运营改了名端上不会跟着变 */
  planCode: string;
  /** 名称 */
  name: string;
  /** 门店数配额 */
  storeQuota: number;
  /** 员工数配额 */
  staffQuota: number;
  /** 这一档给不给跨店统计 */
  crossStoreStats: boolean;
  /** 0 = 这一档不提供试用 */
  trialDays: number;
  /** 是不是他现在用的那一档 */
  current: boolean;
}
/**
 * 入驻申请状态（C 端查自己的进度 / 平台端审核队列共用）。
 *
 * 状态机：`PENDING → REVIEWING → APPROVED | REJECTED`，`REJECTED → PENDING`（补料重提）。
 * **APPROVED 是终态** —— 已经建了商家、发了账号，回退没有意义。
 *
 * ⚠️ 这条是**审核**生命周期，与 `Merchant` 上的**经营**状态（ACTIVE/SUSPENDED）无关：
 * 审核发生在商家还不存在的时候，封禁发生在商家已经存在之后。混成一个枚举会让
 * 「驳回一份申请」和「封禁一家店」共用取值，两件事迟早互相踩。
 */
export interface MerchantApplyStatus {
  /** 申请单号 */
  applyNo: string;
  /** 申请时填的店铺名。**存快照** —— 后来改名不该让历史申请跟着变 */
  name: string;
  /** 主体类型。决定分账主体形态与所需资质（ADR-002 §4） */
  subject: MerchantSubject;
  /** 审核状态。迁移见本类型的注释，APPROVED 为终态 */
  status: MerchantApplyReviewStatus;
  /** 驳回理由。**驳回必须写** —— 不写就等于让人猜着改 */
  rejectReason?: string;
  /** 通过后生成的商家单号。未通过时为空 —— 商家在通过之前根本不存在 */
  merchantNo?: string;
  /** 提交时间 */
  createdAt: number;
  /** 审核完成时间。PENDING/REVIEWING 期间为空 */
  auditedAt?: number;

  // ── 以下是**申请时填的原样内容**，用于驳回后回填 ──────────────────
  //
  // 为什么整份带回来而不是只给状态：驳回往往只缺一张执照，
  // 让人从头重填一遍是把「补交」变成「重来」—— 而重来的人有相当一部分就不回来了。

  /** 联系人姓名 */
  contactName: string;
  /** 联系手机号。这是申请人自己填的联系号码，**不是登录号**，不脱敏 */
  contactPhone: string;
  /** 主营类目 */
  category: string;
  /** 店铺简介 */
  desc: string;
  /** 期望经营范围（ADR-009） */
  serviceScope?: ServiceScope;
  /** 期望覆盖的社区 */
  communityNos?: string[];
  /** 已传的资质图（只有图片 URL，看不出是哪种证、什么时候过期） */
  licenses?: string[];
  /**
   * 结构化资质（V79）：**哪张证、证件号、有效期**。
   *
   * ⚠️ 这一段的标题写着「用于驳回后回填」，而此前只回填了 {@link licenses}
   * ——只有图片。**证件类型、编号、有效期三项全丢**，商家重提时得逐格再填一遍，
   * 而这正是本段注释想避免的那件事：「把补交变成重来」。
   *
   * 后端 `MerchantApplyVO` 一直在发它（审核台就靠它看类型与有效期），
   * 端上这里没声明。
   */
  qualificationItems?: QualificationItem[];
  /** 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 */
  industry?: string;
  /**
   * 是否愿意承接自提点（ADR-005）。
   *
   * **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。
   * 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。
   */
  asPickupPoint?: boolean;
}
