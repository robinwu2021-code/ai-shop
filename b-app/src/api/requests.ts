// B 端请求类型（wire contract）—— **入参的唯一真源**。
//
// 为什么需要这一层（与 C 端 `api/requests.ts` 同一个理由）：
//   contract.ts 的方法签名是位置参数（`mShip(orderNo, expressNo)`），页面调着方便，
//   但**实际发到网络上的是一个对象**。两者是两回事，只有后者才是与服务端的约定。
//   没有这层类型，`openapi-b.yaml` 里的 requestBody 只能是空的 —— 而生成器自己写着
//   「从方法签名反推 body 是猜，猜出来的契约比没有契约更坏」，于是干脆不生成。
//   结果是后端要实现的 35 条 B 端端点**没有入参定义可依**，只能靠读前端源码。
//
// 怎么强制不漂移：
//   http.ts 发出去的 body 一律标 `satisfies XxxReq` —— 字段名写错、少传、多传都在编译期报错。
//
// 为什么不放进 packages/shared：
//   ADR-007 §3 的边界：contract 层不共享。B 端有自己的 `/biz/**` 入参，
//   放一起会诱导两端互相复用不该复用的东西。
import type {
  ArrivalIssueKind,
  CampaignDraft,
  SettleAccountType,
  StaffRole,
  DeliveryRule,
  GoodsStatus,
  GrantType,
  MerchantApplyReq,
  OrderStatus,
  StoreProfile,
} from "@shared/types";
import type { GoodsDraft } from "./contract";

// ---------------------------------------------------------------- 门店送货方式（方案 v4）

export interface StoreFulfillmentSaveReq {
  /** 全量：四路各一条。channel 值域 = 商家可配四路，服务端在写入口拦越界 */
  channels: Array<{
    channel: string;
    enabled: boolean;
    /** 仅 EXPRESS：运费模板号，空 = 平台默认 */
    templateNo?: string | null;
    /** 仅 NEIGHBOR_PICKUP：取货点引用，全量替换；不传 = 不改 */
    pickupNos?: string[];
    /** P2：ALL / SUBSET；不传 = 不改 */
    scopeMode?: string;
    /** P2：SUBSET 时适用的范围项 area_no，全量替换 */
    areaNos?: string[];
  }>;
}

/** 商家自建自提点（P1）。坐标必填：没坐标的点买家用定位永远找不到 */
export interface PickupSelfBuildReq {
  storeNo: string;
  name: string;
  address: string;
  latE6: number;
  lngE6: number;
  openHours?: string;
  /** 不传 = 按坐标就近归到已开通社区 */
  communityNo?: string;
}

// ---------------------------------------------------------------- 账号与入驻

export interface MerchantLoginReqBody {
  /** 登录方式。**商家池与 C 端用户池是两套账号**，同一手机号登两端是两个身份 */
  grantType: GrantType;
  /** `WX_MINI`: wx.login code；`PHONE_OTP`: 手机号 */
  principal: string;
  /** `PHONE_OTP`: 验证码 */
  credential?: string;
  /**
   * 是否勾选了用户协议与隐私政策 —— 注册的合规前置，服务端要留痕。
   *
   * 登录页一直在发（`{ ...req }` 把 `LoginReq.agreed` 带了出去），
   * **漏的是这里没声明**，于是生成的 OpenAPI 里没有它，而后端 `LoginReq` 有。
   * 这类漏声明比漏发更难发现：联调时一切正常，直到有人照着 spec 写另一个客户端。
   */
  agreed?: boolean;
}

/** 入驻申请。字段与共享层的 `MerchantApplyReq` 一致，这里只是给契约一个稳定的 DTO 名 */
export type MerchantApplyReqBody = MerchantApplyReq;

// ---------------------------------------------------------------- 店铺

export type SaveStoreReqBody = StoreProfile;

export interface ShareKitQuery {
  /** 不传即整店分享素材；传了就是单品的 */
  goodsNo?: string;
}

// ---------------------------------------------------------------- 商品

export interface GoodsListQuery {
  /** 页码，从 1 起 */
  page?: number;
  /** 每页条数 */
  size?: number;
  /**
   * 按商品状态过滤。B 端能看到全部状态，C 端只看得到 ON_SALE。
   *
   * <p>`OUT_OF_STOCK` **不是 `GoodsStatus` 的一员**：库里没有这个状态列，
   * 它按「所有 SKU 可用量都 ≤ 0」算出来，且与在售**不互斥**
   * —— 一件在售商品照样能全规格断货。B-4.1 一直写着这一筛，代码里此前没有。
   */
  status?: GoodsStatus | "OUT_OF_STOCK";
  /**
   * 按标题模糊搜。**服务层一直支持，端点此前写死传 null** ——
   * 于是商品页没有搜索，而商品一多这一页就只能靠滚。
   */
  keyword?: string;
  /**
   * 按类目筛（通常是二级）。与 `keyword` 是同一种遗漏：服务层一直支持，
   * 端点写死传 null。类目变必填之后，按类目找货是商家的主路径。
   */
  categoryNo?: string;
}

