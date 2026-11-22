import { describe, expect, it } from "vitest";
import {
  carrierIdValid,
  defaultChoice,
  destinationKey,
  donateCodeValid,
  formatFiscalNumber,
  invoiceMoney,
  normalizeCarrierId,
  periodParts,
  taxIdValid,
} from "@/shared/sales-invoice";

// 台湾电子发票（买方）的展示与提前提示。格式与后端 TwBuyerRules 同一判据。
describe("sales invoice helpers", () => {
  it("formats the fiscal number the way it is printed", () => {
    expect(formatFiscalNumber("AB12345678")).toBe("AB-12345678");
    expect(formatFiscalNumber("weird")).toBe("weird");
    expect(formatFiscalNumber(null)).toBe("");
  });

  it("reads the bi-monthly period code", () => {
    expect(periodParts("11512")).toEqual({ y: 115, a: 11, b: 12 });
    expect(periodParts("11502")).toEqual({ y: 115, a: 1, b: 2 });
    expect(periodParts("11511")).toBeNull();
    expect(periodParts("abc")).toBeNull();
  });

  it("checks carrier formats like the server", () => {
    expect(carrierIdValid("3J0002", "/ABC+123")).toBe(true);
    expect(carrierIdValid("3J0002", normalizeCarrierId(" /abc.12- "))).toBe(true);
    expect(carrierIdValid("3J0002", "ABC+1234")).toBe(false);
    expect(carrierIdValid("CQ0001", "AB12345678901234")).toBe(true);
    expect(carrierIdValid("CQ0001", "A123456789012345")).toBe(false);
    expect(taxIdValid("12345678")).toBe(true);
    expect(taxIdValid("1234567")).toBe(false);
    expect(donateCodeValid("123")).toBe(true);
    expect(donateCodeValid("12345678")).toBe(false);
  });

  it("prefers the default saved carrier, else the member carrier", () => {
    expect(defaultChoice([])).toEqual({ kind: "MEMBER", carrierNo: "" });
    expect(defaultChoice([
      { carrierNo: "C1", carrierType: "3J0002", carrierId: "/ABC+123", isDefault: false },
      { carrierNo: "C2", carrierType: "CQ0001", carrierId: "AB12345678901234", isDefault: true },
    ])).toEqual({ kind: "CARRIER", carrierNo: "C2" });
    expect(defaultChoice([{ carrierNo: "C1", carrierType: "3J0002", carrierId: "/ABC+123", isDefault: false }]))
      .toEqual({ kind: "MEMBER", carrierNo: "" });
  });

  it("says where the invoice went", () => {
    expect(destinationKey({ donateCode: "123", carrierType: null, buyerTaxId: null })).toBe("salesInvoice.dest.donate");
    expect(destinationKey({ donateCode: null, carrierType: "3J0002", buyerTaxId: null })).toBe("salesInvoice.dest.mobile");
    expect(destinationKey({ donateCode: null, carrierType: "EG0001", buyerTaxId: null })).toBe("salesInvoice.dest.member");
    expect(destinationKey({ donateCode: null, carrierType: null, buyerTaxId: "12345678" })).toBe("salesInvoice.dest.taxId");
    expect(destinationKey({ donateCode: null, carrierType: null, buyerTaxId: null })).toBe("salesInvoice.dest.paper");
  });

  it("shows TWD minor units with two decimals", () => {
    expect(invoiceMoney(123456, "TWD")).toBe("TWD 1,234.56");
  });
});
