// 门店：门面资料、店铺码、门店主页、配送规则、门店活动
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { FulfillmentReach, FulfillmentType, ServiceScope } from "./core";
import type { PickupRef } from "./fulfillment";
import type { MarketingCampaign } from "./marketing";
import type { Merchant, MerchantBrief, StaffRole } from "./merchant";
import type { Goods, SpecTemplate } from "./product";
import type { ServiceArea } from "./region";

/**
 * 门店送货方式的一行（方案 v4：channel 挂门店，每店每路一行）。
 *
 * <p>取代商家级 fulfillmentReach 单选。四路可配：STORE_PICKUP / NEIGHBOR_PICKUP /
 * MERCHANT_DELIVERY / EXPRESS —— 服务类两值是商品属性，不出现在这里。
 */
export interface StoreFulfillmentChannel {
  /** 履约渠道（快递 / 到店自提 / 邻里自提 / 商家自送 / 上门预约） */
  channel: FulfillmentType;
  /** 开着没有。**关掉意味着这条路从此不接新单**，已有的单照常履约 */
  enabled: boolean;
  /** 准入矩阵不允许（按主体类型）。端上置灰＋原因，不隐藏 */
  denied: boolean;
  /** 仅 EXPRESS：运费模板号；空 = 平台默认模板 */
  templateNo?: string | null;
  /** 仅 NEIGHBOR_PICKUP（P1）：已引用的取货点，含 PENDING 的自建点（商家要看到「审核中」） */
  pickups?: PickupRef[];
  /** 运营锁路（P2）：置灰不可改，文案「平台已暂停，联系运营」 */
  locked?: boolean;
  /** ALL / SUBSET（P2）：这一路只送经营范围的一个子集 */
  scopeMode?: string;
  /** SUBSET 时适用的范围项 area_no */
  areaNos?: string[];
}
export interface StoreFulfillment {
  /** 门店号 */
  storeNo: string;
  /** 固定四行，顺序即开关顺序 —— 服务端补缺，端上不用自己造 */
  channels: StoreFulfillmentChannel[];
}
/** 门店状态。READONLY = 已停用（不再接新单，已有单照常履约） */
export type StoreStatus = "ACTIVE" | "READONLY";
/**
 * 门店（商家侧管理用）。
 *
 * <p><b>门店与主体是关联不是归属</b>：换执照店照开。所以 `storeNo` 一旦生成就不再变 ——
 * 评价、订单、顾客的「我常逛的店」都挂在它上面。
 */
/**
 * 门店经营类目 —— 商家给自己的店摆的<b>货架</b>。
 *
 * <p>与「主体已获授权的类目」是两件事：那是<b>平台批的证</b>（能不能卖这一类），
 * 这是<b>商家的货架</b>（店里怎么摆）。责任人不同，所以不合成一个字段。
 */
