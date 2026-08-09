// 统一查询参数类型。所有列表接口的 q 参数只能从这里取，禁止在域文件里就地 `PageQ & {...}`
// —— 否则同一个「按状态筛」在五个域会长出五种写法，且没法一眼看出哪些接口支持数据域收敛。

/** 分页三件套 + 关键词。索引签名保留：http-client 的 qs() 直接序列化整个 q。 */
export interface PageQ { page?: number; size?: number; keyword?: string; [k: string]: unknown; }

/**
 * 数据域收敛参数（矩阵 §2.3）。**列表类 q 都应可选带上它**：
 * 前端带 scope 只是让 mock 与真实后端行为一致、少拉全量；越权拦截以后端为准。
 */
export type ScopedQ = PageQ & { merchantNo?: string; communityNo?: string; pickupNo?: string };

/** 单 status 过滤。 */
export type StatusQ = ScopedQ & { status?: string };

/**
 * G1 软删除：可归档主数据的列表参数。`showArchived` 打开才把已归档行带出来，
 * 默认（不传 / false）一律过滤掉 ——「归档了还在列表里」是软删除最常见的漏实现。
 */
export type ArchiveQ = ScopedQ & { showArchived?: boolean };

// ——— 域特有形状 ———

/**
 * 入驻申请：状态 + 关键词（店名/联系人/手机号）。
 *
 * **没有归档开关** —— 申请单不归档，它是历史事实；已处理的靠 status 翻。
 */
export type ApplyQ = { status?: string; keyword?: string; page?: number; size?: number };

/** 商家：审核状态 + 分层 + 归档开关（P-11.1）。 */
export type MerchantQ = ArchiveQ & { status?: string; tier?: string };

/** 订单：状态 + 履约方式 + 流量来源（P-4.1；trafficSource 供 P-16.1.6 结构分析）。 */
export type OrderQ = ScopedQ & { status?: string; fulfillType?: string; trafficSource?: string };

/** 社区：城市 + 开城状态 + 归档开关（P-2.1）。 */
export type CommunityQ = ArchiveQ & { city?: string; opened?: string };

/** 自提点：类型（STORE/NEIGHBOR）+ 状态 + 归档开关（P-2.2）。 */
export type PickupQ = ArchiveQ & { type?: string; status?: string };

/** 到货批次：状态 + 自提点（P-5.1.1）。 */
export type BatchQ = ScopedQ & { status?: string };

/** 门店主页审核：类型（店招/公告）+ 审核状态（P-10.1.2）。 */
export type StoreAuditQ = PageQ & { kind?: string; status?: string };

/** 券模板：类型 + 状态 + 归档开关（P-7.1）。 */
export type CouponQ = ArchiveQ & { type?: string; status?: string };

/** 活动：类型 + 状态（P-7.2）。 */
export type CampaignQ = ArchiveQ & { type?: string; status?: string };

/** 内容位：位置类型 + 启用状态（P-7.3）。 */
export type SlotQ = ArchiveQ & { kind?: string; enabled?: string };

/** 评价：状态 + 是否只看命中刷评信号的（P-13.1）。 */
export type ReviewQ = PageQ & { status?: string; risky?: string; merchantNo?: string };

/** 售后：类型 + 状态 + 是否只看平台介入队列（P-6.1）。 */
export type AfterSaleQ = ScopedQ & { type?: string; status?: string; intervene?: string };

/** 商家团：状态筛选（P-8.1）。 */
export type GroupQ = PageQ & { status?: string };

/** 求团需求：状态 + 社区（P-8.2）。 */
export type DemandQ = ScopedQ & { status?: string };

/** 类目：模板 + 归档开关（P-3.1）。 */
export type CategoryQ = ArchiveQ & { template?: string };

/** 商品：状态 + 类目 + 商家（P-3.2）。 */
export type SkuQ = ScopedQ & { status?: string; categoryNo?: string };

/** 结算单：状态 + 周期（P-12.1）。 */
export type SettlementQ = ScopedQ & { status?: string; period?: string };

/** 员工：角色 + 启用状态（P-1.1.1）。 */
export type StaffQ = PageQ & { role?: string; enabled?: string };

/** 审计日志：是否只看高危（P-1.1.4）。 */
export type AuditQ = PageQ & { critical?: string };

/** 归因链路：来源 + 是否只看冲突/风险（P-9.1.3）。 */
export type TraceQ = PageQ & { source?: string; conflictOnly?: string; riskyOnly?: string };

/** 风险事件：类型 + 处置状态（P-16.2）。 */
export type RiskQ = PageQ & { type?: string; status?: string };

/** 黑名单：主体类型 + 是否只看生效中。 */
export type BlacklistQ = PageQ & { subjectType?: string; activeOnly?: string };

/** 客服工单：状态 + 处理人（P-14.2.1）。 */
export type TicketQ = PageQ & { status?: string; assignee?: string };

/** 素材：类型 + 可见范围 + 上架状态（P-15.1）。 */
export type MaterialQ = PageQ & { kind?: string; scope?: string; published?: string };
