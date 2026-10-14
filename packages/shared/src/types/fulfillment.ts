// 履约：自提点、核销、预约时段、运力与到货异常
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type {
  CATEGORY_TYPE,
} from "@shared/utils/constants";
import type { Merchant } from "./merchant";
import type { StoreProfile } from "./store";
import type { Order, OrderStatus, SubOrderStatus } from "./trade";

/** 门店引用的取货点。status 来自 cmt_pickup_point：只有 ACTIVE 参与买家侧 */
export interface PickupRef {
  /** 取货点号 */
  pickupNo: string;
  /** 名称 */
  name: string;
  /** 地址 */
  address?: string | null;
  /** 类型 */
  type: PickupPointType;
  /** 状态 */
  status: string;
}
/**
 * 关掉某条履约路会影响到的商品（P2 处置前的预览）。
 *
 * **给它起个名字，不写成内联的 `Array<{ ... }>`**：契约里的匿名结构在规格生成器
 * 那边引用不到，只能落成一个空 object —— 后端照着实现就得自己猜返回什么。
 */
export interface FulfillmentImpactItem {
  /** 商品号 */
  goodsNo: string;
  /** 标题 */
  title: string;
}
/** 门店可引用的取货点候选（P1）：范围内的常驻点 + 本店自建的点 */
export interface PickupCandidate {
  /** 取货点号 */
  pickupNo: string;
  /** 名称 */
  name: string;
  /** 地址 */
  address?: string | null;
  /** 类型 */
  type: PickupPointType;
  /** ACTIVE / PENDING / REJECTED …；本店自建的 PENDING 点可引用，别家的不行 */
  status: string;
  /** 所在社区号 */
  communityNo: string;
  /** 所在社区名 */
  communityName?: string | null;
  /** STORE 点的承接门店；= 本店即「我自建的」 */
  ownerStoreNo?: string | null;
  /** 被驳回的原因。**要原样回商家** —— 不写等于让人猜 */
  rejectReason?: string | null;
}
export interface Pickup {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称（通常是承接店铺的店名） */
  name: string;
  /** 自提点地址 */
  address: string;
  /** 距当前社区的距离（米），服务端算好下发 */
  distance: number;
  /** 承接这个自提点的商家（ADR-005：PickupPoint.type=STORE，承接方是入驻商家而非团长） */
  hostMerchantNo: string;
  /** 承接商家的店名 */
  hostName: string;
  /** 承接商家的头像/门头图 */
  hostAvatar: string;
  /** 营业时间文案，如 `08:00-21:00`。展示用，不参与计算 */
  openHours: string;
  /** 到货时间说明，如「次日 18:00 后到」。影响用户选不选这个点 */
  arrivalDesc: string;
  /**
   * 取货点坐标（gcj02，E6）。**可能为空** —— 存量点是手填地址建的。
   * 买家要拿着它导航过去，没有就只能显示地址文本。
   */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
}
/**
 * 批量核销结果。
 * **不是整批回滚**：逐条尝试，失败的逐条回报 —— 店主需要知道**哪一单**没成，
 * 而不是「3 成功 2 失败」然后自己一个个找。整批回滚更糟：一张废码会让另外四单白扫。
 */
export interface VerifyBatchResult {
  /** 成功核销的单数 */
  successCount: number;
  /** 失败明细。code 是那张码，reason 是为什么不行 */
  failed: { code: string; reason: string }[];
}
/**
 * 自提点履约总览（后端 `GET /biz/pickup/overview`）。
 * 承接方最关心的三个数：还有几单没人来取、今天到了几批、这些活挣了多少服务费。
 */
export interface PickupOverview {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  pickupName: string;
  /** 待核销单数 —— 到货了还没人来取的 */
  pendingVerify: number;
  /** 今日到货批次 */
  arrivedBatches: number;
  /** 累计履约服务费（最小货币单位） */
  serviceFeeMinor: number;
}
/**
 * 费率卡（后端 `GET /biz/settle/rate-card`）。
 *
 * ⚠️ 费率是**万分比整数**（后端 `platformRate / 100.0` 才是百分数）——
 * 直接当百分数显示会把 2% 显示成 200%。
 * 语义同样要照搬：**费率以下单时快照为准，调整不影响历史订单** ——
 * 不写清楚的话，商家会以为平台调价能追溯到已成交的单。
 */
export interface RateCard {
  /** 自带客流费率（万分比）。商家自己带来的客人，平台抽成低 */
  merchantOwnedRate: number;
  /** 平台客流费率（万分比）。平台分发带来的订单 */
  platformRate: number;
  /** 费率说明文案。**须写明「以下单时快照为准，调整不影响历史订单」** */
  note: string;
}
/**
 * 预约时段的**按天展示分组**（SERVICE + APPOINTMENT）。
 *
 * ⚠️ <b>与 {@link AppointmentSlot} 不是一回事</b>，别混：
 *   · 这个是「一天 × 若干时间点」的**展示结构**，给选择器分组用
 *   · 那个是排期的**一行**（有 slotNo，下单占的就是它）
 *
 * 它此前就叫 AppointmentSlot，而唯一的使用处是 `GoodsVO.slots?` ——
 * 一个注释里明写着「后端从不下发」的幽灵字段。真排期落地时把名字让了出来。
 */
