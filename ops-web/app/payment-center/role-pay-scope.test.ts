import { describe, expect, it } from "vitest";
import { activeRoleScopes } from "./role-pay-scope";
import type { DataScopeRow } from "./_api/types";

const row = (over: Partial<DataScopeRow>): DataScopeRow => ({
  scopeNo: "S1", scopeType: "ROLE", subjectNo: "FINANCE", marketSet: ["TW"], entityNos: ["*"], storeNos: [],
  fundsAccess: "MASKED_READ", expiresAt: 2_000, status: "ACTIVE", reason: null, enteredBy: null, enabledBy: null, revokedAt: null, ...over,
});

describe("角色页 · 支付中心数据范围（TDD-支付运营端经网关转发与权限下沉 §10 B2）", () => {
  it("只留这个角色、生效中、未到期的角色委托", () => {
    const rows = [
      row({ scopeNo: "ok" }),
      row({ scopeNo: "other-role", subjectNo: "BD" }),
      row({ scopeNo: "user", scopeType: "USER" }),
      row({ scopeNo: "draft", status: "DRAFT" }),
      row({ scopeNo: "revoked", status: "REVOKED" }),
      row({ scopeNo: "expired", expiresAt: 500 }),
    ];
    expect(activeRoleScopes(rows, "FINANCE", 1_000).map((r) => r.scopeNo)).toEqual(["ok"]);
  });
});
