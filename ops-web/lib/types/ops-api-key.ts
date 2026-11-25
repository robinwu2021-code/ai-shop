export type OpsApiKeyAccess = "READ_ONLY" | "MANAGE";
export type OpsApiKeyStatus = "ACTIVE" | "EXPIRED" | "REVOKED";

export interface MyOpsApiKey {
  /** 本机客户端生成的 UUID。 */
  keyNo: string;
  /** 员工填写的用途。 */
  label: string;
  /** 密钥访问级别，仍受运营员工当前角色和数据域约束。 */
  access: OpsApiKeyAccess;
  /** 签发的员工编号。 */
  issuedBy: string;
  /** 签发时间，Unix 毫秒。 */
  createdAt: number;
  /** 到期时间，Unix 毫秒。 */
  expiresAt: number;
  /** 撤销时间，Unix 毫秒。未撤销时为空。 */
  revokedAt?: number | null;
  /** 服务端按到期和撤销时间计算的状态。 */
  status: OpsApiKeyStatus;
}

export interface RegisterOpsApiKey {
  /** 本机客户端生成的 UUID。 */
  keyNo: string;
  /** 员工填写的用途。 */
  label: string;
  /** 密钥访问级别，仍受运营员工当前角色和数据域约束。 */
  access?: OpsApiKeyAccess;
  /** 有效天数，取值 1–90。 */
  validDays: number;
  /** 本机完整密钥的 SHA-256 十六进制摘要。 */
  secretHash: string;
}
