import { describe, expect, it } from "vitest";
import { compareRecognition, recognitionPatch, recognizableKeys, sniffEvidence } from "./recognition";
import type { RecognizeResult } from "./types";

const reg = (fields: Record<string, string | null>, documentType = "REGISTRATION"): RecognizeResult => ({ recognized: true, documentType, fields, confidence: 0.9 });

describe("证件识别", () => {
  it("按文件头认 PNG / JPEG / PDF，其它不认", () => {
    expect(sniffEvidence(new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]))).toBe("image/png");
    expect(sniffEvidence(new Uint8Array([255, 216, 255, 0]))).toBe("image/jpeg");
    expect(sniffEvidence(new TextEncoder().encode("%PDF-1.7"))).toBe("application/pdf");
    expect(sniffEvidence(new TextEncoder().encode("<html>"))).toBeUndefined();
  });

  it("比对忽略空白、分隔符与全半角；没提交的标未提交", () => {
    const rows = compareRecognition(reg({ taxNo: "2453 6806", name: "台北早餐有限公司", registeredAddress: "台北市" }), { taxNo: "24536806", name: "台北早餐（股）" });
    expect(rows.find((r) => r.key === "taxNo")?.state).toBe("MATCH");
    expect(rows.find((r) => r.key === "name")?.state).toBe("MISMATCH");
    expect(rows.find((r) => r.key === "registeredAddress")?.state).toBe("NOT_SUBMITTED");
    expect(compareRecognition({ ...reg({ taxNo: "1" }), recognized: false }, {})).toEqual([]);
  });

  it("识别结果转草稿：只转勾选且可见的字段；日期改渠道格式；银行账号同填结算账号", () => {
    expect(recognitionPatch(reg({ name: "台北早餐有限公司", incorporationDate: "2019-05-01", capitalAmount: "5000000" }), ["name", "incorporationDate"], "COMPANY"))
      .toEqual({ NWP_MemberName: "台北早餐有限公司", NWP_IncorporationDate: "20190501" });
    expect(recognitionPatch(reg({ bankAccount: "00123456789012" }, "BANK_COVER"), ["bankAccount"], "COMPANY"))
      .toEqual({ NWP_BankAccount: "00123456789012", SETTLE_ACCOUNT: "00123456789012" });
    // 身份证：发证日期转民国年；外国籍代表人（类型 2）不填身份证号码与日期
    expect(recognitionPatch(reg({ identityNo: "A123456789", issueDate: "2018-07-01" }, "OWNER_FRONT"), ["identityNo", "issueDate"], "INDIVIDUAL"))
      .toEqual({ NWP_MemberUnified: "A123456789", NWP_IDCardDate: "1070701" });
    expect(recognitionPatch(reg({ identityNo: "A123456789" }, "OWNER_FRONT"), ["identityNo"], "COMPANY", "2")).toEqual({});
    // 公司登记的「企業設立日期」对个人不可见，不转
    expect(recognitionPatch(reg({ incorporationDate: "2019-05-01" }), ["incorporationDate"], "INDIVIDUAL")).toEqual({});
  });

  it("可勾选的识别字段只列这类证件允许且有值的", () => {
    expect(recognizableKeys(reg({ name: "A", taxNo: "", bankAccount: "1" }))).toEqual(["name"]);
  });
});
