// B 端唯一契约。页面只依赖这个接口，不感知 mock / 真实后端。
// 端点对齐后端 B 端 BFF `/mb/**`（C 端是 `/mp/**`）。
//
// 类型全部来自 @shared/types —— **不在这里重复定义**。同一笔订单两端看到的是同一个
// Order 结构，只是可见字段与可执行动作不同；各定义一份必然漂移。
import type {
  BizScope,
  Region,
  StaffRole,
  AfterSale,
  ArrivalIssueKind,
  Category,
  Community,
  CommunityApply,
  CommunityApplyReq,
  CrossStoreCompare,
  CrossStoreOverview,
  MerchantPlan,
  DeliveryRule,
  Goods,
  CurrencyCode,
  MarketId,
  GoodsStatus,
  I18nText,
  MasterData,
  Message,
  MerchantStaff,
  StaffLog,
  MerchantRole,
  PickupOrder,
  PermOption,
  Quote,
  VerifyResult,
  Store,
  PaymentApplyment,
  MerchantApplyReq,
  MerchantApplyStatus,
  MerchantPointAccount,
  MerchantPointsRecord,
  MerchantLoginResp,
  MerchantProfile,
  MerchantCustomer,
  MerchantStats,
  MerchantTodo,
  Order,
  PageQuery,
  PageResult,
  PickingRow,
  GroupBuy,
  GroupRequest,
  CampaignDraft,
  MarketingCampaign,
  Review,
  SettleBill,
  ShareKit,
  SpecTemplate,
  StoreProfile,
  StoreQrcode,
  LoginReq,
  OrderStatus,
  VerifyBatchResult,
  PickupOverview,
  RateCard,
} from "@shared/types";

/** 拍照识别的结果。全部是**建议值**，店主可改可弃 */
export interface GoodsGuess {
  title: string;
  /** 一句话卖点。模型从包装上的广告语里提取，店主可改可弃 */
  subtitle: string;
  type: Goods["type"];
  /**
   * 类目编号。**后端已按候选表校验过** —— 模型给出的编号不在类目树里时是空串，
   * 不会把一个查无此项的编号塞进草稿（那样商家要到点保存那一刻才撞上类目校验）。
   */
  categoryNo: string;
  /**
   * 识别置信度 0–1。低于阈值时页面不预填，只提示「没认出来」。
   *
   * <p>⚠️ 它答的是「**我看这张图看得准不准**」，不是「这是不是一个能上架的商品」。
   * 拿一张纯色纹理图去识别，模型会 0.95 地确信那是「红色斜纹图案」—— 实测过。
   * 所以这道闸拦得住「看不清」，拦不住「看清了但那不是商品」。
   */
  confidence: number;
}

/** 规格组草稿：一个维度（如「重量」）与它的取值（「5 斤」「10 斤」） */
export interface SpecGroupDraft {
  /** 规格维度名，如「重量」「香型」 */
  name: string;
  /** 该维度的可选值 */
  options: string[];
  /**
   * 与 options 一一对应的模板编码。来自平台模板的有值，手输/改过的为空。
   * **一期只存不用** —— 但不存的话，二期做规格聚合要刷全部历史商品。
   */
  optionCodes?: (string | undefined)[];
  /** 该规格组来自哪个平台模板。手输的为空 */
  templateNo?: string;
}

/**
 * SKU 草稿。`optionValues` 的顺序与 `specGroups` 一一对应 ——
 * 这是矩阵的坐标，错位就会出现「5 斤卖成 10 斤的价」。
 */
export interface SkuDraft {
  /** 已有 SKU 带上原编号，改价改库存不会丢历史订单的引用 */
  skuNo?: string;
  /** 各规格维度上的取值，顺序与 specGroups 一一对应 */
  optionValues: string[];
  /** 当前市场的价（最小货币单位）。兼容单市场调用 */
  price: number;
  /**
   * **按市场分别定价**（B6）。未填的市场不在该市场售卖 ——
   * 汇率换算出的价没有价格心理学（¥29.9 → $4.19 不是任何人会标的价），
   * 且汇率一动全店价格跟着抖，而商家并没有调价。
   *
   * <p>⚠️ 键是**市场码**（CN/AE/US），不是币种码。这两套码一一对应，所以写错了
   * 不报任何错 —— 但落到 `prd_sku.market` 上就成了一行 `market='CNY'` 的死数据：
   * C 端按市场取价永远取不到它。此前端上按 `currency` 发，于是每个新 SKU 多一行脏数据，
   * 且商家在 AED/USD 页签填的价**在那两个市场一分钱也卖不出去**。
   */
  priceByMarket?: Partial<Record<MarketId, number>>;
  /** 可售库存 */
  stock: number;
}

