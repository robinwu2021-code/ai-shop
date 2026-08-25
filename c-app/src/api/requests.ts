// C 端请求类型（wire contract）—— **入参的唯一真源**。
//
// 为什么需要这一层：
//   contract.ts 的方法签名是位置参数（`cartAdd(goodsNo, skuNo, qty)`），方便页面调用，
//   但**实际发到网络上的是一个对象**。这两者是两回事，只有后者才是与服务端的约定。
//   没有这层类型，OpenAPI 里的 requestBody 就只能写成空的 `{type:"object"}` ——
//   那样的 spec 生成不出可用的 DTO，"入参对齐" 也就无从谈起。
//
// 怎么强制不漂移：
//   http.ts 里发出去的 body 一律用 `satisfies XxxReq` 标注 —— 字段名写错、少传、多传，
//   全部在编译期报错，而不是等联调时才发现。
//
// 为什么不放进 packages/shared：
//   按 ADR-007 §3 的边界，contract 层不共享 —— B 端有自己的 `/mb/**` 入参，
//   放一起会诱导两端互相复用不该复用的东西。
import type {
  AfterSaleType,
  ReviewScores,
  AfterSaleReason,
  CategoryType,
  FulfillmentType,
  GrantType,
} from "@shared/types";

/**
 * 入驻申请可选的商家类型。
 * 不用 `Extract<MerchantType, ...>` —— 生成 schema 时它的名字会变成
 * `Extract<MerchantType,("COMPANY"|"INDIVIDUAL")>`，不符合 OpenAPI 的组件命名规则。
 * 契约类型要能干净地映射成 DTO 名，所以这里写成直白的联合。
 */
/*
 * 这里曾有一个 `MerchantApplyType = "COMPANY" | "INDIVIDUAL"` ——
 * 商家主体类型的**第四套说法**（权威码是 shared 的 MerchantSubject：
 * MICRO / INDIVIDUAL / ENTERPRISE，且 COMPANY 是已废弃的旧值）。
 * 一个概念此前有五处声明三套取值，见 docs/technical/枚举领域清单.md §2.3。
 */

// ---------------------------------------------------------------- 用户

export interface LoginReqBody {
  /** 登录方式，决定 principal / credential 各放什么 */
  grantType: GrantType;
  /** WX_MINI: wx.login code；PHONE_OTP: 手机号 */
  principal: string;
  /** PHONE_OTP: 验证码 */
  credential?: string;
  /** 裂变归因：邀请人 */
  inviterNo?: string;
  /** 裂变归因：团长 */
  /** 进店归因：从店铺码/店铺分享进入时带上（ADR-004 §5.4） */
  merchantNo?: string;
}

/** 绑定手机号（验证码）。号码要以**字符串**传 —— 见 phone-gate.vue 里那段注释 */
export interface BindPhoneReq {
  phone: string;
  code: string;
}

/** 微信一键授权：端上只拿得到 code，换号在后端做 */
export interface WxPhoneReq {
  code: string;
}

export interface BindCommunityReq {
  /** 要绑定的社区。**商品可见范围依赖它**，绑错了首页就是别的小区的货 */
  communityNo: string;
  /** 默认自提点，须属于该社区 */
  pickupNo: string;
}

// ---------------------------------------------------------------- 地址簿

export interface SaveAddressReq {
  /** 有值 = 编辑，无值 = 新增 */
  addressId?: string;
  /** 收货人姓名 */
  name: string;
  /** 收货人手机号 */
  phone: string;
  /** 省市区 */
  region: string;
  /** 详细地址（街道门牌） */
  detail: string;
  /** 设为默认。置 true 会把原默认地址改为 false */
  isDefault: boolean;
  /** 标签：家 / 公司 / 其他 */
  tag?: string;
  /** 地图选点给的坐标（gcj02，E6）；不传 = 不改 */
  latE6?: number | null;
  lngE6?: number | null;
}

// ---------------------------------------------------------------- 社区 / 商品（query）

export interface NearbyQuery {
  /** 纬度。与 lng 成对出现，不传则按用户已绑定社区返回 */
  lat?: number;
  /** 经度 */
  lng?: number;
}

