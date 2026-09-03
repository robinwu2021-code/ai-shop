// C 端用户本人：账号、地址、积分、他手上的券与卡
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { CurrencyCode } from "./core";
import type { Coupon } from "./marketing";
import type { MerchantPointAccount } from "./merchant";

// ---------------------------------------------------------------- 用户与归属

export interface User {
  /** C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 */
  cUserNo: string;
  /** 昵称。微信授权取来的，用户可改 */
  nickname: string;
  /** 头像 URL */
  avatar: string;
  /** 手机号。已脱敏（中间四位星号），完整号码不下发到端上 */
  phone: string;
  /** 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 */
  communityNo?: string;
  /** 默认自提点。下单时预选，用户可改 */
  pickupNo?: string;
  /** 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） */
  merchantNo?: string;
}
/**
 * 一个「可选的区域」——<b>有已开通社区的那种</b>。
 *
 * 区划全表有 2978 个区县、41352 个街道。把整棵树扔给用户去挑，
 * 十有八九挑到一个一家店都没有的区：那不是选区域，那是抽奖。
 */
/**
 * 微信一键取手机号当前可不可用。
 *
 * <p>由后端说了算：它取决于小程序认证状态与通道开关，端上判不出来。
 * 写死在端上的话，认证下来之后还要再发一次版。
 */
export interface PhoneCapable {
  /** true = 显示「微信一键获取」；false = 显示手机号 + 验证码 */
  capable: boolean;
}
// ---------------------------------------------------------------- 积分
//
// 积分能被商家接收并向平台兑付 → 它是平台的负债，不是营销数字。
// 因此账户、流水、有效期都要按「资金」的标准建模，见 ADR-006。

export type PointRecordType =
  /** 消费获得 */
  | "EARN"
  /** 下单抵扣 */
  | "USE"
  /** 退款返还 */
  | "REFUND"
  /** 过期作废 */
  | "EXPIRE"
  /** 商家收款（B 端账户） */
  | "RECEIVE"
  /** 平台兑付给商家（B 端账户） */
  | "SETTLE";
export interface PointRecord {
  /** 流水单号。积分是平台负债，每一笔变动都要可追溯（ADR-006） */
  recordNo: string;
  /** 变动类型，决定这笔是增是减 */
  type: PointRecordType;
  /** 变动量，正=增加 负=减少 */
  points: number;
  /** 流水标题，如「订单消费获得」「过期作废」。展示用 */
  title: string;
  /** 关联订单。消费/退款类必有，过期/结算类为空 */
  orderNo?: string;
  /** 发生时间 */
  at: number;
  /** 变动后余额，用于对账 —— 只存变动量的话，一条记录出错后面全错 */
  balanceAfter: number;
}
/** 用户积分账户。**单位是积分个数** —— 商家侧是钱，用 {@link MerchantPointAccount} */
export interface PointAccount {
  /** 当前可用余额。**只含能花的分**，待生效的在 pendingBalance */
  balance: number;
  /**
   * 待生效积分：已发放但未过售后期，**不计入 balance**。
   *
   * 两个数必须分开展示（「可用 400 / 待生效 100」）。合成一个的话，
   * 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。
   */
  pendingBalance: number;
  /** 最近一批待生效积分的可用时间。`pendingBalance=0` 时为空 */
  pendingActivateAt?: number;
  /** 累计获得（含已用、已过期），只增不减 */
  totalEarned: number;
  /** 累计已抵扣 */
  totalUsed: number;
  /** 30 天内将过期的积分 */
  expiringSoon: number;
  /** 最近一批积分的过期时间。`expiringSoon=0` 时为空 */
  expiringAt?: number;
}
/**
 * 结算页的积分试算结果。**服务端算**，端上只负责显示。
 *
 * 端上自己算的话，下单时服务端会再算一遍 —— 两处算法只要有一点不同
 * （券后金额口径、运费是否参与、开关判断顺序），用户就会看到
 * 「结算页说能抵 30，下单后只抵了 25」，而这个差额没人解释得清。
 */
export interface PointsDeductible {
  /** 本单最多可抵扣的积分数。已扣掉四级开关与上限，端上直接用 */
  maxPoints: number;
  /** 对应金额（分） */
  maxAmountMinor: number;
  /** 用户当前可用余额，用于展示「你有 X 分」 */
  balance: number;
  /** 不可用时的原因，直接展示。可用时为空 */
  disabledReason?: string;
}
// ---------------------------------------------------------------- 地址簿