/**
 * 商家侧商品草稿。单规格 = 一个规格组一个选项，与多规格同一套结构。
 *
 * 标题与副标题是**三语**：三语是一期范围（C端清单 §五之二），
 * 但此前商品文案只有一份，中文抄进三语 —— 切到英文看到的还是中文。
 * **只有中文必填**，其余留空时由服务端回落中文并标注未翻译（不做机翻，见 §M8-2）。
 */
export interface GoodsDraft {
  /** 商品单号。新建时不传，编辑时必传 */
  goodsNo?: string;
  /** 商品标题（多语言）。后端按 Accept-Language 下发对应语言给 C 端 */
  title: I18nText;
  /** 副标题/卖点（多语言） */
  subtitle: I18nText;
  /** 商品形态，决定详情页用哪套字段。**保存后不建议再改** */
  type: Goods["type"];
  /**
   * 类目单号（三级树的任意一级，通常是叶子）。选填。
   *
   * ⚠️ 与上面的 `type` 是两个维度：`type` 决定履约与合规、平台硬编码；
   * 类目决定归类与经营准入、运营可维护。
   *
   * <p>**保存草稿时不校验资质，上架时才校验** —— 商家可能正准备去申请那张证，
   * 保存这一步就拦住他，等于逼他先把商品归到一个错误的类目下。
   */
  categoryNo?: string;
  /**
   * 封面图 URL（来自 `mUploadImage`）。
   *
   * <p>此前<b>页面上传了封面却没放进提交体</b> —— 店主选了图、页面上也显示出来了，
   * 保存后 C 端拿到的却是空封面。而空封面不报错，只是列表里一块留白。
   */
  cover?: string;
  /** 详情轮播图 */
  images?: string[];
  /** 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 */
  specGroups: SpecGroupDraft[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: SkuDraft[];
  /**
   * 该商品支持的履约方式：`STORE_PICKUP` / `NEIGHBOR_PICKUP` /
   * `MERCHANT_DELIVERY` / `EXPRESS`。
   *
   * <p><b>留空 = 不改</b>（新建时后端默认四种全支持，由商家收窄）。
   * 传空数组是另一件事 —— 一种履约都不支持的商品谁也买不了，后端会拒。
   *
   * <p>下单时会校验「用户选的方式该商品必须支持」，所以收窄之后
   * C 端就选不到被去掉的那几种了。
   */
  fulfillments?: string[];
}

import type { PointsRecordQuery, StaffLoginReq, StoreEditReq, SubmitPaymentReq, TogglePointsReq } from "./requests";

export interface MerchantApi {
  // ---- 账号与入驻（B-11.1）
  mLogin(req: LoginReq): Promise<MerchantLoginResp>;
  mProfile(): Promise<MerchantProfile>;
  /** 上次提交的申请，驳回后回填用 */
  /**
   * 员工登录：手机号 + 验证码，**不建 C 端账号**。
   *
   * 与 {@link mLogin} 是两条路：那条走消费者账号（老板自己），
   * 这条走商家账号（`mch_account`）。店员多半**没有也不需要**消费者账号 ——
   * 要求他先注册成消费者才能上班，是把雇佣关系硬塞进一个消费关系里。
   *
   * 不是本主体在职员工时后端返回 403，**不是「账号不存在」** ——
   * 后者会把「谁是这家店的员工」变成一条可枚举的信息。
   */
  /**
   * 发送短信验证码。
   *
   * **它此前不在端点表里** —— B 端登录页的「发送验证码」按钮只是把 1234 填进输入框：
   * mock 下直接把 1234 填进输入框，于是这条缺失一直被盖住，
   * 而真实环境里没有人收得到验证码，登录整条路走不通。
   *
   * 返回体不含验证码（后端刻意如此）—— 这条别为了联调方便破例。
   */
  mSendOtp(phone: string): Promise<void>;