export interface GoodsListQuery {
  /** 页码，从 1 起 */
  page?: number;
  /** 每页条数 */
  size?: number;
  /** 只看某个商家的商品（门店主页用） */
  merchantNo?: string;
  /** 按商品形态过滤 */
  type?: CategoryType;
  /** 按类目过滤 */
  categoryNo?: string;
  /** 搜索关键词，匹配标题与副标题 */
  keyword?: string;
  /** 按社区过滤 —— **决定这个小区的人能看到哪些商家的货**。不传则按当前绑定社区 */
  communityNo?: string;
}

export interface PromotedMerchantsQuery {
  /** 按社区取推荐。不传则按当前绑定社区 */
  communityNo?: string;
  /** 取几条，默认由服务端定 */
  size?: number;
}

export interface PromotedGoodsQuery {
  /** 按社区取推荐。不传则按当前绑定社区 */
  communityNo?: string;
  /** 取几条，默认由服务端定 */
  size?: number;
}

// ---------------------------------------------------------------- 购物车

export interface CartAddReq {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。购物车按 SKU 去重，同商品不同规格是两行 */
  skuNo: string;
  /** 加购件数，正整数 */
  qty: number;
}

export interface CartUpdateReq {
  /** 要改的 SKU */
  skuNo: string;
  /** 改后的件数。传 0 等同于删除该行 */
  qty: number;
}

export interface CartRemoveReq {
  /** 要删除的 SKU 列表。批量是因为购物车支持多选删除 */
  skuNos: string[];
}

// ---------------------------------------------------------------- 交易

export interface CreateOrderReqBody {
  /** 下单行。跨商家时服务端**拆成多笔子订单**，共享一个 payGroupNo（E3） */
  items: {
    /** 商品单号 */
    goodsNo: string;
    /** SKU 单号 */
    skuNo: string;
    /** 件数 */
    qty: number;
  }[];
  /** 履约方式。决定下面 pickupNo / addressId / appointmentAt 哪个必填 */
  fulfillment: FulfillmentType;
  /** PICKUP 必填：自提点单号 */
  pickupNo?: string;
  /** EXPRESS / 自送必填：收货地址。下单时地址整体**快照**进订单 */
  addressId?: string;
  /** 使用的优惠券 */
  couponNo?: string;
  /** 使用的积分数。服务端按抵扣上限与账户余额截断，端上传的只是意愿 */
  usePoints?: number;
  /** 买家留言 */
  remark?: string;
  /** 参团下单时传团单号。**后端 CreateOrderReq 目前不认这个字段**，接上去会静默变成普通单 */
  groupNo?: string;
  /** APPOINTMENT：预约开始时间戳 */
  appointmentAt?: number;
  /**
   * 支付方式（`PAY_MODE`）。**不传按 ONLINE** —— 存量端上没有这个字段，
   * 不能因为补了它就让老版本下不了单。
   *
   * 能不能选 OFFLINE 由 `orderCapability` 的 `usablePayModes` 说了算，
   * 而后端在 create 里会**再判一次**：端上不该是唯一的闸。
   */
  payMode?: string;
  /**
   * APPOINTMENT：选定的**预约时段**。这家店开了时段就必填 ——
   * 没开则忽略，走 `appointmentAt` 那条旧路（兼容期）。
   */
  appointmentSlotNo?: string;
  /** 幂等 key，防重复提交 */
  idempotencyKey: string;
}

export interface OrderListQuery {
  /** 页码，从 1 起 */
  page?: number;
  /** 每页条数 */
  size?: number;
  /** 按订单状态过滤，不传为全部。取值见 `OrderStatus` */
  status?: string;
}

export interface AfterSaleReq {
  /** 仅退款 / 退货退款 —— 两者流程根本不同，不能合成一个 */
  type?: AfterSaleType;
  /** 已拼好的原因文案（前端把 reason 枚举与补充说明合并后提交） */
  reason: string;
  /** 举证图。破损/少件类售后没有图基本判不了 */
  images: string[];
  /** 结构化原因，便于服务端统计与风控 */
  reasonCode?: AfterSaleReason;
}

// ---------------------------------------------------------------- 拼团

