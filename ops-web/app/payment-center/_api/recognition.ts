// 证件类型嗅探、识别结果比对、识别结果 → 蓝新草稿字段（口径同 near-pay ops-web lib/platmch/evidence.ts 与 lib/onboarding/recognition.ts）。
import type { DocumentType, RecognizeResult } from "./types";
import { newebpayVisible } from "./newebpay-fields";

export type EvidenceKind = "image/png" | "image/jpeg" | "application/pdf";

/** 主体资料证件接口回 octet-stream，类型按文件头判断；只认 PNG / JPEG / PDF。 */
export function sniffEvidence(head: Uint8Array): EvidenceKind | undefined {
  if (head.length >= 8 && head[0] === 137 && head[1] === 80 && head[2] === 78 && head[3] === 71) return "image/png";
  if (head.length >= 3 && head[0] === 255 && head[1] === 216 && head[2] === 255) return "image/jpeg";
  if (head.length >= 5 && String.fromCharCode(...head.slice(0, 5)) === "%PDF-") return "application/pdf";
  return undefined;
}

/** 识别服务认得的证件用途；其它证件只看不识别。 */
export function recognitionTypeOf(purpose: string): DocumentType | undefined {
  return (["REGISTRATION", "OWNER_FRONT", "OWNER_BACK", "BANK_COVER"] as const).find((x) => x === purpose);
}

export type MatchState = "MATCH" | "MISMATCH" | "NOT_SUBMITTED";
export interface ComparisonRow { key: string; recognized: string; submitted?: string; state: MatchState }

/** 分隔符、大小写、千分位在证件与表单里写法不同；字符本身必须相同。 */
const normalize = (v: string) => v.normalize("NFKC").replace(/[\s\-/.,，、()（）]/g, "").toUpperCase();

/** 只比识别服务回了值的字段；结论给人看，不回写。 */
export function compareRecognition(result: RecognizeResult, submitted: Record<string, string | null | undefined>): ComparisonRow[] {
  if (!result.recognized) return [];
  return Object.entries(result.fields)
    .filter((e): e is [string, string] => typeof e[1] === "string" && e[1].trim() !== "")
    .map(([key, recognized]) => {
      const given = submitted[key];
      if (given == null || given.trim() === "") return { key, recognized, state: "NOT_SUBMITTED" as const };
      return { key, recognized, submitted: given, state: normalize(given) === normalize(recognized) ? ("MATCH" as const) : ("MISMATCH" as const) };
    });
}

const ALLOWED: Record<DocumentType, readonly string[]> = {
  REGISTRATION: ["name", "taxNo", "registeredAddress", "incorporationDate", "capitalAmount", "representativeName"],
  OWNER_FRONT: ["representativeName", "identityNo", "birthDate", "issueDate", "issuePlace", "idIssueType", "expiryDate"],
  OWNER_BACK: ["identityAddress", "expiryDate"],
  BANK_COVER: ["bankCode", "branchCode", "accountName", "bankAccount"],
};
const TARGETS: Record<string, string> = {
  name: "MemberName", taxNo: "MemberUnified", registeredAddress: "CompanyAddress", incorporationDate: "IncorporationDate", capitalAmount: "CapitalAmount",
  representativeName: "RepresentName", birthDate: "Date", issueDate: "IDCardDate", bankCode: "BankCode", branchCode: "SubBankCode", accountName: "AccountName", bankAccount: "BankAccount",
};

/** 识别结果里这份证件可用于填表的字段（发证地、领补换类型等字典值仍由运营选）。 */
export function recognizableKeys(result: RecognizeResult): string[] {
  return result.recognized ? ALLOWED[result.documentType as DocumentType]?.filter((k) => typeof result.fields[k] === "string" && result.fields[k]!.trim() !== "") ?? [] : [];
}

/**
 * 勾选的识别字段 → 草稿字段（`NWP_<code>`；银行账号同时填平台结算账号）。只转当前法律形式 / 代表人类型下可见的字段；
 * 身份证上的号码与日期只对本国自然人（个人，或公司且代表人类型 1）适用；日期转渠道格式（发证日期为民国年 yyyMMdd）。
 */
export function recognitionPatch(result: RecognizeResult, selected: readonly string[], legalForm?: string, representativeType?: string | null): Record<string, string> {
  const out: Record<string, string> = {};
  if (!result.recognized) return out;
  const doc = result.documentType as DocumentType;
  const taiwanId = legalForm === "INDIVIDUAL" || (legalForm === "COMPANY" && representativeType === "1");
  for (const key of selected) {
    if (!ALLOWED[doc]?.includes(key)) continue;
    let value = result.fields[key];
    if (typeof value !== "string" || !value.trim()) continue;
    if (doc === "OWNER_FRONT" && ["identityNo", "birthDate", "issueDate"].includes(key) && !taiwanId) continue;
    const target = key === "identityNo" ? (legalForm === "COMPANY" ? "ManagerID" : "MemberUnified") : TARGETS[key];
    if (!target || !newebpayVisible(target, legalForm, representativeType)) continue;
    if (["birthDate", "issueDate", "incorporationDate"].includes(key)) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) continue;
      if (key === "issueDate") {
        const roc = Number(value.slice(0, 4)) - 1911;
        if (roc < 1 || roc > 999) continue;
        value = String(roc).padStart(3, "0") + value.slice(5, 7) + value.slice(8, 10);
      } else value = value.replaceAll("-", "");
    }
    out[`NWP_${target}`] = value;
    if (key === "bankAccount") out.SETTLE_ACCOUNT = value;
  }
  return out;
}
