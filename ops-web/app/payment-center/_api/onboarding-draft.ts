// 进件草稿的规则（纯函数，可测）：平台字段、蓝新字段分到哪个抽屉、按法律形式 / 代表人类型显隐、哪些要先封存、改动怎么算，
// 以及按状态出哪些操作。口径同 near-pay ops-web material-form / material-editor 与 OnboardingApplicationService.allowedActions。
import { newebpayMeta, newebpayVisible, type NewebPayFieldMeta } from "./newebpay-fields";
import type { OnboardingDraft, OnboardingRow } from "./types";

/** 平台通用资料（与渠道无关）：电话与结算账号是敏感值，按蓝新的对应字段封存 */
export const PLATFORM_FIELDS = ["LEGAL_FORM", "CONTACT_NAME", "CONTACT_PHONE", "SETTLE_ACCOUNT_TYPE", "SETTLE_ACCOUNT"] as const;
const PLATFORM_SEAL: Record<string, string> = { CONTACT_PHONE: "MemberPhone", SETTLE_ACCOUNT: "BankAccount" };

/** 蓝新字段分 5 个抽屉（一个抽屉一件事）；SYSTEM 字段（回跳 / 通知网址）由系统填，不给人改 */
export const NWP_SECTIONS = ["identity", "contact", "store", "settlement", "ops"] as const;
export type NwpSection = (typeof NWP_SECTIONS)[number];
const GROUP_SECTION: Record<string, NwpSection> = {
  主体: "identity", 企業代表人: "identity", 身分資料: "identity",
  企業資料: "contact", 联系: "contact", 會員地址: "contact", 管理者: "contact", 聯絡資料: "contact",
  商店: "store", 商店資料: "store", 地址: "store",
  结算: "settlement", 結算資料: "settlement",
};

export function sectionOf(meta: NewebPayFieldMeta): NwpSection | null {
  if (meta.owner === "SYSTEM") return null;
  if (meta.owner !== "MERCHANT") return "ops";
  return GROUP_SECTION[meta.group] ?? "store";
}

/** 这个抽屉在当前法律形式 / 代表人类型下要显示的蓝新字段（按定义顺序） */
export function sectionFields(section: NwpSection, legalForm: string | undefined, representativeType: string | null | undefined): NewebPayFieldMeta[] {
  return Object.values(newebpayMeta).filter((m) => sectionOf(m) === section && newebpayVisible(m.code, legalForm, representativeType));
}

/** 必填：required 含 ANY、法律形式，或公司且含 T<代表人类型>。付款方式条件（CREDIT / WEB）交给服务端的缺项清单。 */
export function requiredIn(meta: NewebPayFieldMeta, legalForm: string | undefined, representativeType: string | null | undefined): boolean {
  const r = meta.required;
  return r.includes("ANY") || (!!legalForm && r.includes(legalForm))
    || (legalForm === "COMPANY" && !!representativeType && r.includes(`T${representativeType}`));
}

export const draftCodeOf = (nwpCode: string) => `NWP_${nwpCode}`;

/** 草稿字段要不要先封存；要的话返回封存口的 fieldCode */
export function sealCodeOf(draftCode: string): string | null {
  if (PLATFORM_SEAL[draftCode]) return PLATFORM_SEAL[draftCode];
  if (draftCode.startsWith("NWP_")) { const code = draftCode.slice(4); return newebpayMeta[code]?.sensitive ? code : null; }
  return null;
}

/**
 * 表单值 → 要写进草稿的改动：普通字段与草稿现值不同才发；敏感字段留空 = 沿用（不发），填了才发。
 * 公司的代表人证件号码按渠道要求写成「类型;号码」。
 */
export function changesOf(draft: Pick<OnboardingDraft, "fields">, values: Record<string, unknown>, codes: readonly string[], representativeType?: string | null): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const code of codes) {
    let v = String(values[code] ?? "").trim();
    if (sealCodeOf(code)) {
      if (!v) continue;
      if (code === "NWP_ManagerID" && representativeType && !v.includes(";")) v = `${representativeType};${v}`;
      out[code] = v;
      continue;
    }
    const cur = draft.fields[code] ?? "";
    if (v !== cur) out[code] = v === "" ? null : v;
  }
  return out;
}

/** 草稿还能不能改（near-pay OnboardingDraftService.editable） */
export function draftEditable(row: Pick<OnboardingRow, "status" | "reviewStatus">): boolean {
  return (row.status === "DRAFT" && ["NONE", "NEED_MORE", "REJECTED"].includes(row.reviewStatus)) || row.status === "REJECTED";
}

export const usesDraft = (row: Pick<OnboardingRow, "payChannel">) => row.payChannel === "NEWEBPAY";

/** 按状态出的操作（near-pay OnboardingApplicationService.allowedActions；服务端照样判，这里只是不出无用按钮） */
export function onboardingActions(row: Pick<OnboardingRow, "status" | "reviewStatus" | "submitMode" | "payChannel" | "submitOutcome">): string[] {
  const nwp = usesDraft(row);
  switch (row.status) {
    case "DRAFT": return [
      ...(!["PENDING", "APPROVED"].includes(row.reviewStatus) ? ["submit"] : []),
      ...(row.reviewStatus === "PENDING" ? ["review"] : []),
      ...(row.reviewStatus === "APPROVED" ? ["dispatch"] : []),
      "cancel",
    ];
    case "SUBMITTED": return [...(row.submitOutcome === "UNKNOWN" ? ["dispatch"] : []), "refresh"];
    case "CHANNEL_REVIEW": case "NEED_SIGN": return [row.submitMode === "MANUAL" ? "manualResult" : "refresh"];
    case "REJECTED": return ["submit"];
    case "APPROVED": return nwp ? ["retryHandoff", "refresh"] : ["refresh"];
    default: return [];
  }
}