export interface GroupBuyListQuery {
  /** 按自提点过滤 —— **成团单位就是自提点**。不传则按当前绑定的自提点 */
  pickupNo?: string;
}

export interface JoinGroupBuyReq {
  /** 参团件数，正整数 */
  qty: number;
}

export interface CreateGroupBuyReq {
  /** 要开团的商品，必须是已上架商品 */
  goodsNo: string;
  /** 成团的自提点 */
  pickupNo: string;
  /** 邻里自提：送到我家（ADR-005）。只能是发起人自己家，不能指定别人家 */
  neighbor?: { toMyHome: true; address: string; timeSlot: string };
}

// ---------------------------------------------------------------- 邻里求团

export interface RequestListQuery {
  /** 按自提点过滤。求团的范围同样是自提点/小区 */
  pickupNo?: string;
}

export interface CreateRequestReq {
  /** 需求所属的自提点/小区 */
  pickupNo: string;
  /** 需求标题，如「想团儿童床垫」。发起时**商品还不存在** */
  title: string;
  /** 需求详述：尺寸、材质、用途，供商家判断能不能接 */
  desc: string;
  /** 期望数量 */
  expectQty: number;
  /** 心理价位（最小货币单位），可不填。填了商家报价更有的放矢 */
  budgetMinor?: number;
}

export interface ChooseQuoteReq {
  /** 选定的报价。**选定即锁价**，此后下单一律用快照价（ADR-003） */
  quoteNo: string;
}

// ---------------------------------------------------------------- 商家 / 评价

export interface MerchantListQuery {
  /** 搜索关键词，匹配店名 */
  keyword?: string;
  /** 按社区过滤 + 按距该社区距离排序。不传 = 全域（搜索场景） */
  communityNo?: string;
}

/*
 * 入驻申请的 wire 契约在**共享层**（`@shared/types` 的 `MerchantApplyReq`，9 个字段）。
 *
 * 这里曾经另写了一份同名类型（6 个字段），于是 C 端与 B 端提交的是**两种不同的东西** ——
 * 而两边打的是同一个业务、最终落同一张表。C 端填的资质图与结算账户类型无处安放，
 * B 端填的又比后端认识的多。这正是「四方口径不一致」的根因。
 *
 * 现在统一到共享层那份：一处定义，三端与后端共用。
 */

export interface ReviewListQuery {
  /** 只看某商品的评价 */
  goodsNo?: string;
  /** 只看某商家的评价。与 goodsNo 二选一，都不传则报错 */
  merchantNo?: string;
}

export interface CreateReviewReq {
  /** 被评价的订单。**必须是已完成订单**，且一单一评 */
  orderNo: string;
  /** 被评价的商品。一单多商品时逐个评 */
  goodsNo: string;
  /** 总分，1–5 整数 */
  rating: number;
  /** 评价正文 */
  content: string;
  /** 评价图 URL 列表，可为空数组 */
  images: string[];
  /**
   * 三维分（商品 / 履约 / 服务）。**可选** —— 老客户端只给总分。
   *
   * 评价页一直在发这个字段，但类型里漏了它，于是它是**悄悄漏出去的**：
   * `satisfies` 检查不到、OpenAPI 里没有、后端也就无从知道要收。
   * 是 wire-alignment 守卫在后端实现时把它抓出来的。
   */
  scores?: ReviewScores;
}

// ---------------------------------------------------------------- 团长


export interface VerifyPickupReq {
  /** 取货码。发起人在邻里自提点为参团者核销时出示的那串码 */
  code: string;
}

export interface MarkArrivedReq {
  /** 要标记到货的订单。**批量**：一次到货通常是一整批，逐单调用会把通知发成 N 条 */
  orderNos: string[];
}

// ---------------------------------------------------------------- 积分

export interface PointsDeductibleQuery {
  /** 试算哪个商家的单 —— 开关是按商家判的，不同店结果不同 */
  merchantNo: string;
  /** 券后金额（分）。抵扣上限按它算，**运费不参与** */
  payableMinor: number;
  /**
   * 支付方式（`PAY_MODE`）。线下能否用积分由平台一个开关控制 ——
   * 不传的话试算按线上算，而下单时按真实支付方式算，两处会给出不同的数。
   */
  payMode?: string;
}