export interface StoreCategory {
  /** 平台类目号。**改显示名不动它** —— 跨店聚合与比价都认这个 */
  categoryNo: string;
  /** 展示名：`displayName` 有就用它，否则是平台类目名。直接照它渲染 */
  name: string;
  /** 平台类目名。改名时要让商家看得见自己改的是谁 */
  platformName: string;
  /** 商家改的名。空 = 用平台名，不是「叫空字符串」 */
  displayName?: string;
  /** 店内展示顺序，小的在前。商家拖出来的顺序 */
  sort: number;
  /** 这个货架上有几件商品 —— **撤架之前商家要看得见代价**（有货就撤不掉） */
  goodsCount: number;
  /**
   * 正在卖的件数。**与 goodsCount 分开**：商家问「这一类卖得怎么样」时要的是它，
   * 问「能不能撤架」时要的是上面那个（含下架与待审的全部）。
   */
  onSaleCount: number;
  /** 待审的件数。它常常是「这一类为什么看起来没货」的答案 */
  pendingCount: number;
}
export interface Store {
  /** 门店号。一旦生成不再变 —— 换主体只换归属，不换它 */
  storeNo: string;
  /** 门店名 */
  name: string;
  /** 门店地址。顾客据此找到取货点，也是履约范围的锚点 */
  address?: string;
  /** 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 */
  isDefault: boolean;
  /** ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） */
  status: StoreStatus;
  /** 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" */
  payMerchantNo?: string;
  /** 这家店现在能不能收钱。照它显示，别自己去比状态串 */
  payReady: boolean;
  /** 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 */
  staffCount: number;
  /** 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 */
  rating?: number;
  /** 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 */
  ratingCount?: number;
  /**
   * 这家店的只读**是套餐降级压下来的**，不是店主自己停的。
   *
   * <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同：
   * 降级压的要**补缴/升档**，自己停的**点一下启用就开**。
   * 不分开的表现是店主反复点那个对降级店无效的启用按钮。
   */
  planSuspended?: boolean;
  /**
   * 经营模式（门店级）。自营门店加经营类目不判资质，「调整经营类目」面板因此不标「需资质」
   * （TDD-门店经营类目）。老后端不发 —— 端上按「不是自营」处理，宁可多提示不少提示。
   */
  businessMode?: BusinessMode;
  /**
   * 所属主体是不是平台自营（mch_entity.self_operated）。后端免资质按它判，
   * 「调整经营类目」面板标不标「需资质」也按它 —— **不要改回读 businessMode**：
   * 那一列每家新店默认都是 SELF_OPERATED，包括第三方商家的店（TDD-门店经营类目 §10）
   */
  selfOperated?: boolean;
}
/** 门店经营模式。与后端 MchStore.SELF_OPERATED / THIRD_PARTY、ops-web 的 BusinessMode 同一套取值 */
export type BusinessMode = "SELF_OPERATED" | "THIRD_PARTY";
export interface StoreRole {
  /** 哪家店 */
  storeNo: string;
  /** 门店名快照，列表直接显示，省一次查询 */
  storeName: string;
  /** MANAGER 店长 / CLERK 店员 */
  role: StaffRole;
}
/** 商家自送规则（ADR-005 §5：不做骑手系统，只有范围与门槛） */
export interface DeliveryRule {
  /** 配送半径，米 */
  radius: number;
  /** 起送价，最小货币单位 */
  minOrderMinor: number;
  /** 配送费，最小货币单位 */
  feeMinor: number;
  /** 免配送费门槛，最小货币单位；0 表示不免 */
  freeThresholdMinor: number;
}
/**
 * 店铺门面（B-11.2 店铺装修 → C 端门店主页的数据源）。
 * 与 Merchant 分开：Merchant 是平台建档的商家主数据（名称/资质/评分，商家改不了），
 * 这里是**店主自己能改的门面内容**。混在一起的话，改公告要走审核就荒谬了。
 */
