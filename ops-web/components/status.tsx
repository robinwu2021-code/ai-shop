"use client";

// 业务件：域内**固定枚举**的徽标。判据是它认得 ai-shop 的业务词（订单状态机、审核状态），
// 所以放 components/ 根而不是 components/ui/ —— ui/ 里不许出现业务语义。
//
// 只有「全站通用、映射固定」的枚举放这里；某一页专有的状态映射表留在那一页
// （见 components/README.md「状态映射表放哪」）。
import type {
  MerchantStatus, MerchantTier, OrderStatus, FulfillmentType, TrafficSource,
  PickupPointType, PickupStatus, BatchStatus, StoreAuditStatus,
  CouponType, CouponStatus, CampaignType, CampaignStatus, SlotKind,
  ReviewStatus, AppealStatus, RiskFlag,
  AfterSaleType, AfterSaleStatus, GroupStatus, DemandStatus,
  CategoryTemplate, SkuStatus, SettleStatus, AttrSource, RiskType, RiskStatus, BlacklistAppealStatus,
  PushStatus, TicketStatus, MaterialKind, MaterialScope,
} from "@/lib/types";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Badge } from "@/components/ui/badge";
import { useI18n } from "@/lib/i18n";

/**
 * ⚠️ **映射表的键序 = 筛选下拉的选项顺序**（statusOptions 按键序派生）。
 * 这里刻意按状态机推进顺序排，改键序等于改 UI，别随手按字母排序。
 */
export function useMerchantStatusMap(): StatusMap<MerchantStatus> {
  const { t } = useI18n();
  return {
    ACTIVE: { label: t("merchantStatus.ACTIVE"), tone: "success" },
    SUSPENDED: { label: t("merchantStatus.SUSPENDED"), tone: "danger" },
    FROZEN: { label: t("merchantStatus.FROZEN"), tone: "warning" },
  };
}

export function MerchantStatusBadge({ value }: { value: MerchantStatus }) {
  return <StatusBadge map={useMerchantStatusMap()} value={value} />;
}

export function useOrderStatusMap(): StatusMap<OrderStatus> {
  const { t } = useI18n();
  return {
    WAIT_PAY: { label: t("orderStatus.WAIT_PAY"), tone: "warning" },
    PAID: { label: t("orderStatus.PAID"), tone: "info" },
    SHIPPED: { label: t("orderStatus.SHIPPED"), tone: "info" },
    ARRIVED: { label: t("orderStatus.ARRIVED"), tone: "warning" },
    COMPLETED: { label: t("orderStatus.COMPLETED"), tone: "success" },
    CANCELLED: { label: t("orderStatus.CANCELLED"), tone: "muted" },
    REFUNDED: { label: t("orderStatus.REFUNDED"), tone: "danger" },
  };
}

export function OrderStatusBadge({ value }: { value: OrderStatus }) {
  return <StatusBadge map={useOrderStatusMap()} value={value} />;
}

/** 履约方式：不是状态而是分类，用中性徽标，不参与"好/坏"的颜色语义。 */
export function useFulfillmentTypeMap(): StatusMap<FulfillmentType> {
  const { t } = useI18n();
  return {
    STORE_PICKUP: { label: t("fulfillmentType.STORE_PICKUP"), tone: "muted" },
    NEIGHBOR_PICKUP: { label: t("fulfillmentType.NEIGHBOR_PICKUP"), tone: "muted" },
    MERCHANT_DELIVERY: { label: t("fulfillmentType.MERCHANT_DELIVERY"), tone: "muted" },
    EXPRESS: { label: t("fulfillmentType.EXPRESS"), tone: "muted" },
    STORE_VERIFY: { label: t("fulfillmentType.STORE_VERIFY"), tone: "muted" },
  };
}

/**
 * 流量来源（矩阵 P-12.1.7 分档计费的依据）。
 * MERCHANT_OWNED 用成功色**不是**因为它"更好"，而是它对应零/低佣金档，
 * 运营需要在列表里一眼扫出这批单 —— 颜色在这里表达的是"注意这批"。
 */
export function useTrafficSourceMap(): StatusMap<TrafficSource> {
  const { t } = useI18n();
  return {
    MERCHANT_OWNED: { label: t("trafficSource.MERCHANT_OWNED"), tone: "success" },
    PLATFORM: { label: t("trafficSource.PLATFORM"), tone: "info" },
    INVITE: { label: t("trafficSource.INVITE"), tone: "muted" },
    CHANNEL: { label: t("trafficSource.CHANNEL"), tone: "muted" },
  };
}