  /**
   * 设置 / 修改登录密码。**要求已登录** —— 当前会话即授权，不收旧密码
   * （要旧密码会把「忘了密码」变成死路，而重设的正路本来就是「验证码登录进来再设」）。
   */
  mSetPassword(password: string): Promise<void>;

  /** 我设过密码没有 —— 决定「我的」页里显示「设置密码」还是「修改密码」 */
  mHasPassword(): Promise<{ hasPassword: boolean }>;

  mStaffLogin(payload: StaffLoginReq): Promise<MerchantLoginResp>;

  mApply(payload: MerchantApplyReq): Promise<MerchantProfile>;
  /**
   * 上次申请。返回的是**申请单**而不是请求体 —— 回填要用的是「上次填了什么」，
   * 而「审到哪一步、为什么被驳回」和它是同一份数据，拆成两条接口只会让两边不同步。
   */
  mApplyDraft(): Promise<MerchantApplyStatus | null>;
  /**
   * 平台主数据：行业 / 主体类型 / 支付通道。
   *
   * 入驻表单需要它才能回答「这个行业能不能选小微」—— 此前主体列表是页面里的常量，
   * 微信放开某个行业的小微白名单要发版才能生效，而选错主体的后果是进件被拒。
   */
  mMasterData(): Promise<MasterData>;

  // ---- 收款进件（ADR-002）—— **与入驻审核是两条独立链路**
  /** 每通道一条。微信过了、支付宝还没过是正常状态，合并成一个会让人以为都没好 */
  mPayments(): Promise<PaymentApplyment[]>;
  /** 结算账号明文只在这一次请求里存在：库里只留掩码，回显也只有掩码 */
  mSubmitPayment(payload: SubmitPaymentReq): Promise<PaymentApplyment>;
  /** 主动回查。留这个入口是因为**回调会丢**，丢了商家就永远停在「审核中」 */
  mRefreshPayment(payChannel: string): Promise<PaymentApplyment>;

  // ---- 店铺与获客（B-11.2）—— **一期主获客路径的商家侧**（ADR-004）
  mStore(): Promise<StoreProfile>;
  /** 设经营范围时可勾选的社区。真实环境应只返回该商家已签约自提点所在的社区 */
  mCommunities(): Promise<Community[]>;
  /**
   * 区划的**直接下级**，逐级往下点。`parent` 空取省级。
   *
   * 一次只给一级而不是整棵树：全国到街道是 4.4 万行，一次拉下来端上要等好几秒，
   * 而商家真正要点开的只有其中一条路径。
   */
  mRegions(parent?: string): Promise<Region[]>;
  /**
   * 提报一个平台还没有的小区（ADR-013 阶段三）。
   *
   * 在这之前商家**无路可走**：覆盖项只能从已有社区里勾，而「让平台加一个小区」
   * 没有入口 —— 只能找 BD 口头说，说完没人知道进展。
   */
  mApplyCommunity(payload: CommunityApplyReq): Promise<CommunityApply>;
  /** 我提报过的。没有它，提报出去等于石沉大海，商家只会隔几天再提一次同样的 */
  mMyCommunityApplies(): Promise<CommunityApply[]>;
  mSaveStore(payload: StoreProfile): Promise<StoreProfile>;

  // ---- 门店管理（M6）—— 与 mStore 的分工：那个管**一家店的门面**，这个管**有几家店**
  /** 含停用的。停用的也要看得见 —— 看不见的话商家会以为店被删了 */
  mStoreList(): Promise<Store[]>;
  /** 新建。**超额直接拒** —— 建出来却打不开的店比拒绝更难解释 */
  mCreateStore(payload: StoreEditReq): Promise<Store>;
  mRenameStore(storeNo: string, payload: StoreEditReq): Promise<Store>;
  /** 停用/启用。**默认店不能停用** —— 停掉之后「这个主体的店在哪」就没答案了 */
  mSetStoreStatus(storeNo: string, active: boolean): Promise<Store>;
  /** 转移默认标。显式动作 —— 勾选式会出现两家默认或零家默认的中间态 */
  mSetDefaultStore(storeNo: string): Promise<Store>;
  /** 换收款号。只能挑本主体已开通的；传空 = 回到主体默认号（合法操作） */
  mSetStorePayment(storeNo: string, payMerchantNo?: string): Promise<Store>;