export interface StoreProfile {
  /** 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） */
  announcement: string;
  /**
   * 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。
   *
   * 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」——
   * 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是
   * 「商家看是空的、买家看到的是昨天的货」。
   */
  announcementUntil?: number | null;
  /** 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 */
  announcementRecent?: string[];
  /**
   * 正卡在人审里的那条公告（机审命中转的），没有就是 null。
   *
   * **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话，
   * 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑，
   * 反复再发一次，队列里堆出一串同样的单子。
   */
  noticePending?: { content: string; submittedAt: number } | null;
  /** 营业时间文案，店主自填 */
  openHours: string;
  /**
   * 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。
   * 与 {@link addressDetail} 分开：重新选点只覆盖这一条。
   */
  address: string;
  /**
   * 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。
   *
   * 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截；
   * 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失，
   * 地址看着还是对的，只是又回到了小区门口。
   */
  addressDetail?: string;
  /** 主推商品，按顺序展示在门店主页首屏 */
  featured: string[];
  /**
   * 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** ——
   * 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款），
   * 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。
   */
  serviceScope: ServiceScope;
  /** scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 */
  serviceCommunityNos: string[];
  /** scope=CITY 时覆盖的城市 */
  serviceCityCode?: string;
  /**
   * 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看 {@link serviceAreas}。
   *
   * 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** ——
   * 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。
   */
  fulfillmentReach?: FulfillmentReach;
  /**
   * 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。
   *
   * **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方：
   * PICKUP 空 = 谁也看不到（没配自提点就没法履约）；
   * ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。
   */
  serviceAreas?: ServiceArea[];
  /**
   * 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。
   * 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。
   */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
}
/** 店铺码（C-ST-08 扫码进店的商家侧） */
export interface StoreQrcode {
  /** 商家单号 */
  merchantNo?: string;
  /** 印在贴纸上的短码。**去掉了 0/O/1/I/L**，让人手输时不会认错 */
  storeCode?: string;
  /**
   * 这个码属于**哪家门店**（V298 一店一码）。
   *
   * ⚠️ **必须显示出来**：多门店店主看到的只是一串码，看不出它是哪家店的。
   * 印 500 张贴纸之前，这是唯一能发现「贴错店」的机会 ——
   * 贴错之后没有任何症状：码扫得通、页面打得开，只是客流算到了另一家头上。
   *
   * 空 = 主体连门店行都没有的历史数据，不是「属于所有店」。
   */
  storeNo?: string | null;
  /**
   * 落地页链接。**未配对外域名时为 null** —— 端上据此不显示链接那一行。
   *
   * ⚠️ 此前后端在两处各写死一个 `https://shop.example.com/s/<code>` 占位域名，
   * 商家复制出去的链接与印出去的贴纸**全都指向一个不存在的地方**，
   * 而这两个功能点在清单上标着「已实现」。不发假链接比发一个点不开的强。
   */
  url?: string | null;
  /**
   * 店铺**小程序码**的 PNG base64（不含 `data:` 前缀）。通道未开启时为 null。
   *
   * 用小程序码而不是 H5 链接：ADR-004 的主获客路径是「码印在包装袋上，老客扫码直达」，
   * 而小程序码**不依赖备案域名**（备案要 7–20 个工作日），扫了直接进门店页。
   */
  imageBase64?: string | null;
  /** 打印建议，服务端给的一句话 */
  printableHint?: string;
}
/** 分享素材（B-11.2.7）。文案由服务端按当前语言与市场生成 */
export interface ShareKit {
  /** 分享文案，已按当前语言与市场生成 */
  text: string;
  /** 落地页链接，文案里已经拼过一次；未配对外域名时为空串。真正的海报图走 {@link Poster} */
  posterUrl: string;
}
/**
 * 真海报（B-11.11 补，2026-08-24）：封面图/店名/价格/小程序码合成的一张 PNG，
 * 能直接发朋友圈——`ShareKit.posterUrl` 一期只是落地页链接，不是图。
 */
export interface Poster {
  /** PNG 的 base64（不含 data: 前缀）。生不出来（商家异常）时为 null */
  imageBase64: string | null;
}
export interface StoreFront {
  /** 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） */
  announcement: string;
  /**
   * 公告最后一次发布的时刻（epoch 毫秒）。没发过、或已过期时为空。
   *
   * **这一行必须带时间**：一句没有时间的「今天到了新米」，既可能是今早写的，
   * 也可能是上个月忘了撤的 —— 老客分不出来就不会再照着它跑一趟，
   * 而「照着公告来一趟」正是这行字存在的全部理由。
   */
  announcementAt?: number | null;
  /** 营业时间文案，店主自填 */
  openHours: string;
  /** 店铺地址，店主自填 */
  address: string;
  /**
   * 门店坐标（gcj02，E6）。**可能为空** —— 商家没在地图上标过点。
   * 买家侧据此决定「导航到这里」显不显示：没坐标的导航按钮点了只会打开一片空白。
   */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
}
/** 店铺页上的一类。`count` 直接显示，省得买家点进去数 */
export interface StoreShelf {
  /** 类目号 */
  categoryNo: string;
  /** 名称 */
  name: string;
  /** 这一类下有几件在架。直接显示，省得买家点进去数 */
  count: number;
}
export interface StoreHome {
  /** 平台建档的商家主数据（名称/资质/评分），店主改不了 */
  merchant: MerchantBrief;
  /** 店主自己维护的门面内容 */
  store: StoreFront;
  /** 在售商品。首屏展示，分页靠单独的商品列表接口 */
  goods: Goods[];
  /**
   * 本店货架：**店主自己排的顺序、自己改的名字**（「本地时鲜」而不是「蔬菜」）。
   *
   * 只含真的有在售商品的类目 —— 摆着却一件货都没有的类目，点进去空手而归。
   * 少于两条时端上不画这一行：一个恒真的筛选开关只是占地方。
   */
  categories: StoreShelf[];
  /** 我是否收藏了这家店 */
  favorited: boolean;
  /**
   * 已停业（门店非 ACTIVE：商家自助停用或平台强制下线）。
   *
   * **是标志而不是 404**：扫码进来的老客要知道「店关了」，不是「链接坏了」。
   * 端上据此盖「已停业」并禁掉加购。
   *
   * ⚠️ 后端 `StoreHomeVO` 一直在发这个字段，这里此前没声明 ——
   * 于是**扫码进一家已停业的店，看起来与正常营业毫无区别**，
   * 加购、下单一路走到底，最后在库存或下单闸门上撞一个说不清的错误。
   */
  closed?: boolean;
}
/** 常买清单的一行（C-ST-02）。按购买频次排序，不是按时间 */
export interface FrequentItem {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。常买是按 SKU 记的 —— 买惯了 5 斤装的人不想要 10 斤装 */
  skuNo: string;
  /** 商品标题 */
  title: string;
  /** 封面图 */
  cover: string;
  /** 规格文案 */
  spec: string;
  /** 当前价（可能已与上次购买时不同） */
  price: number;
  /** 上次买的价，用于「涨价了」提示 */
  lastPrice: number;
  /** 买过几次。列表按它排序，不是按时间 */
  times: number;
  /** 上次购买时间 */
  lastAt: number;
  /** 已下架/无库存 —— 一键再来一单时要显式标出，不能静默丢掉 */
  invalid?: boolean;
}
/** 一键再来一单的结果（C-ST-03）。**丢了什么必须说清楚**，静默少加是投诉源头 */
export interface ReorderResult {
  /** 成功加入购物车的件数 */
  added: number;
  /** 已失效、没加进购物车的商品名 */
  dropped: string[];
  /** 涨价了但仍加入的商品名 */
  priceUp: string[];
}
/**
 * 商家活动（P5，新模型 `pmt_activity`）。
 *
 * @remarks 名字带 Store 前缀是因为 `MarketingCampaign` 已经被老模型占着。
 * 四类玩法在这里是**取值组合**而不是一个 type：
 * 满减 = AMOUNT × CUT，限时特价 = GOODS × PRICE，买赠 = QTY × GIFT，发券 = NONE × COUPON。
 */