export interface AppointmentDaySlots {
  /** YYYY-MM-DD（市场本地时区） */
  date: string;
  /** 当天各时段的余量。`time` 形如 `14:00`，`left` 为剩余可约数，0 表示约满 */
  times: { time: string; left: number }[];
}
/** 自提点承接方类型。与 {@link PickupPointType} 不同：那个说「是什么点」，这个说「谁在承接」 */
export type PickupOwnerType = "MERCHANT" | "USER" | "PLATFORM";
/** 自提点作用域：常驻 / 团粒度（一团一销） */
export type PickupScope = "PERMANENT" | "GROUP_INSTANCE";
/** 自提点计费方式。**与 ops-web 的 `PickupFeeMode` 同值** —— 费率线下逐点协商，故两种都留 */
export type PickupFeeMode = "NONE" | "PER_ITEM" | "RATE";
/** 到货异常类型：缺件 / 破损。B 端到货登记时上报（ADR-005 履约链路） */
export type ArrivalIssueKind = "SHORTAGE" | "DAMAGE";
export interface AppointmentSlot {
  /** 时段号。**下单占的是它** */
  slotNo: string;
  /** 门店号 */
  storeNo: string;
  /** 开始时刻（毫秒） */
  startAt: number;
  /** 结束时刻（毫秒） */
  endAt: number;
  /** 这一档能约几个人 */
  capacity: number;
  /** 已约人数 */
  booked: number;
  /** 还能核几次 */
  remaining: number;
  /** OPEN 可约 / CLOSED 停约。停约**不删行也不赶人** */
  status: SlotStatus;
}
// ---------------------------------------------------------------- 团长


/** 分拣单的一行：按商品汇总，团长照着这个点数 */
export interface PickingRow {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。分拣按 SKU 汇总，不是按商品 */
  skuNo: string;
  /** 商品标题 */
  title: string;
  /** 封面图，照着点数时用来认货 */
  cover: string;
  /** 规格文案 */
  spec: string;
  /** 该 SKU 在本自提点的总件数（含赠品） */
  totalQty: number;
  /** 谁要几件 */
  buyers: { nickname: string; qty: number; orderNo: string }[];
}
/**
 * 自提点履约台上的一单（`/biz/pickup/orders`）。
 *
 * **不是 `Order`**：这里的承接方可能是别家商家的自提点，字段按「履约必需」
 * 裁到最小 —— 认得出人、点得清件数、核得了码，仅此而已（B12）。
 * 端上此前把它当 `Order` 用，于是按 `status === "ARRIVED"` 过滤（那是 mock 的口径），
 * 真实后端返回 `WAIT_FULFILL`，**列表因此永远是空的**。
 */
export interface PickupOrder {
  /** 子单号 —— 履约的最小单位是子单，不是主单 */
  subOrderNo: string;
  /** 取货码 */
  verifyCode: string;
  /** 买家昵称。认人用；没设昵称时为空 */
  buyerNickname?: string;
  /** 手机号后四位。认人够用，联系走平台通道（B12） */
  buyerPhoneTail?: string;
  /** 货主商家名。自提点可能替好几家收货 */
  merchantName?: string;
  /** 子单状态：WAIT_FULFILL / ARRIVED / COMPLETED / … */
  status: SubOrderStatus;
  /** 这单该在哪个自提点取。核销时后端会比对，不是本点直接拒 */
  pickupNo?: string;
  /** 这单要交付的东西。分拣与交货时按它点数 */
  items: { goodsNo: string; title: string; spec?: string; qty: number }[];
}
/**
 * 核销结果。
 *
 * ⚠️ **失败也是 HTTP 200 + `code: 0`**，靠 `success` 判 —— 端上不能只看有没有抛异常。
 * 此前 b-app 正是这么写的：任何一次失败（码无效、已核销、不是本点）
 * 都会走进成功分支，界面提示「核销成功」而货其实没核掉。
 */
export interface VerifyResult {
  /** **判成功只看它** —— 失败同样是 HTTP 200 + code 0 */
  success: boolean;
  /** 成功或识别到单时给出；码根本不存在时为空 */
  subOrderNo?: string | null;
  /** CODE_NOT_FOUND / ALREADY_VERIFIED / NOT_THIS_PICKUP / NOT_ARRIVED / REFUNDED / NOT_PAID */
  reason?: string | null;
}
// ================================================================ 门店主页（C 端）

/**
 * 门店主页数据（C-ST-01）。
 * ⚠️ 这是**交易页不是介绍页**：登录用户第一屏是「我买过的」，不是店招 Banner。
 * 粮油副食的复购路径必须压到三步 —— 打开 → 常买 → 下单（ADR-004 §3.3）。
 */
/**
 * 门店主页上店主自己维护的那一块：公告、营业时间、地址。
 *
 * **只有这三个，不是整份 {@link StoreProfile}** —— 经营范围、配送半径、收款号
 * 那些是 B 端配置，C 端一个字节都不该看到。契约此前直接写 `StoreProfile`，
 * 相当于让门店主页有权拿到商家的全部经营参数。
 */
