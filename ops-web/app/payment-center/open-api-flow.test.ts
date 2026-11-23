// 开放 API 接入全流程（mock）：运营端从接入申请走到商户拿密钥调通，再到泄露应急锁定。
// 对照 2026-11-23 生产试点 ume早餐店 APP07071A89D8EC4F20 的实际步骤；mock 与生产的差别写在各步注释里：
// - 生产的「应用启用」「密钥启用」各要一张已批准的审批（OPEN_APP_ENABLE / CREDENTIAL_ENABLE）、执行人 ≠ 登记人 / 录入人；
//   mock 只有 admin 一个身份、审批不设闸，这两道门在这里只校验「启用人 ≠ 录入人」。
// - 门店授权由商户端（b-app）发起，mock 网关不校验门店。
import { describe, expect, it } from "vitest";
import { createMockPayCenterApi } from "./_api/mock";
import { createMockGateway, gatewayAppOf } from "./_api/open-api-gateway";
import { OPEN_ENDPOINTS } from "./_api/open-api-catalog";
import { buildSignedCall } from "./_api/open-api-sim";
import { verifyResponse } from "./_api/open-api-signer";

const BASE = { origin: "https://tpay.neargo.ai", prefix: "/openapi/v1" };
const ep = (key: string) => OPEN_ENDPOINTS.find((e) => e.key === key)!;
const cmd = (expectedVersion: number, reason: string) => ({ expectedVersion, reason });

describe("开放 API 接入全流程（mock）", () => {
  it("申请 → 批准 → 核验回调 → 启用应用 → 签发 / 核验 / 启用密钥 → 商户调通 → 应急锁定", async () => {
    const api = createMockPayCenterApi();
    const gateway = createMockGateway(BASE.prefix, []);
    const appView = async (no: string) => (await api.apps.detail(no)).record.app;
    const call = async (key: string, appId: string, appSecret: string, params: Record<string, string> = {}, body = "") => {
      const c = await buildSignedCall({ endpoint: ep(key), params, body, appId, scheme: "HMAC", appSecret }, BASE);
      expect(c.url.startsWith("https://tpay.neargo.ai/openapi/v1/")).toBe(true);
      expect(c.signed.stringToSign.split("\n")[1]).toMatch(/^\/openapi\/v1\//); // 签名 PATH 带对外前缀
      return gateway({ method: ep(key).method, pathWithQuery: c.pathWithQuery, headers: c.signed.headers, body: c.payload });
    };

    // 1. 接入申请（SY11）：别人提交、本人批准 → 生成草稿应用
    const application = (await api.appApplications.detail("AAP26112200001")).record;
    expect(application.status).toBe("SUBMITTED");
    expect(application.submittedBy).not.toBe((await api.me()).operatorNo); // 提交人不得审核
    await api.appApplications.decide(application.applicationNo, "approve", cmd(0, "资料齐全"));
    const appNo = (await api.appApplications.detail(application.applicationNo)).record.appNo!;
    let app = await appView(appNo);
    expect(app).toMatchObject({ status: "DRAFT", callbackStatus: "UNVERIFIED", credentialStatus: "NONE", environment: "LIVE", scopes: ["READ", "COLLECT"] });

    // 2. 有回调地址就必须先核验，才能启用
    await expect(api.apps.command(appNo, "enable", cmd(app.version, "启用"))).rejects.toThrow(/回调地址核验/);
    await api.apps.verifyCallback(appNo, { ...cmd(app.version, "回调验证通过"), evidenceRef: "CB-TEST-1" });
    app = await appView(appNo);
    await api.apps.command(appNo, "enable", cmd(app.version, "试点启用")); // 生产：先过 OPEN_APP_ENABLE 审批，由非登记人执行
    expect((await appView(appNo)).status).toBe("ACTIVE");

    // 3. 签发 APP_SECRET：明文只在这一次响应里，版本是草稿
    const issued = await api.apps.issueCredential(appNo, { credentialType: "APP_SECRET", reason: "签发" });
    expect(issued).toMatchObject({ status: "DRAFT", versionIndex: 1 });
    expect(issued.secret).toMatch(/^sk_live_[a-z0-9]{32}$/);
    expect(JSON.stringify(await api.credentials.detail(issued.keyId))).not.toContain(issued.secret);
    expect((await appView(appNo)).credentialStatus).toBe("PENDING");

    // 草稿密钥调不通（网关只认应用启用 + 密钥启用）
    gateway.registerApp(gatewayAppOf(await appView(appNo), issued.secret));
    expect((await call("queryOrder", appNo, issued.secret, { payOrderNo: "P1" })).status).toBe(401);

    // 4. 核验 → 启用（生产：CREDENTIAL_ENABLE 两审 + 启用人 ≠ 录入人）
    await expect(api.credentials.enable(issued.keyId, 1, { approvalRef: "AP-TEST", reason: "跳过核验" })).rejects.toThrow(/已核验/);
    await api.credentials.verify(issued.keyId, 1, { evidenceRef: `核对 keyId ${issued.keyId} v1 归属 ${appNo}`, reason: "签发核对" });
    await api.credentials.enable(issued.keyId, 1, { approvalRef: "AP-TEST", reason: "启用" });
    app = await appView(appNo);
    expect(app).toMatchObject({ status: "ACTIVE", credentialStatus: "ACTIVE" });
    gateway.registerApp(gatewayAppOf(app, issued.secret));

    // 5. 商户调用：查单通过且响应签名可验；scope 外的退款 403；密钥错 401；正式环境不给 ping
    const ok = await call("queryOrder", appNo, issued.secret, { payOrderNo: "P1" });
    expect(ok.status).toBe(200);
    expect(JSON.parse(ok.body).code).toBe(0);
    expect((await verifyResponse(issued.secret, ok.headers, ok.body)).status).toBe("VALID");
    expect((await call("createOrder", appNo, issued.secret, {}, '{"outOrderNo":"O1","amountMinor":100,"currency":"TWD","subject":"早餐"}')).status).toBe(200);
    expect((await call("refund", appNo, issued.secret, { payOrderNo: "P1" }, '{"outRefundNo":"R1","amountMinor":100}')).status).toBe(403);
    expect((await call("queryOrder", appNo, `${issued.secret.slice(0, -1)}x`, { payOrderNo: "P1" })).status).toBe(401);
    expect((await call("ping", appNo, issued.secret, {}, "{}")).status).toBe(403);

    // 6. 泄露应急：吊销全部密钥版本、日退款上限置 0 → 再调 401
    const locked = await api.apps.emergencyLock(appNo, "密钥疑似泄露");
    expect(locked.revokedVersions).toContain(`${issued.keyId}:v1`);
    expect(locked.refundLimitAfter).toBe(0);
    app = await appView(appNo);
    expect(app.credentialStatus).toBe("REVOKED");
    gateway.registerApp(gatewayAppOf(app, issued.secret));
    expect((await call("queryOrder", appNo, issued.secret, { payOrderNo: "P1" })).status).toBe(401);
  });

  it("没配回调地址的应用可以直接启用（与 near-pay 一致：notifyUrl 为空不要求核验）", async () => {
    const api = createMockPayCenterApi();
    const d = await api.apps.create({ name: "无回调", purpose: null, ownerKind: "MERCHANT", merchantNo: "PM1001", environment: "LIVE", scopes: ["READ"], notifyUrl: null, callbackDomains: [], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, reason: "试点" } as never);
    const no = d.record.app.appNo;
    await api.apps.command(no, "enable", cmd(d.record.app.version, "启用"));
    expect((await api.apps.detail(no)).record.app.status).toBe("ACTIVE");
  });
});
