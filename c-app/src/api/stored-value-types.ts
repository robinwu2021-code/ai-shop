import type { CurrencyCode } from "@shared/types";
export type StoredValueType = "ALL" | "RECHARGE" | "REDEMPTION";
export interface StoredValueStore {
  /** 门店编号 */
  storeNo: string;
  /** 门店显示名称 */
  storeName: string;
  /** 最近业务发生时间 */
  lastOccurredAt: string;
  /** 最近一笔记录类型 */
  lastType: Exclude<StoredValueType, "ALL">;
}
export interface StoredValueStoresQuery {
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?: string;
  /** 请求的每页记录数量 */
  size?: number }
export interface StoredValueStoresPage {
  /** 当前页业务记录列表 */
  records: StoredValueStore[];
  /** 下一页游标，无后续记录时为空 */
  nextCursor: string | null;
  /** 是否还有后续分页 */
  hasMore: boolean;
}
export interface StoredValueRecordsQuery extends StoredValueStoresQuery {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type?: StoredValueType;
  /** 筛选开始日期 */
  startDate?: string;
  /** 筛选截止日期 */
  endDate?: string;
  /** 需要定位的展示单号 */
  focusDocumentNo?: string;
}
export interface StoredValueRecord {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type: Exclude<StoredValueType, "ALL">;
  /** 面向用户的业务标题 */
  title: string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency: CurrencyCode;
  /** 带收支方向符号的金额，货币最小单位 */
  signedAmountMinor: number;
  /** 业务实际发生时间 */
  occurredAt: string;
  /** 面向用户的展示单号，不能代替内部业务编号 */
  documentNo: string | null;
}
export interface StoredValueRecordsPage {
  /** 当前储值门店摘要 */
  store: {
  /** 门店编号 */
  storeNo: string;
  /** 门店显示名称 */
  storeName: string; businessTimeZone: string };
  /** 服务端实际采用的查询条件 */
  filter: {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type: StoredValueType;
  /** 筛选开始日期 */
  startDate: string | null;
  /** 筛选截止日期 */
  endDate: string | null };
  /** 当前页业务记录列表 */
  records: StoredValueRecord[];
  /** 下一页游标，无后续记录时为空 */
  nextCursor: string | null;
  /** 是否还有后续分页 */
  hasMore: boolean;
}
export interface StoredValueNotificationCapability {
  /** 消息或支付通道编码 */
  channel: "WXSUB";
  /** 当前环境是否支持此能力 */
  available: boolean;
  /** 已配置的消息模板编号 */
  templateId: string | null;
  /** 不可用原因编码 */
  reasonCode: string | null;
}