/**
 * 保存商品的**线上格式**，与页面用的 {@link GoodsDraft} 不同形状。
 *
 * <p>后端要的是「基准语言的那一份 + 三语 map」两个字段，而不是一个三语对象。
 * 此前这里直接 `= GoodsDraft`，于是端上把 `title` 当对象发过去，
 * 后端反序列化直接抛 —— **b-app 保存商品在真实后端上一次都没成功过**，
 * 而 mock 上完全正常，所以没人发现。拍平在 `http.ts` 里做，页面不受影响。
 */
export interface SaveGoodsReqBody {
  /** 商品单号。新建时不传，编辑时必传 */
  goodsNo?: string;
  /** 基准语言（zh-CN）的标题。后端按 Accept-Language 下发时的兜底 */
  title: string;
  /** 基准语言（zh-CN）的副标题/卖点 */
  subtitle: string;
  /** 标题的三语原文，键是 Lang。缺译的语言按 R9 回落展示中文 */
  titleI18n: Record<string, string>;
  /** 副标题的三语原文，同上 */
  subtitleI18n: Record<string, string>;
  /**
   * 类目单号。**必填，且是唯一的分类输入** ——
   * 商品形态（生鲜要截单、服务不发货、iOS 可售规则）由它派生，请求体里不再有 `type`。
   */
  categoryNo: string;
  /** 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 */
  cover?: string;
  /** 详情轮播图 */
  images?: string[];
  /** 详情区长图。**空数组也要发** —— 与 images 同一口径，不发就删不掉 */
  detailImages?: string[];
  /** 图文详情正文（纯文本）。**空串也要发** —— 后端「不传 = 不改」，删光了不发就删不掉 */
  detail?: string;
  /** 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 */
  specGroups: GoodsDraft["specGroups"];
  /** 支持的履约方式；不传 = 不改（新建默认四种全支持） */
  fulfillments?: string[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: GoodsDraft["skus"];
  /** 每人限购，0 = 不限。不传 = 不改 */
  limitPerUser?: number;
  /** 生鲜段：截单 / 到货描述 / 是否按实称 / 产地。不传 = 不改 */
  fresh?: GoodsDraft["fresh"];
  /** 服务段：时长 / 可核销门店。不传 = 不改 */
  service?: GoodsDraft["service"];
  /** 拼团档：起团人数 + 团价，要么都给要么都不给 */
  groupBuy?: GoodsDraft["groupBuy"];
  /**
   * 引用的平台标准品。传了它，服务端会用标准品的 categoryNo 与 optionCode
   * **覆盖**请求里的值；不传 = 自建品 / 脱离标准品。
   */
  stdNo?: string;
}

export interface ToggleGoodsReq {
  /** 目标状态：true 上架、false 下架。下架后详情页仍可访问但不可下单 */
  onSale: boolean;
}

export interface SaveStockReq {
  /** 要改库存的 SKU */
  skuNo: string;
  /** 改后的库存数。**是绝对值不是增量** */
  stock: number;
}

export interface UploadImageReq {
  /** 端上的临时文件路径。真实实现走 multipart，这里是 mock 与 H5 的折中 */
  tempPath: string;
}

/**
 * 自动生成图文详情。**主图可选** —— 没图时模型只按文字写，
 * 写出来更泛，但总比让商家对着空白框强。
 */
export interface DescribeGoodsReq {
  imageUrl?: string;
  /** 商品名。必须有，否则模型只能瞎编 */
  title: string;
  subtitle?: string;
  categoryNo?: string;
}

export interface RecognizeGoodsReq {
  /** 待识别的商品图 URL（先走 upload/image 拿到）。返回识别出的标题与类目建议 */
  imageUrl: string;
}

export interface SpecTemplatesQuery {
  /** 按**品类**过滤平台模板（兜底那一层）；不传则返回全部 + 商家自存 */
  categoryType?: string;
  /**
   * 已选**类目**。传了才拿得到类目级模板 —— 那是「专门给这一类的」那批，
   * 且会用同名规格组顶掉品类兜底。
   */
  categoryNo?: string;
}

export interface SaveSpecTemplateReq {
  /** 规格维度名，如「重量」 */
  name: string;
  /** 可选值列表。存成商家自己的模板（scope=MERCHANT），不影响平台模板 */
  options: string[];
}

// ---------------------------------------------------------------- 跨店对比

export interface CrossStoreCompareQuery {
  /**
   * 回看天数（含今天）。不传 = 30。
   *
   * 后端会夹在 1–365 并在返回体的 `days` 里回显 —— **窗口按回显的那个画**，
   * 不要照着自己发出去的值写「近 N 天」：传 99999 时两个数会对不上。
   */
  days?: number;
}

// ---------------------------------------------------------------- 订单与履约

export interface OrderListQuery {
  /** 页码，从 1 起 */
  page?: number;
  /** 每页条数 */
  size?: number;
  /** 按订单状态过滤，不传为全部 */
  status?: OrderStatus;
  /**
   * 看全部门店的单，不传/false 只看**当前门店**。
   *
   * ⚠️ 后端一直支持这个参数，端上从没传过 —— 于是订单页恒等于「当前门店」，
   * 而界面上既没有门店名也没有切换入口，多门店老板会以为自己看到的是全部。
   *
   * 「全部」对老板和店员**不是一回事**：老板的全部是主体名下所有店，
   * 店员的全部只是他被授权的那几家。这个区分在后端（allowedStoresOrAll），
   * 端上只管传不传。
   */
  allStores?: boolean;
}

export interface ShipReq {
  /** 快递单号。填了即视为已发货，订单流转到 SHIPPED */
  expressNo: string;
}

export type SaveDeliveryRuleReqBody = DeliveryRule;

export interface MarkArrivedReq {
  /** 批量：一次到货通常是一整批，逐单调用会让通知发成 N 条 */
  orderNos: string[];
  /**
   * 给哪个自提点登记；**不传 = 当前门店的那个点**。
   *
   * 一个商家两家店两个点是常态（自提点归属到门店之后）。不传且当前门店没有点时
   * 后端会拒 —— 而不是悄悄登记到另一个点上。
   */
  pickupNo?: string;
}

export interface VerifyReq {
  /**
   * 取货码。字段名必须是 `verifyCode` —— 后端 `BizPickupController.VerifyReq` 收的是它。
   * 这里曾经写作 `code`：**路径对得上、body 对不上**，守卫只比路径看不出来，
   * 联调时才会以 400 的形式暴露。
   */
  verifyCode: string;
  /** 代客核销（老人没带手机，店主代为确认）。留痕在服务端 */
  onBehalf?: boolean;
}

/** 批量核销（后端已实现 `/biz/pickup/verify/batch`）。高峰期一个个扫码是真实痛点 */
export interface VerifyBatchReq {
  /**
   * 一批取货码。**逐条尝试、不整批回滚** —— 失败的逐条回报（见 `VerifyBatchResult`），
   * 否则一张废码会让另外几单白扫。
   */
  verifyCodes: string[];
}

export interface ReportShortageReq {
  /** 出问题的 SKU */
  skuNo: string;
  /** 问题类型：少件 / 破损。两者的售后责任判定不同 */
  kind: ArrivalIssueKind;
  /** 情况说明。承接方填，供货方与平台据此定责 */
  note: string;
}

// ---------------------------------------------------------------- 售后

export interface HandleAfterSaleReq {
  /**
   * 驳回理由，**必填**（后端 `@NotBlank`）：用户拿不到理由只能升级平台，
   * 平台再回头问商家，多绕一圈。
   * 字段名是 `remark` 不是 `reply` —— 后端 `BizAfterSaleController.RejectReq` 收的是它。
   */
  remark: string;
}

// ---------------------------------------------------------------- 团购与报价

export interface CreateGroupReq {
  /** 要开团的商品，必须是本店已上架商品 */
  goodsNo: string;
}

/**
 * 报价。四个字段名全部按后端 `BizQuoteController.QuoteReq` 对齐 ——
 * 此前前端发的是 `{priceMinor, minCount, desc}`，后端收的是
 * `{unitPriceMinor, minQty, note, validDays}`，**没有一个对得上**，联调必 400。
 */
export interface QuoteReq {
  /** 单价（最小货币单位）。名字带 unit 是有意义的：报的是单价不是总价 */
  unitPriceMinor: number;
  /** 起订量 */
  minQty: number;
  /** 报价说明：规格、材质、是否含安装等，供发起人比价 */
  note: string;
  /** 报价有效期（天）。后端不传时默认 7 天 —— 报价不能无限期挂着 */
  validDays?: number;
}

// ---------------------------------------------------------------- 评价

export interface ReplyReviewReq {
  /** 回复内容。公开展示在评价下方，一条评价只能回一次 */
  reply: string;
}

export interface AppealReviewReq {
  /** 申诉理由。这是**唯一**能把差评送进平台裁决台的入口 */
  reason: string;
  /** 举证图：聊天记录、物流截图 */
  images?: string[];
}

// ---------------------------------------------------------------- 营销

export type SaveCampaignReqBody = CampaignDraft;

export interface ToggleCampaignReq {
  /** 目标状态：true 启动、false 暂停。暂停不影响已领取的券 */
  running: boolean;
}

// ---------------------------------------------------------------- 积分

export interface PointsRecordQuery {
  /** 账期 `YYYYMM`，不传为当期 */
  period?: string;
  /** 页码，从 1 起 */
  page?: number;
  /** 每页条数 */
  size?: number;
}

export interface TogglePointsReq {
  /** 目标状态。**关闭只影响将来** —— 已发出的分仍有效，已扣的服务费不退 */
  enabled: boolean;
}

/**
 * 提交收款进件。
 *
 * @property settleAccount 结算账号**明文**。只在这一次请求里存在 ——
 *   服务端转给通道后只留掩码，任何回显都是掩码（ADR-002 §5）。
 *   端上也不要缓存它：表单提交完就清空。
 */
export interface SubmitPaymentReq {
  /** 给哪个通道进件，如 WECHAT */
  payChannel: string;
  /** 结算账户形态。不传时后端按法律形态取默认（小微打个人、其余对公） */
  settleAccountType?: SettleAccountType;
  /** 结算账号明文。见上方说明：**不落库、不进日志、不回显** */
  settleAccount: string;
  /** 资质图地址。小微免传，个体户与企业必传 */
  licenses?: string[];
  /** 进件联系人。通道核对资料时联系他，不一定等于登录人 */
  contactName?: string;
  /** 进件联系电话 */
  contactPhone?: string;
  /**
   * 为**哪家门店**进件；不传 = 主体级默认号（单店永远走这条）。
   *
   * 传它就是在走「分开结算」：微信侧一个商户号只能绑一个结算账户，
   * 两家店各收各的钱，就得进件两次拿两个号。
   */
  storeNo?: string;
}

/** 新建/改名门店。门面其余部分（公告/营业时间/主推）走 SaveStoreReqBody */
/**
 * 从地图上选中的点直接开通聚落 —— 商家侧没有「提报/等审核」这一步了。
 * 重复由后端三道闸挡（村码 / 同街道归一名 / 坐标 150 米内），撞上返回既有那条。
 */
export interface OpenFromMapReq {
  name: string;
  address?: string;
  latE6: number;
  lngE6: number;
  /** 端上已知的街道码（9 位），只在服务端逆地理不可用时兜底 */
  streetCode?: string;
}

export interface StoreEditReq {
  /** 门店名 */
  name: string;
  /** 门店地址 */
  address?: string;
  /**
   * 这家店摆哪些货架（**只有新建时有意义**，改名时后端忽略）。
   *
   * <p><b>不传 = 复制默认店的</b>：多门店商家开分店卖的多半是同一批货，
   * 从零勾选是纯负担。一个都没有也合法 —— 建品时会自动加入。
   */
  categoryNos?: string[];
}

/** 停用/启用（门店与员工共用同一个形状） */
export interface SetActiveReq {
  /** true 启用 / false 停用 */
  active: boolean;
}

/** 换门店收款号。**不传或传空 = 回到主体默认号**，这是合法操作不是清空错误 */
export interface SetStorePaymentReq {
  /** 目标收款商户号。只能是本主体已开通的号；空 = 回到主体默认号 */
  payMerchantNo?: string;
}

/** 加员工。只要手机号 —— 不发密码、不建 C 端账号 */
export interface AddStaffReq {
  /** 员工手机号（11 位）。**它就是登录号** —— 员工用它 + 验证码进 B 端 */
  loginPhone: string;
  /**
   * 备注名（如「小张」）。选填但强烈建议 ——
   * 不填的话列表与审计里都只有一串脱敏尾号，三个人以后就分不清谁是谁。
   */
  displayName?: string;
}

/**
 * 授予或撤销**一个**门店角色。
 *
 * **增量式，不是覆盖式**：这一次只动 `role` 这一个角色，不碰他在这家店的其他角色。
 * 覆盖式在多角色下是错的 —— 老板想「再加一个配送员」，结果把「店员」冲掉了。
 */
export interface GrantStoreReq {
  /** 授权到哪家店。只能是本主体的门店 */
  storeNo: string;
  /** 要授予/撤销的那一个角色 */
  role: StaffRole;
  /** true 授予（默认）、false 撤销。撤到一个不剩 = 从这家店移除他 */
  granted?: boolean;
}

/** 员工登录。与商家登录同形状，但打的是另一个端点、解析出的是另一套身份 */
export interface StaffLoginReq {
  /** 员工的登录手机号（老板在员工管理里加的那个） */
  phone: string;
  /** 短信验证码 */
  code: string;
}