/**
 * 商家分层的展示名。
 *
 * <p>分层（P-11.1.6）是**预留字段，后端目前一条都不写** —— 拿不到值时给一个短横，
 * 而不是把 `merchantTier.null` 这样的 i18n 键原样打到表格里。
 * 键名漏到界面上，看起来像系统坏了，实际只是这个字段还没启用。
 */
export function useMerchantTierLabel(): (tier?: MerchantTier | null) => string {
  const { t } = useI18n();
  return (tier) => (tier ? t(`merchantTier.${tier}`) : "-");
}

/** 认证标（P-11.1.2）。未认证不出徽标 —— 满屏"未认证"是噪音，认证才是信息。 */
export function VerifiedBadge({ verified }: { verified: boolean }) {
  const { t } = useI18n();
  if (!verified) return null;
  return <Badge tone="info">{t("status.verified")}</Badge>;
}

/**
 * 自提点类型（ADR-005）。两类点的规则完全不同，列表里必须一眼分得出来：
 * STORE 收服务费、承接全部订单；NEIGHBOR 零报酬、只服务单个团。
 */
export function usePickupPointTypeMap(): StatusMap<PickupPointType> {
  const { t } = useI18n();
  return {
    STORE: { label: t("pickupPointType.STORE"), tone: "info" },
    PLATFORM: { label: t("pickupPointType.PLATFORM"), tone: "warning" },
    NEIGHBOR: { label: t("pickupPointType.NEIGHBOR"), tone: "outline" },
  };
}
export function PickupPointTypeBadge({ value }: { value: PickupPointType }) {
  return <StatusBadge map={usePickupPointTypeMap()} value={value} />;
}

export function usePickupStatusMap(): StatusMap<PickupStatus> {
  const { t } = useI18n();
  return {
    ACTIVE: { label: t("pickupStatus.ACTIVE"), tone: "success" },
    MIGRATING: { label: t("pickupStatus.MIGRATING"), tone: "warning" },
    SUSPENDED: { label: t("pickupStatus.SUSPENDED"), tone: "muted" },
  };
}
export function PickupStatusBadge({ value }: { value: PickupStatus }) {
  return <StatusBadge map={usePickupStatusMap()} value={value} />;
}

/** 到货批次：键序 = 推进顺序（计划→发车→到货→签收），不是字母序。 */
export function useBatchStatusMap(): StatusMap<BatchStatus> {
  const { t } = useI18n();
  return {
    PLANNED: { label: t("batchStatus.PLANNED"), tone: "muted" },
    DISPATCHED: { label: t("batchStatus.DISPATCHED"), tone: "info" },
    ARRIVED: { label: t("batchStatus.ARRIVED"), tone: "warning" },
    SIGNED: { label: t("batchStatus.SIGNED"), tone: "success" },
  };
}
export function BatchStatusBadge({ value }: { value: BatchStatus }) {
  return <StatusBadge map={useBatchStatusMap()} value={value} />;
}

export function useStoreAuditStatusMap(): StatusMap<StoreAuditStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("storeAuditStatus.PENDING"), tone: "warning" },
    PASSED: { label: t("storeAuditStatus.PASSED"), tone: "success" },
    REJECTED: { label: t("storeAuditStatus.REJECTED"), tone: "danger" },
  };
}
export function StoreAuditStatusBadge({ value }: { value: StoreAuditStatus }) {
  return <StatusBadge map={useStoreAuditStatusMap()} value={value} />;
}

// ── 营销（P-7）────────────────────────────────────────────────────────────
export function useCouponTypeMap(): StatusMap<CouponType> {
  const { t } = useI18n();
  return {
    FULL_CUT: { label: t("couponType.FULL_CUT"), tone: "muted" },
    DISCOUNT: { label: t("couponType.DISCOUNT"), tone: "muted" },
    NEWCOMER: { label: t("couponType.NEWCOMER"), tone: "info" },
    TARGETED: { label: t("couponType.TARGETED"), tone: "muted" },
  };
}