  // ---- 员工与授权（B-11.10）
  /** 含停用的。手机号已脱敏 */
  mStaffList(): Promise<MerchantStaff[]>;
  /** 加员工。**不发密码、不建 C 端账号** —— 他用自己的手机号验证码登录 */
  mAddStaff(loginPhone: string, displayName?: string): Promise<MerchantStaff>;
  /** 停用/启用。**老板不能被停用** */
  mSetStaffStatus(mchAccountNo: string, active: boolean): Promise<MerchantStaff>;
  /** 授权到店。role 传空 = 收回这家店的授权 */
  /**
   * 授予或撤销**一个**门店角色（一人一店可多角色，权限取并集）。
   *
   * @param granted 不传 = 授予；传 false = 撤销这一个角色
   */
  /**
   * 我在当前门店的作用域与权限。**登录后与切门店后都要调**。
   *
   * 页面按返回的 `perms` 裁剪入口 —— 后端拒绝是安全边界，前端隐藏是体验，
   * 两者都要有：只做后端，员工会看到一堆点了报错的入口；只做前端，那不是安全。
   */
  mBizScope(): Promise<BizScope>;

  mGrantStore(mchAccountNo: string, storeNo: string, role: StaffRole,
              granted?: boolean): Promise<MerchantStaff>;
  /**
   * 员工与授权的变更记录。**与员工管理同一档权限**（老板）——
   * 「谁给谁加了什么权限」本身就是权限信息，能看它的人不该比能改它的人多。
   *
   * @param mchAccountNo 只看某个人的；不传看全部
   */
  mStaffLogs(mchAccountNo?: string): Promise<StaffLog[]>;

  // ---- 角色（V71 自定义角色）
  /**
   * 本主体可用的角色：6 个预置（只读）+ 自定义。
   *
   * **每个角色带 `permLabels`（中文）与 `usedBy`（几个人在用）** ——
   * 前者让老板勾权限时不用盲选，后者是删除按钮的依据。
   */
  mRoles(): Promise<MerchantRole[]>;
  /**
   * 自定义角色可以勾的权限点（不含 `biz:store:admin`）。
   *
   * **勾选面板的唯一取值来源** —— 不要拿 `mRoles()` 的并集当选项，
   * 那份少一条 `biz:finance`（只有老板有，而老板那行是 `*`）。
   */
  mRolePerms(): Promise<PermOption[]>;
  /**
   * 建自定义角色。
   *
   * ⚠️ **`biz:store:admin` 不在可勾列表里**，传了后端也会拒（70006）——
   * 那是「管人」的码，授出去等于让被授权的人能改所有人的授权。
   */
  mCreateRole(payload: { name: string; perms: string[] }): Promise<MerchantRole>;
  /** 改名 / 改权限。**预置角色会被拒**（10400），要改先复制一份 */
  mUpdateRole(roleCode: string, payload: { name: string; perms: string[] }): Promise<MerchantRole>;
  /** 删除。**还有人在用时拒**（10409）—— 删了那些人的权限凭空消失 */
  mDeleteRole(roleCode: string): Promise<void>;

  mStoreQrcode(): Promise<StoreQrcode>;
  /** 分享素材：整店或单品。文案要带「还差 N 人」这类可直接转发的内容 */
  mShareKit(goodsNo?: string): Promise<ShareKit>;

  // ---- 工作台（B-10.1 + B-11 汇总）
  mTodo(): Promise<MerchantTodo>;
  mStats(): Promise<MerchantStats>;

  // ---- 跨店总览与对比（B-11.12.5 / 11.12.6，增值包 P2）
  /**
   * 按店并列的今日 / 本月 / 三项待办。**单店商家也能打开** —— 他看到的就是他那一家，
   * 不是空列表也不是报错。
   *
   * ⚠️ **无能力位（FREE 档）会抛 `ApiError(70023)`**（`PLAN_CAPABILITY_REQUIRED`，
   * 文案带当前档位）。调用方要接住它并渲染**示例态**：
   * 入口照常显示、点进去看得到这一页长什么样 + 一句升档说明。
   * 渲染成空白页或红色报错的话，商家看到的是「功能坏了」而不是「这是付费功能」。
   */
  mCrossStoreOverview(): Promise<CrossStoreOverview>;
  /**
   * 按店对比：销售额 / 订单数 / 复购率 / 缺货数，外加一个**主体级**评分。
   *
   * ⚠️ 返回体里的 `rating` **不在每店那一行上**（`rvw_review` 只有 `entity_no`）——
   * 画成表格的一列会让各店显示同一个数字。放在页面顶部作主体口径说明。
   *
   * @param days 回看天数（含今天），默认 30。后端夹在 1–365 并在 `days` 字段回显实际值
   */
  mCrossStoreCompare(days?: number): Promise<CrossStoreCompare>;

