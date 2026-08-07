// 契约基础类型（与 C/B 端同一口径，见 docs/technical/architecture.md §5）：
//   响应包 { code, msg, data } · 分页 { records, total, page, size }
//   camelCase · 业务单号 xxxNo · 时间 xxxAt · 枚举大写下划线 · 金额为最小货币单位整数
// ⚠️ 契约禁止 delete*，软删除语义一律 archive* / unarchive*（工程约定 §10.6）。

export interface Result<T> {
  /** 业务状态码，`0` 表示成功。非 0 时 `data` 无意义 */
  code: number;
  /** 后端字段名是 msg（不是 message），与 C 端一致 */
  msg?: string;
  /** 业务数据 */
  data: T;
}

export interface Page<T> {
  /** 当前页数据 */
  records: T[];
  /** 满足条件的总条数（不是总页数） */
  total: number;
  /** 当前页码，从 1 起 */
  page: number;
  /** 每页条数 */
  size: number;
}

/** 可归档主数据的公共字段（G1 软删除）。 */
export interface Archivable {
  /**
   * 归档时间。**软删除标记** —— 有值即视为已删除，列表默认不返回。
   * 契约禁止 `delete*`，一律 `archive*` / `unarchive*`（工程约定 §10.6）。
   */
  archivedAt?: string | null;
}

/** 平台端数据域：所有列表查询由后端按此裁剪，前端只做展示。 */
export interface DataScope {
  /** 租户，MVP 恒为 MAIN（预留多租户，见矩阵 P-17.1.6） */
  tenantNo: string;
  /** 限定到某商家（商家运营 BD 角色） */
  merchantNo?: string;
  /** 限定到某社区（社区运营角色） */
  communityNo?: string;
  /** 限定到某自提点 */
  pickupNo?: string;
}

export const MAIN_TENANT = "MAIN";
