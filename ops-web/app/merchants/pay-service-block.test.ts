import { describe, expect, it } from "vitest";
import { toApplyForm, PAY_APPLY_PERM } from "./pay-service-block";

describe("申请支付服务（TDD-运营端申请支付服务 A1）", () => {
  it("表单去空白，可选项空串不发", () => {
    expect(toApplyForm({ legalName: " 創勢科技股份有限公司 ", registrationNo: "507656342", representativeName: " ", contactPhone: "" }))
      .toEqual({ legalName: "創勢科技股份有限公司", registrationNo: "507656342", representativeName: undefined, contactPhone: undefined, registeredAddress: undefined });
  });

  it("权限码与后端一致", () => {
    expect(PAY_APPLY_PERM).toBe("merchant:merchant:pay-apply");
  });
});