/** 自己组合一行的种类：条件（全部满足才生效）/ 优惠（按顺序叠加） */
export type ActivityRuleKind = "CONDITION" | "BENEFIT";

/**
 * 自己组合的一行（原型 s11）：一个条件或一个优惠。条件全部满足才生效，优惠按顺序叠加。
 */
export interface ActivityRuleItem {
  /** CONDITION / BENEFIT */
  kind: ActivityRuleKind;
  /** 条件：AMOUNT 满金额 / QTY 满件数 / GOODS 指定商品；优惠：CUT 减 / PERCENT 打折 / POINTS 送积分 */
  type: string;
  /** AMOUNT 的门槛、CUT 的减额（最小货币单位） */
  amountMinor?: number | null;
  /** QTY 的件数、POINTS 的分数 */
  n?: number | null;
  /** PERCENT 的折扣（万分比，8000 = 8 折） */
  bp?: number | null;
  /** PERCENT 的封顶（最小货币单位），必填 */
  capMinor?: number | null;
  /** GOODS 的商品 */
  goodsNos?: string[] | null;
}

export interface StoreActivity {
  /** 活动号 */
  activityNo: string;
  /** 活动名。商家自己起，出现在活动列表与冲突提示里 */
  name: string;
  /** `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 */
  goal?: string | null;
  /** 限定到某一家门店。空 = 主体下所有门店 */
  storeNo?: string | null;
  /** `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 / `GROUP` 拼够人数成团 */
  triggerType: string;
  /** 满额门槛（分）。triggerType=AMOUNT 时用 */
  triggerAmountMinor?: number | null;
  /** 满件门槛。triggerType=QTY 时用 */
  triggerQty?: number | null;
  /** `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 */
  benefitType: string;
  /** 优惠金额（分）。CUT 是减多少，PRICE 是改成多少 */
  benefitAmountMinor?: number | null;
  /** 赠品件数。benefitType=GIFT 时用 */
  benefitQty?: number | null;
  /** 赠品商品号或券号，随 benefitType 变 */
  benefitRef?: string | null;
  /** `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 */
  scheduleType: string;
  /** 开始时刻（毫秒） */
  startAt?: number | null;
  /** 结束时刻（毫秒） */
  endAt?: number | null;
  /** RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` */
  scheduleRule?: string | null;
  /** 限量：这个活动最多优惠多少单。空 = 不限量 */
  quota?: number | null;
  /** 已用掉的限量 */
  quotaUsed: number;
  /** 还剩多少 = quota - quotaUsed。不限量时为空 */
  quotaLeft?: number | null;
  /** 预算上限（分）。空 = 不限 —— 用尽后自动停，已享受的不回收 */
  budgetMinor?: number | null;
  /** 已花掉的预算（分） */
  budgetUsedMinor: number;
  /** 最大敞口 = 限量 × 单次优惠。建活动页要显示它 */
  maxExposureMinor?: number | null;
  /** 空数组 = **对所有人生效**。老活动迁过来就是这个状态 */
  audiences: Array<{ type: string; value: string }>;
  /** 参与的商品。空 = 全店商品都参与 */
  goodsNos: string[];
  /** `DRAFT` / `RUNNING` / `PAUSED` / `ENDED` */
  status: string;
  /** `EXPIRED` / `QUOTA` / `BUDGET` / `MANUAL`。商家问「怎么停了」要有答案 */
  endedReason?: string | null;
  /**
   * 此刻是不是真的在生效。**与 status 分开**：周期活动在非时段里 status 仍是 RUNNING，
   * 而商家问的是「现在减不减」。
   */
  liveNow: boolean;
  /** 社区集单（`CUTOFF`）：每期截单时刻 HH:mm，市场时区 */
  cutoffTime?: string | null;
  /** 社区集单：提货日 = 截单日 + N 天，缺省 1 */
  pickupOffset?: number | null;
  /** 社区集单：提货日几点起可取 HH:mm */
  pickupFrom?: string | null;
  /** 社区集单：起订量（份）。空 = 不设，截单即成 */
  minQty?: number | null;
  /** 社区集单：每期份数上限。空 = 不限 */
  periodQuota?: number | null;
  /** 社区集单：未达起订量时的处理时限（小时）。空 = 平台缺省 14，每个活动可单独配 */
  decideHours?: number | null;
  /** 拼团（`GROUP`）：开团后多少小时内成团。空 = 24 */
  groupHours?: number | null;
  /** 自己组合（COMBO）的条件与优惠；其余玩法为空 */
  rules?: ActivityRuleItem[] | null;
}
/** 建活动入参。`activityNo` 为空 = 新建 */
export interface StoreActivityDraft {
  /** 活动号。**为空 = 新建**；传了就是改这一个 */
  activityNo?: string;
  /** 活动名。商家自己起，出现在活动列表与冲突提示里 */
  name: string;
  /** `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 */
  goal?: string | null;
  /** 限定到某一家门店。空 = 主体下所有门店 */
  storeNo?: string | null;
  /** `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 / `GROUP` 拼团 / `CUTOFF` 社区集单 */
  triggerType?: string;
  /** 满额门槛（分）。triggerType=AMOUNT 时用 */
  triggerAmountMinor?: number | null;
  /** 满件门槛。triggerType=QTY 时用 */
  triggerQty?: number | null;
  /** `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 */
  benefitType: string;
  /** 优惠金额（分）。CUT 是减多少，PRICE 是改成多少 */
  benefitAmountMinor?: number | null;
  /** 赠品件数。benefitType=GIFT 时用 */
  benefitQty?: number | null;
  /** 赠品商品号或券号，随 benefitType 变 */
  benefitRef?: string | null;
  /** `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 */
  scheduleType?: string;
  /** 开始时刻（毫秒） */
  startAt?: number | null;
  /** 结束时刻（毫秒） */
  endAt?: number | null;
  /** RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` */
  scheduleRule?: string | null;
  /** 限量：这个活动最多优惠多少单。空 = 不限量 */
  quota?: number | null;
  /** 预算上限（分）。空 = 不限 —— 用尽后自动停，已享受的不回收 */
  budgetMinor?: number | null;
  /** 定向到哪些人。**空数组 = 对所有人生效**，不是「谁也不发」 */
  audiences?: Array<{ type: string; value: string }>;
  /** 参与的商品。空 = 全店商品都参与 */
  goodsNos?: string[];
  /** 社区集单（`CUTOFF`）：每期截单时刻 HH:mm，市场时区 */
  cutoffTime?: string | null;
  /** 社区集单：提货日 = 截单日 + N 天，缺省 1 */
  pickupOffset?: number | null;
  /** 社区集单：提货日几点起可取 HH:mm */
  pickupFrom?: string | null;
  /** 社区集单：起订量（份）。空 = 不设，截单即成 */
  minQty?: number | null;
  /** 社区集单：每期份数上限。空 = 不限 */
  periodQuota?: number | null;
  /** 社区集单：未达起订量时的处理时限（小时）。空 = 平台缺省 14，每个活动可单独配 */
  decideHours?: number | null;
  /** 拼团（`GROUP`）：开团后多少小时内成团。空 = 24 */
  groupHours?: number | null;
  /** 自己组合（COMBO）的条件与优惠；其余玩法为空 */
  rules?: ActivityRuleItem[] | null;
}
/**
 * 「我的规格」里的一组：**这家店的一个货架类目**，以及它能用到的规格。
 *
 * <p>按货架类目给而不是给平台的全部通用维度：一家只卖蔬菜和肉的店，
 * 看到「尺码」「口径」「时长」是纯噪音，而噪音会让他觉得这一页与自己无关。
 *
 * <p>`dims` 可能是空的 —— 那是运营还没给这个类目配规格，**商家看得见才问得出来**。
 */