  // ---- 我的增值包（B-11.13，增值包 P4）
  /**
   * 我的档位、用量与三档对比。
   *
   * ⚠️ **只有老板调得到**（`biz:store:admin`）—— 店长会拿到 403。
   * 端上按 `can('biz:store:admin')` 决定要不要渲染入口，别让店长点进去看报错。
   *
   * 用量（`storeUsed` / `staffUsed`）**一律用后端给的**：自己拿门店列表数会与
   * 建店那道闸的口径分岔（闸门只数营业中的店），表现是「页面说满了、其实还能建」。
   */
  mMyPlan(): Promise<MerchantPlan>;
  /**
   * 自助开通试用。**一主体一次，永不回退**。
   *
   * 能不能点看 `MerchantPlan.trialTier`（null = 不能）。**不要自己用
   * `planCode === 'FREE' && !trialUsed` 推** —— 那会漏掉「平台把试用天数配成 0」。
   *
   * @returns 开通后的视图，直接用它重渲染，不必再拉一次 {@link mMyPlan}
   */
  mStartTrial(): Promise<MerchantPlan>;

  // ---- 商品（B-11.3）
  /**
   * @param q.keyword 按标题模糊搜。服务层一直支持，端点此前写死传 null ——
   *                  商品一多，没有搜索的列表就只能靠滚
   */
  mGoodsList(q: PageQuery & { status?: GoodsStatus; keyword?: string }): Promise<PageResult<Goods>>;
  mGoodsDetail(goodsNo: string): Promise<Goods>;
  mSaveGoods(payload: GoodsDraft): Promise<Goods>;
  mToggleGoods(goodsNo: string, onSale: boolean): Promise<Goods>;
  mSaveStock(goodsNo: string, skuNo: string, stock: number): Promise<Goods>;
  /**
   * 改**当前门店**的库存（多门店）。门店走 `X-Store-No` 头，http-client 自动带。
   *
   * ⚠️ 第一次对某个 SKU 调用它，这个 SKU 就整体转为按店管理 ——
   * 此后没设过库存的门店卖不出这件商品（视为 0，不是回退主体总量）。
   * 所以只在商家**确实有多家店**时才用它，单店仍走 mSaveStock。
   */
  mSaveStoreStock(goodsNo: string, skuNo: string, stock: number): Promise<Goods>;

  // ---- 商品图片与拍照建品（B-11.3.7 / E9）
  /** 上传一张图，返回可访问 URL。小程序侧走 uploadFile，域名需在白名单 */
  mUploadImage(tempPath: string): Promise<{ url: string }>;
  /**
   * 拍照识别：**只用来猜一个标题**，猜错不影响，店主可改。
   * ⚠️ 绝不做「一拍就自动上架」—— 识别错了价格也错，货会以错价卖出去。
   */
  mRecognizeGoods(imageUrl: string): Promise<GoodsGuess>;

  // ---- 类目（B-11.3.1）
  /**
   * 三级类目树 —— 编辑商品时选类目用。
   *
   * ⚠️ 与 `Goods["type"]`（五品类）**不是一回事**：type 决定履约与合规，平台硬编码；
   * 类目决定归类与经营准入，运营可维护。挂了资质门槛的类目，
   * 没拿到授权的商家**上架时**会被拒（保存草稿不拦）。
   */
  mCategoryTree(): Promise<Category[]>;

  // ---- 规格模板（B-11.3.2）
  /** 按类目取可用模板：平台预置 + 本商家存的常用 */
  mSpecTemplates(categoryType?: Goods["type"]): Promise<SpecTemplate[]>;
  /** 把当前编辑的规格组存为「我的常用」，下次建品直接套 */
  mSaveSpecTemplate(payload: { name: string; options: string[] }): Promise<SpecTemplate>;

