// 商品与规格：类目、标准品、SKU、规格模板
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { CategoryType, FulfillmentType } from "./core";
import type { AppointmentDaySlots } from "./fulfillment";
import type { BizScope, MerchantBrief } from "./merchant";

/** 新加的规格取值（商家自建维度）。同上：命名是为了它能进契约 */
export interface SpecValueAdded {
  /** 平台值池里的编号。**有它才参与筛选与跨店比较** */
  valueNo: string;
  /** 码值 */
  code: string;
  /** 显示名 */
  label: string;
}
// ---------------------------------------------------------------- 商品

/**
 * 类目树节点（对齐后端 `CategoryVO`）。
 *
 * <p>⚠️ **不要把它和 `CategoryType` 搞混** —— 那是五品类枚举
 * （NORMAL/FRESH/SERVICE/VIRTUAL/CARD），挂在商品上、由平台硬编码，决定履约与合规
 * （冷链、不发货、iOS 可售规则）。这里的类目树是运营可维护的数据，决定归类与经营准入。
 * 两个维度正交，见 `docs/technical/类目树补齐方案.md`。
 *
 * <p>这个类型此前声明了一个后端根本不返回的 `type` 字段，并写着「仅两级」——
 * 而后端一直是三级。没人用它，所以错了很久也没暴露。
 */
export interface Category {
  /** 类目单号 */
  categoryNo: string;
  /** 上级类目单号。一级类目为空 */
  parentNo?: string | null;
  /** 1–3。**三级封顶** */
  level: number;
  /** 类目名（后端按 Accept-Language 下发已本地化文案） */
  name: string;
  /** 类目图标 URL。运营没配就是空串，端上按占位渲染 */
  icon?: string;
  /**
   * 该类目的**品类模板**：`STANDARD` / `FRESH` / `SERVICE` / `VOUCHER`。
   *
   * <p><b>它就是「品类」，只是另一套码</b>（STANDARD↔NORMAL、VOUCHER↔CARD，
   * 见 `TEMPLATE_TO_TYPE`）。选定类目即可推出品类 —— 让商家把同一件事填两遍，
   * 唯一的产出是两者可能互相矛盾，而矛盾没有任何一处会拦。
   */
  template?: string;
  /**
   * 经营这个类目要的授权码；**空 = 无门槛**。
   *
   * <p>与 `BizScope.categoryCodes` 比对，端上就能在选之前说清楚「你还不能卖这一类」——
   * 不下发的话商家只能靠「选了、保存、被拒」这条路才知道，
   * 而那句报错既说不出缺哪张证，也说不出去哪申请。
   */
  requiredCode?: string;
  /** 人读的资质名，如「食品经营许可证」。展示用，判据是 `requiredCode` */
  qualifications?: string[];
  /** 同级内的展示顺序，小的在前。运营在后台拖动排序改的就是它 */
  sort: number;
  /** 子类目。叶子是空数组而不是 undefined —— 端上少一次判空 */
  children: Category[];
}
/**
 * 平台标准品（TDD-标准品库）：商家引用建品的**模子**。
 *
 * <p>**无价、无库存、无履约** —— 那些永远是商家的。它存在的理由是 `specGroups`
 * 里的 `optionCode`：没有标准品，三家店各自录「本地菠菜」得到三个毫无关系的商品，
 * 聚合、比价、统计全都无从谈起。
 *
 * <p>取用时端上只是把字段**填进表单**，商家可以改标题与图；但**类目与 optionCode
 * 由服务端强制以标准品为准** —— 能改掉的话，标准品就退化成一个填表助手。
 */