export interface StoreCategorySpecs {
  /** 类目号 */
  categoryNo: string;
  /** 店主改过名的用店主的叫法（「好菜」而不是「蔬菜」）—— 这一页是给他看的 */
  categoryName: string;
  /** 销售规格：买家要挑一档，每档单独定价、单独算库存 */
  dims: SpecTemplate[];
  /**
   * 商品参数：只描述，不分 SKU、不影响价格与库存。
   *
   * <p>与 `dims` 并排而不是合成一列加个字段：它们在界面上是两块，
   * 合成一列端上每处都要先过滤，而漏过滤一次就是「产地」被当成规格。
   */
  props?: SpecTemplate[];
}


/**
 * 社区集单的一期（B 端 s20 / s31 / s33）。
 * **份数、人数、金额都是从订单现算的**，不是存的 —— 只算已付款且未退的。
 */
/**
 * 集单一期的状态（与后端 `PmtPeriod` 常量同源）。
 * `OPEN` 收单中 / `SHORT` 未达起订量待处理 / `CONFIRMED` 已成 / `CANCELLED` 已取消
 */
export type BatchPeriodStatus = "OPEN" | "SHORT" | "CONFIRMED" | "CANCELLED";

export interface BatchPeriod {
  /** 期号（`PD…`） */
  periodNo: string;
  /** 这一期属于哪个集单活动 */
  activityNo: string;
  /** 活动名，列表上「09-18 · 每日鲜果」的后半段 */
  activityName?: string | null;
  /** 截单日 YYYY-MM-DD */
  periodDate: string;
  /** 截单时刻（毫秒）。商家提前截单后是那一刻 */
  cutoffAt: number;
  /** 提货日 YYYY-MM-DD */
  pickupDate: string;
  /** 提货日几点起 HH:mm */
  pickupFrom?: string | null;
  /** 见 {@link BatchPeriodStatus} */
  status: BatchPeriodStatus;
  /** 已付款且未退的份数 */
  qty: number;
  /** 下单人数（去重） */
  customers: number;
  /** 已付款且未退的金额（分） */
  amountMinor: number;
  /** 起订量，空 = 不设 */
  minQty?: number | null;
  /** 每期上限，空 = 不限 */
  periodQuota?: number | null;
  /** SHORT 时：过了这个点不处理就自动取消并退款 */
  decideDeadline?: number | null;
}