/** 键序 = 状态机推进顺序。 */
export function useCouponStatusMap(): StatusMap<CouponStatus> {
  const { t } = useI18n();
  return {
    DRAFT: { label: t("couponStatus.DRAFT"), tone: "muted" },
    ACTIVE: { label: t("couponStatus.ACTIVE"), tone: "success" },
    PAUSED: { label: t("couponStatus.PAUSED"), tone: "warning" },
    ENDED: { label: t("couponStatus.ENDED"), tone: "muted" },
  };
}
export function CouponStatusBadge({ value }: { value: CouponStatus }) {
  return <StatusBadge map={useCouponStatusMap()} value={value} />;
}

export function useCampaignTypeMap(): StatusMap<CampaignType> {
  const { t } = useI18n();
  return {
    SECKILL: { label: t("campaignType.SECKILL"), tone: "danger" },
    FLASH: { label: t("campaignType.FLASH"), tone: "warning" },
    FULL_REDUCE: { label: t("campaignType.FULL_REDUCE"), tone: "muted" },
    GIFT: { label: t("campaignType.GIFT"), tone: "muted" },
    NEWCOMER: { label: t("campaignType.NEWCOMER"), tone: "info" },
  };
}

export function useCampaignStatusMap(): StatusMap<CampaignStatus> {
  const { t } = useI18n();
  return {
    DRAFT: { label: t("campaignStatus.DRAFT"), tone: "muted" },
    SCHEDULED: { label: t("campaignStatus.SCHEDULED"), tone: "info" },
    RUNNING: { label: t("campaignStatus.RUNNING"), tone: "success" },
    ENDED: { label: t("campaignStatus.ENDED"), tone: "muted" },
  };
}
export function CampaignStatusBadge({ value }: { value: CampaignStatus }) {
  return <StatusBadge map={useCampaignStatusMap()} value={value} />;
}

export function useSlotKindMap(): StatusMap<SlotKind> {
  const { t } = useI18n();
  return {
    HOME_FLOOR: { label: t("slotKind.HOME_FLOOR"), tone: "muted" },
    BANNER: { label: t("slotKind.BANNER"), tone: "muted" },
    CHANNEL: { label: t("slotKind.CHANNEL"), tone: "muted" },
  };
}

// ── 评价（P-13）───────────────────────────────────────────────────────────
export function useReviewStatusMap(): StatusMap<ReviewStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("reviewStatus.PENDING"), tone: "warning" },
    PASSED: { label: t("reviewStatus.PASSED"), tone: "success" },
    REJECTED: { label: t("reviewStatus.REJECTED"), tone: "danger" },
  };
}
export function ReviewStatusBadge({ value }: { value: ReviewStatus }) {
  return <StatusBadge map={useReviewStatusMap()} value={value} />;
}

export function useAppealStatusMap(): StatusMap<AppealStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("appealStatus.PENDING"), tone: "warning" },
    UPHELD: { label: t("appealStatus.UPHELD"), tone: "info" },
    REJECTED: { label: t("appealStatus.REJECTED"), tone: "muted" },
  };
}
export function AppealStatusBadge({ value }: { value: AppealStatus }) {
  return <StatusBadge map={useAppealStatusMap()} value={value} />;
}

/**
 * 刷评信号（P-13.1.5）。**统一用 warning 而不是 danger**：
 * 它是给人审的线索不是判定结论，用红色会诱导审核员直接当成违规处理。
 */
export function RiskFlagBadges({ flags }: { flags: RiskFlag[] }) {
  const { t } = useI18n();
  if (flags.length === 0) return <span className="text-muted-foreground">{t("status.none")}</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {flags.map((f) => <Badge key={f} tone="warning">{t(`riskFlag.${f}`)}</Badge>)}
    </span>
  );
}

// ── 售后（P-6）────────────────────────────────────────────────────────────
export function useAfterSaleTypeMap(): StatusMap<AfterSaleType> {
  const { t } = useI18n();
  return {
    REFUND_ONLY: { label: t("afterSaleType.REFUND_ONLY"), tone: "muted" },
    RETURN_REFUND: { label: t("afterSaleType.RETURN_REFUND"), tone: "muted" },
    EXCHANGE: { label: t("afterSaleType.EXCHANGE"), tone: "muted" },
  };
}

/**
 * 键序 = 处理流程顺序。ARBITRATING 用 danger **不是因为它更糟**，
 * 而是它意味着「用户和商家谈崩了、有人在等平台表态」——列表里必须最先被看到。
 */