/**
 * 本团待取的一单（发起人视角，C-GB-06 邻里自提）。
 *
 * **不是 `Order`**。契约此前把这条链路的三个端点都声明成返回 `Order`，
 * 而后端返回的一直是这个形状 —— 页面读 `o.orderNo` 拿到 undefined，
 * 于是 `v-for` 的 key 全是 undefined，核销按钮点谁都一样。
 * 发起人只需要「谁的、几件、核销码」，不需要整张订单。
 */
export interface GroupPickupOrder {
  /** 子订单号（`SUB…`）—— 这条链路上的「一单」就是一张子订单 */
  subOrderNo: string;
  /** 买家昵称。自提点认人靠它 */
  buyerNickname: string;
  /** 核销码。**只有发起人看得到**，参团者看自己那一单即可 */
  verifyCode: string;
  /** 状态 */
  status: OrderStatus;
  /** 明细行 */
  items: { goodsNo: string; title: string; spec: string; qty: number }[];
}
// ================================================================ 自提点（ADR-005）

/**
 * 自提点实体。
 *
 * 取代了原先的 `Merchant.isPickupPoint` 布尔字段 —— 那个表达不了「承接方是用户」：
 * 邻里自提是送到**团发起人家里**，承接的是邻居本人，不是商家。
 */
/**
 * 自提点类型。对应 `cmt_pickup_point.type`。
 *
 * ⚠️ 此前只以裸字面量的形式内联在 `PickupPoint.type` 里 —— 值是对的，
 * 但**没有单一声明处**：对账工具扫不到它，各处写的是裸字符串。
 * `CATEGORY_TYPE` 出事前正是这个状态（见 docs/technical/枚举统一方案.md §2「C 无主」）：
 * 今天没 bug，但下一个人在别处再写一次时，没有任何东西会拦住他写错。
 */
export type PickupPointType =
  | "STORE" // 商家自有门店，不收费
  | "NEIGHBOR" // 邻居家。**承接方是用户不是商家，零报酬**（ADR-005）
  | "PLATFORM"; // 平台提供，线下协商
export interface PickupPoint {
  /** 自提点单号 */
  pickupNo: string;
  /**
   * 自提点由谁承接。**三档，各自的费用规则完全不同**（2026-08-06 定）：
   *   · STORE    商家自己的门店 —— 商家自行解决，平台不收履约服务费
   *   · NEIGHBOR 团发起人家里 —— **零报酬**（ADR-005），有报酬就是团长招募换个名字
   *   · PLATFORM 平台提供的点 —— 收履约服务费，**费率线下逐点协商，由运营平台录入**
   */
  type: PickupPointType;
  /** 承接方所属账号池 */
  ownerType: PickupOwnerType;
  /** 承接方单号，按 ownerType 落在 merchantNo 或 cUserNo 上 */
  ownerNo: string;
  /** 常驻 | 团粒度（一团一销） */
  scope: PickupScope;
  /** type=NEIGHBOR 时必填：这个点只服务这一个团 */
  groupNo?: string;
  /** 自提点名称 */
  name: string;
  /**
   * 展示地址。**成团前只到楼栋，付款后才给完整门牌**（B13）——
   * 未成团的团不该暴露发起人住址。
   */
  address: string;
  /** 约定取货时段。邻居家不能一直堆着货（B15） */
  timeSlot?: string;
  /**
   * 计费口径。**必须显式标出用哪一种** —— 库里按件与按率两列长期并存，
   * 没有判别列的话结算侧只能猜，猜错就是给自提点少付或多付钱。
   * 之所以两种都留：费率是**线下逐点协商**的，有的点谈成按件、有的谈成按成交额抽成，
   * 硬统一成一种会让运营在谈判里没有筹码。
   */
  feeMode: PickupFeeMode;
  /** feeMode=PER_ITEM 时的按件服务费。STORE 与 NEIGHBOR 恒为 0 */
  serviceFeePerItemMinor: number;
  /** feeMode=RATE 时的费率（万分比）。STORE 与 NEIGHBOR 恒为 0 */
  serviceFeeRate: number;
}
/**
 * 一家承运方（`CarrierVO`）。**归履约域维护，进销存只读**。
 */
export interface Carrier {
  /** 编号，如 `SF`。**调拨单存的就是它** —— 跨库不能外键，所以存对方的业务键 */
  carrier: string;
  /** 名字。选中后要**一起回传**给发货接口：进销存读不了主库，快照只能由端上带过去 */
  name: string;
}
// ── 2026-08-30：从 interface 里提出来的三个具名类型 ──
//
// 内联的字面量联合**对所有工具不可见** —— 枚举登记表登记不到、三端对账对不到、
// 改名时必漏一处。提取的成本是一行，漏掉的代价是一个筛不出东西的死分支。

/** 预约时段状态。CLOSED 停约**不删行也不赶人** —— 已约的照常履约 */
export type SlotStatus = "OPEN" | "CLOSED";