/** 一期的详情：按商品、按自提点的份数（s20） */
export interface BatchPeriodDetail {
  /** 这一期本身（份数、人数、金额已现算） */
  period: BatchPeriod;
  /** 按商品汇总的份数，只算已付款且未退的 —— 采购按它 */
  byGoods: Array<{ goodsNo: string; title: string; qty: number }>;
  /** 按自提点汇总的份数 —— 配送按它 */
  byPickup: Array<{ pickupNo: string | null; pickupName: string | null; qty: number }>;
}

/** 「去采购」：按 SKU 汇总，给进货单预填 */
export interface BatchPurchaseLine {
  /** SKU 号。进货单按它匹配库存物料 */
  skuNo: string;
  /** 商品号 */
  goodsNo: string;
  /** 商品名 */
  title: string;
  /** 规格，如「5 斤装」 */
  spec?: string | null;
  /** 这一期要进的份数 */
  qty: number;
}

/** 营销入口一屏的数字（s01）。全部现算 */
export interface MarketingSummary {
  /** 本月让利（分），活动 + 券，撤销的不算 */
  monthDiscountMinor: number;
  /** 本月享受过优惠的订单数 */
  monthOrders: number;
  /** 进行中的活动数 */
  activityRunning: number;
  /** 在发的券数 */
  couponIssuing: number;
  /** 今天收单中的各期份数之和 */
  periodTodayQty: number;
  /** 今天最早的截单时刻；没有收单中的期时为空 */
  periodTodayCutoffAt?: number | null;
  /** 未达起订量、等处理的期数（黄标） */
  periodsShort: number;
  /** 还差人的团数（黄标） */
  groupsShort: number;
  /** 等待报价的求团需求数（黄标） */
  quotesPending: number;
  /** 还能报名的平台活动数 */
  enrollable: number;
}

