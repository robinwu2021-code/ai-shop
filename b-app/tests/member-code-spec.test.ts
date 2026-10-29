import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { describe, expect, it } from "vitest";

// Read the generated contract consumed by clients, rather than a copied DTO or source mapping.
const spec = parse(readFileSync(fileURLToPath(new URL("../../docs/api/openapi-b.yaml", import.meta.url)), "utf8"));
const operation = spec.paths["/biz/member-code/resolve"].post;

describe("统一会员码生成规格", () => {
  it("保留识别 POST 的操作名、会话鉴权且不接收身份查询参数", () => {
    expect(operation.operationId).toBe("mResolveMemberCode");
    expect(operation.security).toEqual([{ bearerAuth: [] }]);
    expect(operation.parameters).toEqual([]);
  });

  it("必须提交引用既有请求 schema 的 JSON body，且仅含有描述的凭证与门店字符串", () => {
    expect(operation.requestBody).toEqual({
      required: true,
      content: { "application/json": { schema: { $ref: "#/components/schemas/ResolveMemberCodeReq" } } },
    });
    const request = spec.components.schemas.ResolveMemberCodeReq;
    expect(request.type).toBe("object");
    expect(request.additionalProperties).toBe(false);
    expect(request.required.toSorted()).toEqual(["memberCode", "storeNo"]);
    expect(Object.keys(request.properties).toSorted()).toEqual(["memberCode", "storeNo"]);
    expect(request.properties.memberCode).toEqual({ type: "string", description: expect.stringMatching(/凭证/) });
    expect(request.properties.storeNo).toEqual({ type: "string", description: expect.stringMatching(/门店/) });
  });

  it("保留顾客引用响应及带 ISO UTC 语义的三字段 schema", () => {
    const response = operation.responses["200"].content["application/json"].schema;
    expect(response.properties.data).toEqual({ $ref: "#/components/schemas/CustomerReference" });
    expect(response.required.toSorted()).toEqual(["code", "data", "msg"]);
    const customer = spec.components.schemas.CustomerReference;
    expect(customer.type).toBe("object");
    expect(customer.additionalProperties).toBe(false);
    expect(customer.required.toSorted()).toEqual(["customerRef", "expiresAt", "maskedName"]);
    expect(Object.keys(customer.properties).toSorted()).toEqual(["customerRef", "expiresAt", "maskedName"]);
    expect(customer.properties.customerRef).toEqual({ type: "string", description: expect.stringMatching(/顾客引用/) });
    expect(customer.properties.maskedName).toEqual({ type: "string", description: expect.stringMatching(/脱敏/) });
    expect(customer.properties.expiresAt).toEqual({ type: "string", description: expect.stringMatching(/ISO 8601 UTC/) });
  });
});