export function useAfterSaleStatusMap(): StatusMap<AfterSaleStatus> {
  const { t } = useI18n();
  return {
    APPLIED: { label: t("afterSaleStatus.APPLIED"), tone: "warning" },
    REFUNDING: { label: t("afterSaleStatus.REFUNDING"), tone: "info" },
    ARBITRATING: { label: t("afterSaleStatus.ARBITRATING"), tone: "danger" },
    REJECTED: { label: t("afterSaleStatus.REJECTED"), tone: "muted" },
    REFUNDED: { label: t("afterSaleStatus.REFUNDED"), tone: "success" },
    CLOSED: { label: t("afterSaleStatus.CLOSED"), tone: "muted" },
  };
}
export function AfterSaleStatusBadge({ value }: { value: AfterSaleStatus }) {
  return <StatusBadge map={useAfterSaleStatusMap()} value={value} />;
}

// ── 团购与求团（P-8）──────────────────────────────────────────────────────
export function useGroupStatusMap(): StatusMap<GroupStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("groupStatus.PENDING"), tone: "warning" },
    RUNNING: { label: t("groupStatus.RUNNING"), tone: "info" },
    SUCCESS: { label: t("groupStatus.SUCCESS"), tone: "success" },
    FAILED: { label: t("groupStatus.FAILED"), tone: "muted" },
  };
}
export function GroupStatusBadge({ value }: { value: GroupStatus }) {
  return <StatusBadge map={useGroupStatusMap()} value={value} />;
}

export function useDemandStatusMap(): StatusMap<DemandStatus> {
  const { t } = useI18n();
  return {
    OPEN: { label: t("demandStatus.OPEN"), tone: "warning" },
    QUOTING: { label: t("demandStatus.QUOTING"), tone: "info" },
    CHOSEN: { label: t("demandStatus.CHOSEN"), tone: "success" },
    CLOSED: { label: t("demandStatus.CLOSED"), tone: "muted" },
  };
}
export function DemandStatusBadge({ value }: { value: DemandStatus }) {
  return <StatusBadge map={useDemandStatusMap()} value={value} />;
}

// ── 商品与类目（P-3）──────────────────────────────────────────────────────
/** 品类属性模板：分类而非状态，全用中性色（给它上色会读成"某个品类更重要"）。 */
export function useCategoryTemplateMap(): StatusMap<CategoryTemplate> {
  const { t } = useI18n();
  return {
    STANDARD: { label: t("categoryTemplate.STANDARD"), tone: "muted" },
    FRESH: { label: t("categoryTemplate.FRESH"), tone: "muted" },
    SERVICE: { label: t("categoryTemplate.SERVICE"), tone: "muted" },
    VIRTUAL: { label: t("categoryTemplate.VIRTUAL"), tone: "muted" },
    VOUCHER: { label: t("categoryTemplate.VOUCHER"), tone: "muted" },
  };
}

/** 键序 = 上架流程顺序。REJECTED 用 danger：它是**需要商家动手**的状态，不是终点。 */
export function useSkuStatusMap(): StatusMap<SkuStatus> {
  const { t } = useI18n();
  return {
    DRAFT: { label: t("skuStatus.DRAFT"), tone: "muted" },
    PENDING: { label: t("skuStatus.PENDING"), tone: "warning" },
    ON_SALE: { label: t("skuStatus.ON_SALE"), tone: "success" },
    OFF_SALE: { label: t("skuStatus.OFF_SALE"), tone: "muted" },
    REJECTED: { label: t("skuStatus.REJECTED"), tone: "danger" },
  };
}
export function SkuStatusBadge({ value }: { value: SkuStatus }) {
  return <StatusBadge map={useSkuStatusMap()} value={value} />;
}

// ── 结算与资金（P-12）────────────────────────────────────────────────────
/**
 * 键序 = 分账流程顺序。
 * FROZEN_BACK（解冻回平台）用 muted 而不是 danger：它是**兜底规则正常生效**的结果，
 * 不是事故；真正需要人处理的是 FAILED。
 */
export function useSettleStatusMap(): StatusMap<SettleStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("settleStatus.PENDING"), tone: "warning" },
    SPLITTING: { label: t("settleStatus.SPLITTING"), tone: "info" },
    SPLIT: { label: t("settleStatus.SPLIT"), tone: "success" },
    FAILED: { label: t("settleStatus.FAILED"), tone: "danger" },
    FROZEN_BACK: { label: t("settleStatus.FROZEN_BACK"), tone: "muted" },
  };
}
export function SettleStatusBadge({ value }: { value: SettleStatus }) {
  return <StatusBadge map={useSettleStatusMap()} value={value} />;
}