export interface SpuStd {
  /** 标准品号 */
  stdNo: string;
  /** 所属类目。取用后**改不掉**：类目决定形态（生鲜要截单、服务不发货） */
  categoryNo: string;
  /** 类目名，展示用 */
  categoryName?: string;
  /** 标题 */
  title: string;
  /** 标题的多语言版本 */
  titleI18n?: Record<string, string>;
  /** 副标题 */
  subtitle?: string;
  /** 封面图 */
  cover?: string;
  /** 图集 */
  images?: string[];
  /** 每个选项都带 `optionCode` —— 跨店可比靠的就是它 */
  specGroups: SpecGroup[];
  /** 别名/品牌/俗称，搜索用。端上可以不展示 */
  keywords?: string;
  /** 状态 */
  status?: string;
  /** 被引用次数，只给运营排序用 */
  refCount?: number;
  /** 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 */
  barcode?: string;
  /**
   * 出处：`OPS` 运营手录 / `OFF` 从 Open Food Facts 导入。
   * 众包来的那批全是待审状态，运营靠它把「还没人看过的」与「自己录的」分开审。
   */
  source?: string;
}
/** 规格维度，例：{ name: "重量", options: ["约5斤", "约10斤"] } */
export interface SpecGroup {
  /** 规格维度名，如「重量」「包装」 */
  name: string;
  /** 该维度的可选值，如 `["约5斤", "约10斤"]` */
  options: string[];
  /**
   * 与 options 一一对应的模板编码。来自模板的选项有值，自由输入的为空。
   * 一期只写入不消费 —— 但不留位的话，二期做规格聚合要刷全部历史商品。
   */
  optionCodes?: (string | undefined)[];
  /** 该规格组来自哪个模板（便于「用的人多不多」这类平台侧统计） */
  templateNo?: string;
}
export interface Sku {
  /** SKU 单号。下单、库存、订单行都指向它，不是指向 goodsNo */
  skuNo: string;
  /**
   * 各规格维度上的取值，顺序与 Goods.specGroups 一一对应。
   * 单规格商品长度为 1；多规格（如 重量 × 包装）长度 >1。
   */
  optionValues: string[];
  /** 展示用拼接文案（后端下发，端上不自己拼，避免多语言分隔符差异） */
  spec: string;
  /** 售价（最小货币单位） */
  price: number;
  /** 划线价（最小货币单位）。为空表示不展示划线价 */
  originPrice?: number;
  /** 可售库存。下单时服务端二次校验，端上这个值只用于展示与预校验 */
  stock: number;
  /** FRESH 且按重计价：标称重量（克） */
  nominalGram?: number;
  /**
   * 成本价（最小货币单位）。**只有商家侧 `/biz/goods/{no}` 下发，C 端恒空。**
   *
   * 进货价是商家的经营秘密，出现在买家端的响应里就等于公开了。
   * 它不参与任何计价，只用来在编辑页实时算毛利。
   */
  costPrice?: number;
  /**
   * 商品条码 EAN-13 / UPC（V252）。**只在商家侧下发** —— 它是商家与供应商/ERP
   * 之间的键，对买家没有用处，而条码还能反查到进货渠道。
   */
  barcode?: string;
  /** 商家自有货号。他 ERP 里的主键，同样只在商家侧下发 */
  merchantSkuCode?: string;
  /**
   * 计量单位（件 / 斤 / kg / 份）。**买家侧也要** ——
   * 「5」到底是 5 件还是 5 斤，买家同样需要知道才判断得了贵不贵。
   */
  saleUnit?: string;
  /**
   * 各市场价（市场码 → 最小货币单位）。**只有商家侧 `/biz/goods/{no}` 下发，C 端恒空。**
   *
   * <p>编辑页按市场逐格填，而保存是**整份覆盖** —— 拿不到整张表就只能回填当前
   * 那一格，于是改一次标题，其余市场的价格行就被删了，且不报错：
   * 那两个市场的买家从此看不到这件商品。与 `titleI18n` 是同一个形状的故障。
   */
  priceByMarket?: Record<string, number>;
  /**
   * 本店单独定的价（最小货币单位）。**只在 B 端下发，空 = 同主体价**，不是 0。
   *
   * <p>与门店库存回退方向相反：没设过价的店按主体价卖，没设过库存的店按 0 卖 ——
   * 价格视为 0 就是白送。
   */
  storePrice?: number;
}
/** 卡券属性（CARD） */
export interface CardSpec {
  /** 储值卡面值（最小货币单位）；次卡为空 */
  faceValueMinor?: number;
  /** 次卡总次数；储值卡为空 */
  timesTotal?: number;
  /** 有效期天数 */
  validDays: number;
}
/**
 * 促销：买 N 送 M。
 * 语义：购买数量达到 N 件，赠送 M 件 —— 用户**付 N 件的钱，收到 N+M 件**。
 * 赠品不进计价（价格为 0），只作为订单里的独立行存在，履约时随单发出。
 */
