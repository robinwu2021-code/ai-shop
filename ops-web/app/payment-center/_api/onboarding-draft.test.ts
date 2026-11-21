import { describe, expect, it } from "vitest";
import { newebpayMeta } from "./newebpay-fields";
import { NWP_SECTIONS, changesOf, draftEditable, onboardingActions, requiredIn, sealCodeOf, sectionFields, sectionOf } from "./onboarding-draft";

describe("进件草稿规则", () => {
  it("每个非系统蓝新字段都落在某个抽屉；系统字段不给人改", () => {
    for (const m of Object.values(newebpayMeta)) {
      if (m.owner === "SYSTEM") expect(sectionOf(m)).toBeNull();
      else expect(NWP_SECTIONS).toContain(sectionOf(m));
    }
    expect(sectionOf(newebpayMeta.LoginAccount)).toBe("ops");
  });

  it("显隐与必填：公司才有代表人字段；代表人类型决定身分资料", () => {
    const company1 = sectionFields("identity", "COMPANY", "1").map((m) => m.code);
    expect(company1).toEqual(expect.arrayContaining(["MemberUnified", "RepresentName", "ManagerID", "IDCardDate", "IDCardPlace", "Date"]));
    expect(company1).not.toContain("ARCexp");
    expect(sectionFields("identity", "COMPANY", "2").map((m) => m.code)).toContain("ARCexp");
    expect(sectionFields("identity", "INDIVIDUAL", null).map((m) => m.code)).not.toContain("RepresentName");
    expect(requiredIn(newebpayMeta.RepresentName, "COMPANY", "1")).toBe(true);
    expect(requiredIn(newebpayMeta.ARCexp, "COMPANY", "2")).toBe(true);
    expect(requiredIn(newebpayMeta.MemberAddr, "COMPANY", "1")).toBe(false);
  });

  it("封存：平台电话 / 账号按蓝新对应字段；蓝新敏感字段按自身代码；普通字段不封存", () => {
    expect(sealCodeOf("CONTACT_PHONE")).toBe("MemberPhone");
    expect(sealCodeOf("SETTLE_ACCOUNT")).toBe("BankAccount");
    expect(sealCodeOf("NWP_BankAccount")).toBe("BankAccount");
    expect(sealCodeOf("NWP_MerchantName")).toBeNull();
    expect(sealCodeOf("CONTACT_NAME")).toBeNull();
  });

  it("改动：普通字段与现值不同才发、清空发 null；敏感字段留空沿用；公司代表人证号写成「类型;号码」", () => {
    const draft = { fields: { CONTACT_NAME: "王", NWP_MerchantName: "早餐" } };
    expect(changesOf(draft, { CONTACT_NAME: "王", NWP_MerchantName: "", NWP_BankAccount: "", CONTACT_PHONE: "0912" }, ["CONTACT_NAME", "NWP_MerchantName", "NWP_BankAccount", "CONTACT_PHONE"]))
      .toEqual({ NWP_MerchantName: null, CONTACT_PHONE: "0912" });
    expect(changesOf({ fields: {} }, { NWP_ManagerID: "A123456789" }, ["NWP_ManagerID"], "1")).toEqual({ NWP_ManagerID: "1;A123456789" });
    expect(changesOf({ fields: {} }, { NWP_ManagerID: "5;12345678" }, ["NWP_ManagerID"], "1")).toEqual({ NWP_ManagerID: "5;12345678" });
  });

  it("可改与可做的操作跟着状态走", () => {
    expect(draftEditable({ status: "DRAFT", reviewStatus: "NEED_MORE" })).toBe(true);
    expect(draftEditable({ status: "DRAFT", reviewStatus: "PENDING" })).toBe(false);
    expect(draftEditable({ status: "REJECTED", reviewStatus: "REJECTED" })).toBe(true);
    const base = { submitMode: "MANUAL", payChannel: "NEWEBPAY", submitOutcome: null };
    expect(onboardingActions({ ...base, status: "DRAFT", reviewStatus: "APPROVED" })).toEqual(["dispatch", "cancel"]);
    expect(onboardingActions({ ...base, status: "DRAFT", reviewStatus: "PENDING" })).toEqual(["review", "cancel"]);
    expect(onboardingActions({ ...base, status: "SUBMITTED", reviewStatus: "APPROVED", submitOutcome: "UNKNOWN" })).toEqual(["dispatch", "refresh"]);
    expect(onboardingActions({ ...base, status: "CHANNEL_REVIEW", reviewStatus: "APPROVED" })).toEqual(["manualResult"]);
    expect(onboardingActions({ ...base, status: "CHANNEL_REVIEW", reviewStatus: "APPROVED", submitMode: "API" })).toEqual(["refresh"]);
    expect(onboardingActions({ ...base, status: "APPROVED", reviewStatus: "APPROVED" })).toEqual(["retryHandoff", "refresh"]);
  });
});