// ── 增长与归因（P-9）/ 风控（P-16.2）────────────────────────────────────
/** 归因来源。键序 = 矩阵 P-9.1.1 的默认优先级（店铺码 > 邀请人 > 渠道）。 */
export function useAttrSourceMap(): StatusMap<AttrSource> {
  const { t } = useI18n();
  return {
    STORE_CODE: { label: t("attrSource.STORE_CODE"), tone: "success" },
    INVITER: { label: t("attrSource.INVITER"), tone: "info" },
    CHANNEL: { label: t("attrSource.CHANNEL"), tone: "muted" },
  };
}

export function useRiskTypeMap(): StatusMap<RiskType> {
  const { t } = useI18n();
  return {
    FAKE_ORDER: { label: t("riskType.FAKE_ORDER"), tone: "danger" },
    ABNORMAL_FISSION: { label: t("riskType.ABNORMAL_FISSION"), tone: "danger" },
    MALICIOUS_REFUND: { label: t("riskType.MALICIOUS_REFUND"), tone: "danger" },
  };
}

/** PENDING 用 warning：它表示"还没人看"，不是"已确认有问题"。 */
export function useRiskStatusMap(): StatusMap<RiskStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("riskStatus.PENDING"), tone: "warning" },
    CONFIRMED: { label: t("riskStatus.CONFIRMED"), tone: "danger" },
    DISMISSED: { label: t("riskStatus.DISMISSED"), tone: "muted" },
  };
}
export function RiskStatusBadge({ value }: { value: RiskStatus }) {
  return <StatusBadge map={useRiskStatusMap()} value={value} />;
}

export function useBlacklistAppealMap(): StatusMap<BlacklistAppealStatus> {
  const { t } = useI18n();
  return {
    NONE: { label: t("blacklistAppeal.NONE"), tone: "muted" },
    PENDING: { label: t("blacklistAppeal.PENDING"), tone: "warning" },
    UPHELD: { label: t("blacklistAppeal.UPHELD"), tone: "success" },
    REJECTED: { label: t("blacklistAppeal.REJECTED"), tone: "muted" },
  };
}

// ── 消息与客服（P-14）/ 素材（P-15）──────────────────────────────────────
export function usePushStatusMap(): StatusMap<PushStatus> {
  const { t } = useI18n();
  return {
    DRAFT: { label: t("pushStatus.DRAFT"), tone: "muted" },
    SCHEDULED: { label: t("pushStatus.SCHEDULED"), tone: "info" },
    SENT: { label: t("pushStatus.SENT"), tone: "success" },
    CANCELLED: { label: t("pushStatus.CANCELLED"), tone: "muted" },
  };
}

/** PENDING 用 warning：未分派的工单是"有人在等"，不是中性状态。 */
export function useTicketStatusMap(): StatusMap<TicketStatus> {
  const { t } = useI18n();
  return {
    PENDING: { label: t("ticketStatus.PENDING"), tone: "warning" },
    ASSIGNED: { label: t("ticketStatus.ASSIGNED"), tone: "info" },
    RESOLVED: { label: t("ticketStatus.RESOLVED"), tone: "success" },
    CLOSED: { label: t("ticketStatus.CLOSED"), tone: "muted" },
  };
}
export function TicketStatusBadge({ value }: { value: TicketStatus }) {
  return <StatusBadge map={useTicketStatusMap()} value={value} />;
}

export function useMaterialKindMap(): StatusMap<MaterialKind> {
  const { t } = useI18n();
  return {
    COPY: { label: t("materialKind.COPY"), tone: "muted" },
    IMAGE: { label: t("materialKind.IMAGE"), tone: "muted" },
    POSTER: { label: t("materialKind.POSTER"), tone: "muted" },
    VIDEO: { label: t("materialKind.VIDEO"), tone: "muted" },
  };
}

export function useMaterialScopeMap(): StatusMap<MaterialScope> {
  const { t } = useI18n();
  return {
    ALL: { label: t("materialScope.ALL"), tone: "success" },
    COMMUNITY: { label: t("materialScope.COMMUNITY"), tone: "info" },
    MERCHANT: { label: t("materialScope.MERCHANT"), tone: "info" },
  };
}