export interface Promotion {
  /** 促销类型。目前只有买 N 送 M 一种 */
  type: "BUY_N_GET_M";
  /** 购买件数门槛 N */
  buyN: number;
  /** 赠送件数 M */
  giftM: number;
  /** 赠品商品号；不填则赠同款 */
  giftGoodsNo?: string;
  /** 赠品展示名（后端下发已本地化） */
  giftTitle?: string;
}
/** 虚拟商品属性（VIRTUAL） */
export interface VirtualSpec {
  /** 发放说明，如「支付后 1 分钟内短信发码」 */
  deliverDesc: string;
}
/**
 * 一条商品参数。
 *
 * <p>`valueNo` 是平台值池里的编号，**有它才参与筛选与跨店比较**；
 * 量纲型（功率、净重）平台不枚举值，那时只有 `label`。
 */
export interface GoodsParam {
  /** 所属规格维度（`usage_type=PROP`） */
  dimNo: string;
  /**
   * 维度名（「产地」「保质期」）。**买家页要显示它** ——
   * 只有 dimNo 的话详情页上是一行 `SD_ORIGIN: 本地`。
   *
   * <p>存在商品身上而不是每次去规格库查：它是**下单那一刻的快照**，
   * 与规格组同一口径 —— 商家事后把本店叫法改了，已卖出的商品不该跟着变。
   */
  name?: string;
  /** 平台值编号。量纲型没有 */
  valueNo?: string;
  /** 平台值编码，跨店可比 */
  code?: string;
  /** 展示文案 */
  label: string;
}
export interface Goods {
  /**
   * <b>本店</b>上不上架（多门店，B 端列表下发）。
   *
   * ⚠️ **`null` / 缺省 = 未按店管理**（跟随主体级 `onSale`），**不是「未上架」**。
   * 上下架早就按门店落行了，而主体的 `onSale` 是「任一门店在售就为真」的总闸 ——
   * 只看它的话，A 店下架完那件货还写着「在售」，店长会以为没点上。
   */
  storeOnSale?: boolean | null;