export interface Address {
  /**
   * 地址 ID。这里是 `Id` 不是 `No` —— 它不是业务单号，是用户地址簿里的一条本地记录，
   * 不跨端流转、不出现在订单快照里（下单时地址是**整体快照**进订单的）
   */
  addressId: string;
  /** 收货人姓名 */
  name: string;
  /** 收货人手机号 */
  phone: string;
  /** 省市区，拼好给人看的一串 */
  region: string;
  /**
   * 省 / 市 / 区县，**分开的三个**。
   *
   * 与 `region` 并存不是冗余：`region` 是展示用的一串（存量地址、地图回填都只有它），
   * 这三列是**能拿来算的**那份 —— 按省算运费、按区派单、按市校经营范围。
   * 后端 `usr_address` 一直有这三列，端上一直没填，于是那些规则全在 null 上求值，
   * 一条都不命中，而页面上完全正常。
   *
   * 可能为空：存量地址是纯手填的，拆不出来。
   */
  province?: string | null;
  /** 市 */
  city?: string | null;
  /** 区/县 */
  district?: string | null;
  /** 详细地址（街道门牌） */
  detail: string;
  /** 是否默认地址。整个地址簿至多一条为 true */
  isDefault: boolean;
  /** 标签：家 / 公司 / 其他 */
  tag?: string;
  /**
   * 收货点坐标（gcj02，E6）。地图选点回填；**可能为空** —— 存量地址是纯手填的。
   * 商家的「自送半径」要拿它跟门店坐标算距离，没有坐标那条规则就永远算不出结果。
   */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
}
// ---------------------------------------------------------------- 卡包

export interface UserCard {
  /** 卡号。核销时出示的就是它 */
  cardNo: string;
  /** 购买时的商品单号 */
  goodsNo: string;
  /** 卡名快照 */
  title: string;
  /** 卡面图 */
  cover: string;
  /** 储值卡剩余额度（最小货币单位） */
  balanceMinor?: number;
  /** 次卡剩余次数 */
  timesLeft?: number;
  /** 过期时间。过期后余额/次数作废 */
  expireAt: number;
  /** 购卡时锁定的货币，不随用户切市场变化 */
  currency: CurrencyCode;
}
// ---------------------------------------------------------------- 营销

/**
 * 领到手的那张券（`mkt_user_coupon` 的一行）。
 *
 * 与 {@link Coupon} 的关系：Coupon 是**模板**（活动配的那张），
 * UserCoupon 是**某个人手里的那一张**。领取接口返回的是后者 ——
 * 契约此前写成返回 Coupon，而后端一直返回这个形状，字段一个都对不上。
 * 页面恰好不读返回值（领完重拉列表），所以没人撞上；但契约说的是假话。
 */
export interface UserCoupon {
  /** 这个人手里那一张的编号 */
  userCouponNo: string;
  /** 券模板快照 */
  coupon: Coupon;
  /** UNUSED / USED / EXPIRED */
  status: string;
  /** 当前这笔订单能不能用它 —— 由服务端算，端上不要自己判门槛 */
  usableNow: boolean;
  /** 领取时刻 */
  receivedAt: number;
  /** 核销/使用时刻。空 = 还没用 */
  usedAt?: number;
}
// ---------------------------------------------------------------- 登录

/**
 * 登录方式。
 * · WX_MINI  小程序静默登录（只拿 openid，拿不到手机号）
 * · WX_PHONE 小程序一键取手机号（推荐：一次授权直接拿到号，省掉短信）
 * · WX_OPEN  App 微信开放平台
 * · APPLE    Apple 登录（iOS 上架硬要求）
 * · PHONE_OTP 手机号 + 短信验证码（全端兜底，也是商家账号的主标识）
 * · PASSWORD  手机号 + 密码（**只有 B 端有**）。商家一天开好几次 App，
 *   每次等一条短信是实打实的摩擦；而它与其它方式最本质的差别是**不建户** ——
 *   能用密码登录的前提是他已经设过密码，而设密码本身要先登录。
 */
export type GrantType =
  | "WX_MINI"
  | "WX_PHONE"
  | "WX_OPEN"
  | "PHONE_OTP"
  | "APPLE"
  | "PASSWORD";
export interface LoginReq {
  /** 登录方式，决定 principal / credential 各放什么 */
  grantType: GrantType;
  /** 凭证主体：`WX_*` 放 wx.login 的 code；`PHONE_OTP` 放手机号；`APPLE` 放 identityToken */
  principal: string;
  /** 凭证副本：仅 `PHONE_OTP` 需要，放短信验证码 */
  credential?: string;
  /** 邀请人。从邀请链接进入时带上，用于拉新归因 */
  inviterNo?: string;
  /** 从店铺码/店铺分享进入时带上，用于进店归因与费率分档（ADR-004 §5.4） */
  merchantNo?: string;
  /** 是否已勾选同意用户协议与隐私政策 —— 注册的合规前置，服务端要留痕 */
  agreed?: boolean;
}
export interface LoginResp {
  /** 访问令牌。后续请求放 `Authorization: Bearer <token>` */
  token: string;
  /** 登录用户档案 */
  user: User;
}
