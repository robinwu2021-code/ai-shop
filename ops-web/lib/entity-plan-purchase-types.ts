/** Entity quota contracts, intentionally distinct from store mall entitlements. */
export type PlanMoney = {
  /** 资金币种代码；所有币种均以两位小数的最小金额单位计量。 */
  currency:string;
  /** 最小金额单位整数，不使用浮点元金额。 */
  minor:number;
};
export type EntityPlanOffer={
  /** 主体套餐销售报价编号。 */
  offerNo:string;
  /** 报价版本；下单必须绑定所选版本。 */
  version:number;
  /** 报价对应的主体套餐档位代码。 */
  planCode:string;
  /** 展示名称。 */
  name:string;
  /** 本次购买增加的套餐有效天数。 */
  days:number;
  /** 该版本冻结的销售价格，不代表银行实收。 */
  price:PlanMoney;
  /** 该版本冻结的主体门店额度。 */
  storeQuota:number;
  /** 该版本冻结的主体员工额度。 */
  staffQuota:number;
  /** 是否包含跨门店统计能力。 */
  crossStoreStats:boolean;
  /** 是否允许创建新的购买订单；不修改既有订单快照。 */
  enabled:boolean;
};
export type EntityPlanOfferEdit={
  /** 要新增或更新的销售报价编号。 */
  offerNo:string;
  /** 期望现有版本；新增报价传 0，防止覆盖并发修改。 */
  expectedVersion:number;
  /** 主体套餐档位代码。 */
  planCode:string;
  /** 展示名称。 */
  name:string;
  /** 购买有效天数。 */
  days:number;
  /** 新版本销售价格。 */
  price:PlanMoney;
  /** 是否对后续新购买开放销售。 */
  enabled:boolean;
};
export type EntityPlanClaim={
  /** 商家付款申报幂等号；不作为到账事实。 */
  claimNo:string;
  /** 商家申报的实际付款金额。 */
  amount:PlanMoney;
  /** 商家申报的实际付款时间，ISO 8601 UTC。 */
  paidAt:string;
  /** 商家申报的银行流水参考号。 */
  bankReference:string;
  /** 该订单范围内上传的商家付款凭证资产键。 */
  evidenceKey:string;
};
export type EntityPlanConfirm=Omit<EntityPlanClaim,"claimNo">&{
  /** 运营真实核款动作幂等号；未知结果必须查询并沿用原号。 */
  actionNo:string;
  /** 关联商家申报号；运营直接代购核款时为 null。 */
  claimNo:string|null;
  /** 审核与银行核对理由，留作审计。 */
  reason:string;
};
export type EntityPlanVoid={
  /** 对账关闭动作幂等号；恢复不创建新动作。 */
  actionNo:string;
  /** 独立银行对账参考号。 */
  reference:string;
  /** 本订单 RECONCILIATION 用途的私有对账凭证资产键。 */
  evidenceKey:string;
  /** 关闭并解除原订单占用的审计理由。 */
  reason:string;
};
export type EntityPlanAction={
  /** 原运营核款或对账关闭动作号。 */
  actionNo:string;
  /** 资金动作状态；ACCEPTED 仅为受理，APPLIED/REJECTED 为已核验结果。 */
  status:string;
  /** 资金底座记录的真实支付号；尚无实收时为 null。 */
  paymentNo:string|null;
  /** 原审核理由或资金核验拒绝原因。 */
  reason:string;
};
export type EntityPlanPurchase={
  /** 主体套餐购买订单号。 */
  orderNo:string;
  /** 创建订单的原始幂等请求号。 */
  requestNo:string;
  /** 订单所属商家主体编号。 */
  entityNo:string;
  /** 下单时服务器验证并冻结的门店编号。 */
  storeNo:string;
  /** 下单时冻结的销售报价及额度快照。 */
  offer:EntityPlanOffer;
  /** 购买业务状态；仅 APPLIED 表示权益开通，REFUNDED 表示异常款已核实退回。 */
  status:string;
  /** 创建来源 MERCHANT/OPERATOR；运营代购仍需真实银行核款。 */
  purchaseSource?:string;
  /** 本订单银行付款截止时间，ISO 8601。 */
  dueAt:string;
  /** 冻结收款指引；尚未准备或未配置真实收款账户时为 null。 */
  instructions:null|{
    /** 指引绑定的平台收款商户号。 */
    platformMerchantNo:string;
    /** 服务器下发的银行收款指引字段。 */
    instructions:Record<string,string>;
  };
  /** 商家付款申报与审核记录，不等于真实到账。 */
  claims:{
    /** 原商家付款申报号。 */
    claimNo:string;
    /** 申报状态；SUBMITTED 为待核款。 */
    status:string;
    /** 商家冻结的原始申报内容。 */
    declaration:EntityPlanClaim;
    /** 审核说明；未审核时为 null。 */
    reviewNote:string|null;
  }[];
  /** 可按动作号核对及恢复的运营动作记录。 */
  actions:EntityPlanAction[];
};
export type EntityAssistedRequest={
  /** 本次代购幂等请求号；结果未知时冻结并重用。 */
  requestNo:string;
  /** 所选在售主体套餐报价编号。 */
  offerNo:string;
  /** 所选报价的精确版本，由服务器再次校验。 */
  offerVersion:number;
  /** 从服务器授权门店集合选择的门店编号。 */
  storeNo:string;
  /** 从服务器返回的主体责任账号集合选择的原账号。 */
  ownerAccountNo:string;
};
export type EntityAssistanceContext={
  /** 可用于本主体代购的服务器授权责任账号集合。 */
  owners:{
    /** 责任账号编号；不能从旧订单推测。 */
    accountNo:string;
    /** 账号展示名称。 */
    displayName:string;
    /** 账号对应的主体身份种类。 */
    subjectKind:string;
  }[];
  /** 可用于本主体代购的服务器授权门店集合。 */
  stores:{
    /** 门店编号。 */
    storeNo:string;
    /** 门店展示名称。 */
    name:string;
  }[];
};
export type EntityPlanEvidenceTicket={
  /** 绑定主体、门店、订单与凭证用途的私有资产键；上传接口仍须验证归属。 */
  assetKey:string;
};
export type EntityPlanSignedRead={
  /** 授权后签发的短时私有凭证读取地址。 */
  url:string;
};
export type {EntityPlanPurchaseApi} from "./api/contracts/entity-plan-purchase";