  // ---- 订单与配送（B-11.4）
  /** `status` 用 `OrderStatus` 而不是 `string` —— 松成 string 后，前端传个 "toShip"
   *  这种 tab key 上去也编译得过，而服务端只认状态枚举（由 requests.ts 的 satisfies 抓出）
   *
   *  ⚠️ **配送员拿到的是裁剪档**：只有 `COURIER` 一个角色的人，后端返回
   *  `CourierOrderVO`（`orderNo` / `status` / `fulfillment` / `itemQty` / `createdAt`），
   *  **没有 `amount`、没有 `verifyCode`、没有 `items`**（需求 §4.4：他送的是货不是钱）。
   *  类型这里仍声明为 `Order` —— 收窄成联合类型会让每个用到订单的页面都要分支，
   *  而只有配送页会遇到裁剪档。**用到金额的地方按字段有无渲染**，别按角色判。 */
  /** `status` 与 `fulfillments` 正交，见 c-app 的 orderList 注释 */
  mOrderList(
    q: PageQuery & { status?: OrderStatus; fulfillments?: string[]; allStores?: boolean },
  ): Promise<PageResult<Order>>;
  mOrderDetail(orderNo: string): Promise<Order>;
  /** 快递发货：回填运单号 */
  mShip(orderNo: string, expressNo: string): Promise<Order>;
  /** 商家自送：老板点一下「已送达」。不做骑手轨迹（ADR-005 §5） */
  mDelivered(orderNo: string): Promise<Order>;
  mDeliveryRule(): Promise<DeliveryRule>;
  mSaveDeliveryRule(rule: DeliveryRule): Promise<DeliveryRule>;

  // ---- 自提点履约（B-10）
  /** 本自提点的订单 —— 含别家商家的货，字段已按履约必需裁剪（B12） */
  /** 自提点履约总览（后端已实现）。承接方进履约台第一眼要看的三个数 */
  mPickupOverview(): Promise<PickupOverview>;
  /**
   * 本自提点的待履约单。**返回的是 `PickupOrder`，不是 `Order`** ——
   * 承接方可能替别家收货，字段按履约必需裁到最小（B12），
   * 状态也是子单那一套（`WAIT_FULFILL`…），不是主单的。
   */
  mPickupOrders(): Promise<PickupOrder[]>;
  mPickingList(): Promise<PickingRow[]>;
  /**
   * 到货登记。
   *
   * @param pickupNo 给哪个自提点登记；不传 = 当前门店的那个点。
   *                 多点商家必须能指定 —— 否则另一个点的货永远登记不上
   */
  mMarkArrived(subOrderNos: string[], pickupNo?: string): Promise<PickupOrder[]>;
  /**
   * 核销自提码。核销成功 → C 端该订单立刻变已完成。
   *
   * ⚠️ **失败也是 code 0**，靠返回体的 `success` 判 —— 不要只看有没有抛异常。
   * 码无效、已核销、不是本点这三种都会带 `reason` 回来，
   * 而它们对店主意味着完全不同的下一步。
   */
  mVerify(code: string): Promise<VerifyResult>;
  /**
   * 批量核销（B-6.4 扩展）。高峰期一个个扫码是自提点的真实痛点。
   * **逐条尝试、失败逐条回报**，不整批回滚 —— 一张废码不该让另外四单白扫。
   */
  mVerifyBatch(codes: string[]): Promise<VerifyBatchResult>;
  /**
   * 按取货码**片段**搜单。输码核销的兜底之兜底：
   * 码磨花了、屏幕反光、邻居只记得后四位时，全码输入这条路也走不通。
   *
   * 返回的是 `Order`，端上据此让店主确认是哪一单再核销 ——
   * **不直接核销**：模糊匹配可能命中多单，替他选一单是替他承担风险。
   */
  mVerifySearch(keyword: string): Promise<PickupOrder[]>;

  // ---- 售后（B-11.5）
  /**
   * 待处理售后。**返回售后单，不是订单** —— 后端 /biz/after-sale 给的是
   * List<AfterSaleVO>，端上此前把它类型成 Order[]，形状根本对不上。
   */
  mAfterSaleList(): Promise<AfterSale[]>;
  /** 同意：仅退款直接退；退货退款要等收货后才退（见 mConfirmReturn） */
  mApproveAfterSale(afterSaleNo: string, reply: string): Promise<AfterSale>;
  /** 驳回**必须给理由** —— 用户据此决定要不要上升平台，没理由就是把路堵死 */
  mRejectAfterSale(afterSaleNo: string, reply: string): Promise<AfterSale>;
  /**
   * 确认收到退货 → 随即退款（B-7.3）。
   * **退款必须在这一步之后** —— 同意即退的话，货没回来钱先出去了。
   */
  mConfirmReturn(afterSaleNo: string): Promise<AfterSale>;