/** 平台活动的报名门槛（原型 s29「报名门槛」「类目」「城市」） */
export interface PlatformEnrollRule {
  /** 评分下限；空 = 不限 */
  minRating?: number | null;
  /** 是否要求无违规 */
  noViolation: boolean;
  /** 只收这些类目的货；空 = 不限 */
  categoryNos: string[];
  /** 只收这些城市的店；空 = 不限（暂只展示、不校验） */
  cityCodes: string[];
}

/** 报名状态 */
export type EnrollmentStatus = "SUBMITTED" | "APPROVED" | "REJECTED" | "WITHDRAWN";

/** 一份报名（原型 s28 提交后 · s30 审核表的一行） */
export interface PlatformEnrollment {
  /** 报名单号 */
  enrollmentNo: string;
  /** 平台活动 */
  activityNo: string;
  /** 报名的商家 */
  entityNo: string;
  /** 商家名 */
  merchantName: string;
  /** 报名的货 */
  goodsNos: string[];
  /** 报的份数 */
  quota: number;
  /** 已用份数 */
  quotaUsed: number;
  /** 最多平台补贴（最小货币单位）= 份数 × 每单平台补贴 */
  platformMaxMinor: number;
  /** 最多商家承担 = 份数 × 每单商家承担 */
  merchantMaxMinor: number;
  /** 商家评分（审核时看） */
  rating: number;
  /** 状态 */
  status: EnrollmentStatus;
  /** 驳回理由 */
  rejectReason?: string | null;
  /** 审核时间 */
  reviewedAt?: number | null;
  /** 提交时间 */
  createdAt: number;
}

/**
 * 平台活动（原型 s27 · s28 · s29 · s30）。规则与商家活动同一个模型；多出来的只有出资、预算、报名。
 * 出资写成**每单金额**（s28 的约定）：商家看的是「一单我出多少」。
 */
export interface PlatformActivity {
  /** 活动单号 */
  activityNo: string;
  /** 名称 */
  name: string;
  /** 触发：NONE 立减 / AMOUNT 满额 / QTY 满件 */
  triggerType: string;
  /** 满多少（分） */
  triggerAmountMinor?: number | null;
  /** 满几件 */
  triggerQty?: number | null;
  /** 优惠：现只有 CUT（减钱） */
  benefitType: string;
  /** 减多少（分） */
  benefitAmountMinor?: number | null;
  /** 活动开始 */
  startAt?: number | null;
  /** 活动结束 */
  endAt?: number | null;
  /** 报名截止 */
  enrollDeadline?: number | null;
  /** 平台出资万分比：10000 全额 / 5000 一半 / 0 不出 */
  platformShareBp: number;
  /** 平台预算（分） */
  budgetMinor?: number | null;
  /** 已通过的报名占掉的预算 */
  reservedMinor: number;
  /** 每单平台最多补贴 */
  perOrderPlatformMinor: number;
  /** 每单商家最多承担 */
  perOrderMerchantMinor: number;
  /** 报名门槛 */
  enrollRule: PlatformEnrollRule;
  /** DRAFT / RUNNING / ENDED */
  status: string;
  /** 待审报名数 */
  submitted: number;
  /** 已通过报名数 */
  approved: number;
  /** 已驳回报名数 */
  rejected: number;
  /** 商家端：我的报名；没报过为空 */
  mine?: PlatformEnrollment | null;
}
