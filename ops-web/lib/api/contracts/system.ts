// 覆盖范围：系统配置（P-17.1）。
import type {
  AppearanceConfig, AuthCodeAdmin, FeatureFlag, Industry, MarketConfig, RuleTexts, ServiceScopeConfig,
  MediaBackfillResult, MediaBatchDetail, MediaOverview, MediaPurgeBatch, MediaPurgePreview,
  MediaReclaimable, MediaReclaimableQuery, MediaScanResult, MediaStoreUsage, Page,
} from "@/lib/types";

export interface SystemApi {
  // ── 授权码字典（TDD-一期主数据收敛 阶段二）—— **已接真后端** `/ops/auth-codes/**`

  /**
   * <b>全量，含停用</b>，带商家数与类目引用数。
   *
   * 与 `merchantApi.listAuthCodeDict()`（发证时的可选项，只给启用的）是两个口径，
   * 不能合并：停用过的码在那边看不见，也就再也恢复不了。
   */
  listAuthCodeDict(): Promise<AuthCodeAdmin[]>;
  /** 新建或更新。`code` 建成之后不可改 —— 改它等于换一张证 */
  saveAuthCodeDict(v: Pick<AuthCodeAdmin, "code" | "name" | "requiredQualification" | "sort">): Promise<AuthCodeAdmin>;
  /**
   * 启停。**停用不撤销存量商家已持有的授权**，只是不再发放。
   *
   * @param reason 必填 —— 它决定一批商家还能不能上新品
   */
  setAuthCodeDictEnabled(code: string, enabled: boolean, reason: string): Promise<AuthCodeAdmin>;

  // ── 经营范围开关（ADR-009）—— **已接真后端** `/ops/service-scopes/**`

  listServiceScopes(): Promise<ServiceScopeConfig[]>;
  /** 开关某一档，返回最新的三档全量。**至少要留一档** —— 全关等于所有商家保存不了门店 */
  setServiceScopeEnabled(scope: string, enabled: boolean, reason: string): Promise<ServiceScopeConfig[]>;

  // ── 行业主数据（P-17.1 / ADR-010）—— **已接真后端** `/ops/industries/**`

  listIndustries(): Promise<Industry[]>;
  /**
   * 改某通道的小微白名单。
   *
   * @param remark 为什么改。**建议必填** —— 改白名单会被商家追问，
   *   而「谁什么时候为什么改的」只有这里记得住
   */
  setIndustryMicroAllowed(industry: string, payChannel: string, allowed: boolean, remark?: string): Promise<Industry>;
  /** 停用后入驻表单里不再出现这个行业；**不影响已入驻的商家** */
  setIndustryEnabled(industry: string, enabled: boolean): Promise<Industry>;
  /** 强制开启积分：商家不可自行关闭 */
  setIndustryPointsForced(industry: string, forced: boolean): Promise<Industry>;

  getAppearance(): Promise<AppearanceConfig>;
  /** 皮肤下发（P-17.1.1 / C-TH-05）。取值必须是四套皮肤之一。 */
  saveAppearance(v: Pick<AppearanceConfig, "defaultSkin" | "festivalSkin" | "festivalFrom" | "festivalTo" | "fallbackLang">): Promise<AppearanceConfig>;

  listMarkets(): Promise<MarketConfig[]>;
  /** 市场与汇率（P-17.1.3）。汇率 > 0；**基准货币不可改**。 */
  saveMarketRate(code: string, rate: number, enabled: boolean): Promise<MarketConfig>;

  getRuleTexts(): Promise<RuleTexts>;
  /** 规则文案（P-17.1.4）。三条都不能为空 —— C 端要展示给用户看。 */
  saveRuleTexts(v: Pick<RuleTexts, "refund" | "pickup" | "weighDiff">): Promise<RuleTexts>;

  listFeatureFlags(): Promise<FeatureFlag[]>;
  /** 开关与灰度（P-17.1.5）。灰度比例 0–100。 */
  saveFeatureFlag(key: string, enabled: boolean, rolloutPercent: number): Promise<FeatureFlag>;

  // ── 存储空间治理（TDD-图片存储与空间回收）── **已接真后端** `/ops/media/**`

  getMediaOverview(): Promise<MediaOverview>;
  /** 门店占用。后端已按「待回收」倒序 —— 这一页的目的就是找出最该清的店 */
  listMediaStoreUsage(): Promise<MediaStoreUsage[]>;
  listMediaReclaimable(q?: MediaReclaimableQuery): Promise<Page<MediaReclaimable>>;
  listMediaBatches(): Promise<MediaPurgeBatch[]>;
  getMediaBatch(batchNo: string): Promise<MediaBatchDetail>;

  /** 重扫。**只读** —— 一个文件都不删，只重算「谁还被引用着」 */
  scanMedia(): Promise<MediaScanResult>;
  /** 磁盘对账：把「磁盘上有、库里没有」的文件补录进来。幂等 */
  backfillMedia(): Promise<MediaBackfillResult>;
  /** 预览这一票有多少张、多少字节。确认弹窗里显示的就是它 */
  previewMediaPurge(q: MediaReclaimableQuery): Promise<MediaPurgePreview>;
  /**
   * 提交回收。**不可逆**。
   *
   * 两种入参二选一：勾选的 `assetKeys`，或跨页全选的 `筛选 + expectedCount`。
   * **后者必须带数量**，服务端比对不一致就整批拒绝 —— 从看到清单到点确认之间，
   * 扫描可能刚好把几张救回去了，不比对就会删掉运营没看过的那几张。
   */
  purgeMedia(v: MediaReclaimableQuery & { assetKeys?: string[]; expectedCount?: number }):
    Promise<{ batchNo: string }>;
}