  // ---- 团购与报价（B-11.6）
  mGroupList(): Promise<GroupBuy[]>;
  /** 商家在已上架商品上开团。商品未配 groupBuy 则不能开 */
  mCreateGroup(goodsNo: string): Promise<GroupBuy>;
  /** 可报价的邻里需求单 */
  mRequestList(): Promise<GroupRequest[]>;
  /** 报价。改价留痕并公示涨价（ADR-003）；已锁价的不可改 */
  mQuote(
    requestNo: string,
    payload: { priceMinor: number; minCount: number; desc: string },
  ): Promise<Quote>;

  // ---- 评价（B-11.7）
  mReviewList(): Promise<Review[]>;
  mReplyReview(reviewNo: string, reply: string): Promise<Review>;
  /**
   * 申诉差评（B-9.4）。**只有低分评价可申诉** —— 四星五星去申诉没有意义，
   * 开放了只会变成「凡是不满意的评价都申诉一遍」，把平台裁决台淹掉。
   * 裁决在平台端（P-13.1），这里只负责把单送进去。
   */
  mAppealReview(reviewNo: string, reason: string, images?: string[]): Promise<Review>;

  // ---- 营销（B-11.8）
  mCampaignList(): Promise<MarketingCampaign[]>;
  mSaveCampaign(payload: CampaignDraft): Promise<MarketingCampaign>;
  /** 启停。**只允许 RUNNING ↔ PAUSED** —— 已结束的活动不可复活，否则时段与预算全乱 */
  mToggleCampaign(campaignNo: string, running: boolean): Promise<MarketingCampaign>;

  // ---- 客户与复购（B-11.2.8）
  mCustomers(): Promise<MerchantCustomer[]>;

  // ---- 结算（B-11.9）
  /**
   * 结算流水。**一笔子订单一行**，不是周期账单。
   *
   * @param allStores 是否看全部门店。默认（false）只看当前门店 ——
   *                  与订单页同一套惯例；「全部」对老板和店员不是一回事，后端按授权收窄
   */
  mSettleList(allStores?: boolean): Promise<SettleBill[]>;
  /** 费率卡（后端已实现）。把费率讲清楚是「自带客流零佣金」能起作用的前提 */
  mRateCard(): Promise<RateCard>;

  // ---- 到货异常上报（B-10.4.2）
  /** 破损 / 短少上报。下游是售后责任判定（平台 / 供货商家 / 自提点商家，M4 待定） */
  mReportShortage(
    subOrderNo: string,
    payload: { skuNo: string; kind: ArrivalIssueKind; note: string },
  ): Promise<PickupOrder>;

  // ---- 积分（商家侧只有成本与开关，看不到抵扣与补差）
  mPointsAccount(): Promise<MerchantPointAccount>;
  /** 发分服务费明细：一单一条，数据来自 stl_bill.points_fee_minor */
  mPointsRecords(q?: PointsRecordQuery): Promise<MerchantPointsRecord[]>;
  /**
   * 开/关本店积分。
   *
   * **关闭只影响将来** —— 已发出的分仍有效、已扣的服务费不退，
   * 否则关一次开关就是一次资金事故。
   */
  mPointsToggle(req: TogglePointsReq): Promise<MerchantPointAccount>;

  // ---- 消息（新订单/售后/评价的落点；推送与响铃是三期的加速通道，这里是事实记录）
  mMessageList(): Promise<Message[]>;
  /** 未读数。tabBar 红点 30s 轮询用 —— 拉整个列表数未读是把带宽当角标用 */
  mMessageUnread(): Promise<number>;
  mMessageRead(messageNo: string): Promise<Message[]>;
  mMessageReadAll(): Promise<Message[]>;

  // ---- App 推送设备（ADR-018；仅 App 构建有 clientId）
  mRegisterPushToken(platform: string, provider: string, clientId: string): Promise<void>;
  /**
   * 解绑。**登出必须调** —— 门店共用一台手机换班时，
   * 上一班的人不能继续收到这家店的订单推送。
   */
  mUnregisterPushToken(clientId: string): Promise<void>;
}