  /** 商品单号 */
  goodsNo: string;
  /** 商品标题 */
  title: string;
  /** 副标题/卖点一句话 */
  subtitle: string;
  /** 封面图 URL。列表页用这一张 */
  cover: string;
  /** 详情轮播图 URL 列表 */
  images: string[];
  /**
   * 图文详情区的长图，按顺序全宽竖排。
   *
   * **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、
   * 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是
   * 一张 1:3 的长图被塞进方形轮播里。
   */
  detailImages?: string[];
  /**
   * **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。
   *
   * <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU，
   * 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。
   */
  params?: GoodsParam[];
  /** 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 */
  type: CategoryType;
  /** 所属类目 */
  categoryNo: string;
  /** 所属商家 —— 商品与服务都要展示商家信息 */
  merchant: MerchantBrief;
  /** 本商品的评分与评价数（区别于商家整体评分） */
  rating?: number;
  /** 本商品的评价条数 */
  ratingCount?: number;
  /** 展示价（最小货币单位），取各 SKU 最低价 */
  price: number;
  /** 划线价（最小货币单位） */
  originPrice?: number;
  /** 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 */
  fulfillments: FulfillmentType[];
  /** 规格维度定义；单规格商品也有一组 */
  specGroups: SpecGroup[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: Sku[];
  /** 累计销量，展示用 */
  sales: number;
  /** FRESH：预售截单时间戳 */
  cutoffAt?: number;
  /** FRESH：预计到货描述 */
  arrivalDesc?: string;
  /** FRESH：是否按实称多退少补 */
  weighed?: boolean;
  /** FRESH：产地 */
  origin?: string;
  /** SERVICE：服务时长（分钟） */
  durationMin?: number;
  /** SERVICE：可核销门店 */
  storeName?: string;
  /**
   * ⚠️ **以下四个字段后端从不下发**：`slots` / `card` / `virtual` / `promotions`。
   *
   * `GoodsVO` 里一个都没有 —— c-app 的分类/首页/搜索/商品/店铺五个页面按契约
   * 写完了渲染，接真后端后永远拿到 `undefined`，落进兜底分支，**不报错**。
   * mock 下它们有值，所以这条差异在开发期完全看不出来。
   *
   * 与「五品类差异字段没有写入路径」是同一个洞的两侧：一侧没有写入，一侧没有下发。
   * **不在这一轮删**：删掉要同时改五个页面的渲染，而「卡券与虚拟商品是不是
   * 商家自助能建的东西」还没有产品结论 —— 见 `商品域-优化清单` P3-5。
   */
  /** SERVICE + APPOINTMENT：可预约时段。**后端未下发** */
  slots?: AppointmentDaySlots[];
  /** CARD。**后端未下发** */
  card?: CardSpec;
  /** VIRTUAL。**后端未下发** */
  virtual?: VirtualSpec;
  /** 促销（一期只有买 N 送 M）。**后端未下发** */
  promotions?: Promotion[];
  /** 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 */
  groupBuy?: { minCount: number; price: number };
  /**
   * 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列，
   * 但全仓没有任何读写。等积分域接上再兑现。
   */
  points?: number;
  /** 每人限购，0 = 不限 */
  limitPerUser: number;
  /** 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 */
  onSale: boolean;
  /**
   * 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。
   *
   * 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时
   * `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」，
   * 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。
   *
   * 待审是 `PENDING`（词典 §11 的通用状态词表；库里那列仍叫 AUDITING，
   * 但那是审核结果那一轴的列名，不出现在契约里）。
   */
  /**
   * 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染，
   * 别拿一个空白区块占着详情页。
   */
  detail?: string;
  /** 状态 */
  status?: GoodsStatus;
  /**
   * 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。
   *
   * **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。
   * 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳
   * 还是被平台下的。过审时清空。
   *
   * ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着
   * 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。
   * 那句注释描述的是一件**从未发生过**的事。
   */
  auditReason?: string;
  /**
   * 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。
   *
   * 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格，
   * 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**：
   * C 端缺译文时回落中文，看起来一切正常。
   */
  titleI18n?: Record<string, string>;
  /** 三语副标题原文，同 `titleI18n` */
  subtitleI18n?: Record<string, string>;
  /**
   * 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。**
   *
   * <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于
   * **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛，
   * 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。
   */
  stdNo?: string;
  /**
   * 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**，
   * C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。
   *
   * <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行，
   * 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。
   */
  hasDraft?: boolean;
}
/** 规格模板归属：平台统一维护 / 商家自存 */
export type SpecTemplateScope = "PLATFORM" | "MERCHANT";
/** 商品在商家侧的状态。C 端只看得到 ON_SALE */
/**
 * 商品状态。
 *
 * ⚠️ 待审用 `PENDING` 不用 `AUDITING` —— ops-web 的 `SkuStatus` 一直用
 * `PENDING`，同一件事两个词。词典 §11 的通用状态词表规定「已提交待处理」= `PENDING`。
 */
/**
 * 商家侧商品状态。
 *
 * <p><b>DRAFT 与 PENDING 是两件事</b>：草稿是「还没提交，等你」，待审是「已提交，等平台」——
 * 说错了商家的下一步就错了。也与 OFF_SALE（点一下就能卖）分开。
 */
export type GoodsStatus = "DRAFT" | "ON_SALE" | "OFF_SALE" | "PENDING" | "REJECTED";
// ================================================================ 规格模板

/**
 * 规格选项。
 *
 * `code` 是**能不能做规格聚合的分水岭**：
 * 三家店卖同一种米，自由输入会写成「5斤」「五斤」「2.5kg」——
 * 这三个字符串在库里毫无关系，将来想做「按重量筛选 / 同规格比价」全部落空，
 * 而且不可回溯（历史商品已经写死）。所以模板带来的值必须带 code。
 *
 * 自由输入的值只有 label、没有 code：照常展示，但不参与聚合。
 * **一期只写入不消费**，聚合搜索是二期 —— 但字段现在就得留位。
 */
export interface SpecOption {
  /** 来自模板时有值；商家自己输入的没有 */
  code?: string;
  /** 选项展示文案，如「约5斤」 */
  label: string;
}
/**
 * 商品编码批量导入的试算 / 结果（P4）。
 *
 * <p>四个数各回答一件事：**这份表有多少行、会改几行、几行没变化、几行有问题**。
 * 少了「没变化」那一格，商家会把「改了 3 行」读成「另外 197 行失败了」。
 */
export interface SkuIdentityReport {
  /** 数据行数，不含表头 */
  total: number;
  /** 会真正写下去的行数 */
  willSet: number;
  /** 匹配上了但三列都没变的行数 */
  noChange: number;
  /** 有问题的行：认不出货号、值不合法。**要能逐行看** —— 只给个数商家不知道改哪一行 */
  problems: { line: number; reason: string }[];
  /** 前几行的前后对照，让他确认「改的是不是我想的那些」 */
  samples: {
    skuNo: string;
    goods: string;
    spec: string;
    barcodeFrom?: string | null;
    barcodeTo?: string | null;
    codeFrom?: string | null;
    codeTo?: string | null;
    unitFrom?: string | null;
    unitTo?: string | null;
  }[];
}
/**
 * 规格模板。两层：
 *   · PLATFORM —— 平台按类目预置，可聚合可筛选
 *   · MERCHANT —— 商家把自己常用的存下来，第二次建品直接套
 *
 * ⚠️ **模板是建议不是强制**：卖手工酱菜的没有匹配模板，硬要他选就只能瞎选。
 */
/**
 * 商家对平台规格的覆盖：**本店用哪几个、什么顺序、叫什么**。
 *
 * <p><b>改名只改展示</b>：`dimNo` 一个字不变，所以跨店聚合照常成立。
 * 与「我的类目」的 `displayName` 是同一个模式 —— 那里已经证明过这条边界站得住。
 *
 * <p>传空数组 = 清掉覆盖、完全跟平台走。**与平台一致的不落行**：
 * 这样运营给类目加了新维度，没动过手的商家会自动获得它。
 */
export interface SpecOverride {
  /** 规格维度号 */
  dimNo: string;
  /** false = 本店不用它。移除掉的在界面上收在下面，能加回来 */
  enabled: boolean;
  /**
   * **本店叫法**；空 = 用平台的。只换展示，`dimNo` 一个字不变 ——
   * 所以三家店的同一个规格照样聚得到一起。与「我的类目」的 displayName 同一个模式。
   */
  label?: string;
  /** 用哪几档。商家自己输入的档位也在这里（它已经落进规格库，与平台值同轴） */
  values?: { code: string; enabled: boolean }[];
}
/**
 * 「我的规格」里的一条自建维度。
 *
 * <p>与 {@link SpecTemplate} 的差别是**视角**：那个回答「建品时能挑什么」，
 * 这个回答「我拥有什么、能改什么、动它会影响多少」。
 */
export interface MerchantSpecDim {
  /** 规格维度号 */
  dimNo: string;
  /** 名称 */
  name: string;
  /** 这个维度下的取值数（含平台档位 + 自己加的） */
  valueCount: number;
  /**
   * 用在几件商品上。**按规格组名统计** —— 存量商品的规格快照里只有名字，
   * 没有维度编号（那个字段是后加的），按编号统计的话老商品一件都算不进来，
   * 而「停用它会影响什么」问的恰恰是历史。
   */
  usedCount: number;
  /** ACTIVE 在用 / ARCHIVED 已归档（不再出现在选择器里，历史商品的快照照旧） */
  status: SpecTemplateStatus;
  /** 已建 / 上限。摆出来，而不是等他建到第 11 个才被拒 */
  dimUsed: number;
  /** 维度配额上限，按档位取 */
  dimQuota: number;
  /** 取值配额上限 */
  valueQuota: number;
  /** 这个维度下的取值 */
  values: SpecOption[];
}
export interface SpecTemplate {
  /** 模板单号 */
  templateNo: string;
  /** 模板归属：平台统一维护 or 商家自存。商家只能改自己的 */
  scope: SpecTemplateScope;
  /** 平台模板按品类推荐；商家模板不限品类 */
  categoryType?: CategoryType;
  /**
   * 类目级模板的归属类目；**空 = 品类兜底**。
   *
   * <p>端上靠它区分两层：类目级排在前面并标出来。不下发的话两批混在一起，
   * 商家分不出哪个是「专门给这一类的」。
   */
  categoryNo?: string;
  /** 规格维度名，如「重量」「香型」 */
  name: string;
  /** 该维度的可选项 */
  options: SpecOption[];
  /** scope=MERCHANT 时归属的商家 */
  merchantNo?: string;
  /**
   * **主维度**：选完类目该自动建出来的就是这一组（每个类目至多一个，守卫测住）。
   *
   * <p>不下发的话端上只能靠「数组第一个」猜 —— 后端确实那么排，但那是巧合而非契约：
   * 排序一改端上跟着错，症状是「自动建出来的是包装不是重量」，没有一处会报错。
   *
   * <p>商家自存模板与品类兜底模板恒为 false：主维度是**类目绑定**上的判据，
   * 那两条路不经过绑定表。
   */
  primary?: boolean;
}
/** 规格模板状态。ARCHIVED 归档后不再出现在选择器里，但历史商品的快照照旧 */
export type SpecTemplateStatus = "ACTIVE" | "ARCHIVED";
