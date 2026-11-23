// P12 开放 API（TDD-支付中心运营页面 §2.16）：HTTP 映射对 near-pay develop/s1 a1c5da09 的三个控制器、
// mock 状态机（提交人不得审核、批准建草稿应用、持有证明、应急锁定、平台密钥两人启用）、签名与契约测试向量、mock 网关。
import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { OPEN_ENDPOINTS, resolveOpenApiBase } from "./_api/open-api-catalog";
import { buildSignedCall } from "./_api/open-api-sim";
import { aesGcmEncryptField, generateRsaKeyPair, hmacBase64, hmacKeyOf, rsaSignBase64, sha256Hex, signRequest, verifyResponse } from "./_api/open-api-signer";
import { createMockGateway, TEST_VECTOR_APP } from "./_api/open-api-gateway";
import { merchantCommands } from "./app-keys";
import { approvalActionVisible } from "./approval-actions";

function httpApi(data: unknown = {}) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};
const ord = OPEN_ENDPOINTS.find((e) => e.key === "createOrder")!;
const ping = OPEN_ENDPOINTS.find((e) => e.key === "ping")!;

describe("P12 · HTTP 映射（OpsAppApplicationController / OpsAppKeyController / OpsPlatformKeyController）", () => {
  it("接入申请：列表按搜索前缀分流到 applicationNo / merchantNo；详情；提交带幂等号；批准 / 驳回 / 撤回", async () => {
    const { f, api } = httpApi({ records: [], total: 0, record: { applicationNo: "AAP1" }, version: 0 });
    await api.appApplications.list({ page: 1, size: 20, keyword: "AAP26112200001" });
    await api.appApplications.list({ page: 1, size: 20, keyword: "UME-M-88600253990002308", status: "SUBMITTED" });
    await api.appApplications.detail("AAP1");
    const body = { merchantNo: "UME-M-88600253990002308", appName: "ume 早餐店 POS", purpose: null, scopes: ["READ", "COLLECT"], notifyUrl: null, callbackDomains: [], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, contactName: null, contactEmail: null, signType: "HMAC" as const };
    await api.appApplications.submit(body);
    await api.appApplications.submit(body);
    await api.appApplications.decide("AAP1", "approve", { expectedVersion: 0, reason: "资料齐全" });
    await api.appApplications.decide("AAP1", "withdraw", { expectedVersion: 0, reason: "不需要了" });
    expect(call(f, 0).url).toBe("/pay/app-applications?page=1&size=20&applicationNo=AAP26112200001");
    expect(call(f, 1).url).toBe("/pay/app-applications?page=1&size=20&status=SUBMITTED&merchantNo=UME-M-88600253990002308");
    expect(call(f, 2).url).toBe("/pay/app-applications/AAP1");
    expect(call(f, 3)).toMatchObject({ url: "/pay/app-applications", method: "POST", body: { merchantNo: "UME-M-88600253990002308", signType: "HMAC", scopes: ["READ", "COLLECT"] } });
    expect(call(f, 3).body.requestNo).toBe(call(f, 4).body.requestNo);
    expect(call(f, 5)).toMatchObject({ url: "/pay/app-applications/AAP1/approve", method: "POST", body: { expectedVersion: 0, reason: "资料齐全" } });
    expect(call(f, 5).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 6).url).toBe("/pay/app-applications/AAP1/withdraw");
  });

  it("应用密钥：挑战与上传不带自动幂等号；应急锁定带；用量默认 7 天", async () => {
    const { f, api } = httpApi([]);
    await api.apps.keyChallenge("APP1");
    await api.apps.uploadPublicKey("APP1", { publicKeyPem: "-----BEGIN PUBLIC KEY-----", challenge: "CH1", timestamp: "1", signature: "s", reason: "上线" });
    await api.apps.emergencyLock("APP1", "疑似泄露");
    await api.apps.keyUsage("APP1");
    expect(call(f, 0)).toMatchObject({ url: "/pay/apps/APP1/public-keys/challenge", method: "POST" });
    expect(call(f, 0).body.requestNo).toBeUndefined();
    expect(call(f, 1)).toMatchObject({ url: "/pay/apps/APP1/public-keys", body: { challenge: "CH1", timestamp: "1" } });
    expect(call(f, 1).body.requestNo).toBeUndefined();
    expect(call(f, 2)).toMatchObject({ url: "/pay/apps/APP1/emergency-lock", body: { reason: "疑似泄露" } });
    expect(call(f, 2).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 3).url).toBe("/pay/apps/APP1/key-usage?days=7");
  });

  it("平台密钥：列表（null → 空数组）、按用途生成", async () => {
    const { f, api } = httpApi(null);
    expect(await api.platformKeys.list()).toEqual([]);
    await api.platformKeys.generate("ENCRYPT", "首次生成");
    expect(call(f, 0).url).toBe("/pay/platform-keys");
    expect(call(f, 1)).toMatchObject({ url: "/pay/platform-keys/ENCRYPT/generate", method: "POST", body: { reason: "首次生成" } });
  });
});

describe("「是不是我」按 near-pay 员工号判断（/me/access），不按登录名", () => {
  it("HTTP：me() 取 /me/access；mock 的我是 admin", async () => {
    const { f, api } = httpApi({ operatorNo: "ST-NEARA", roles: ["SUPER_ADMIN"] });
    expect((await api.me()).operatorNo).toBe("ST-NEARA");
    expect(call(f, 0).url).toBe("/pay/me/access");
    expect((await createMockPayCenterApi().me()).operatorNo).toBe("admin");
  });

  it("审核待办、告警、接入申请都不再拿登录名比认领人 / 申请人 / 提交人", async () => {
    const fs = await import("node:fs");
    for (const file of ["approvals-tab.tsx", "alerts-tab.tsx", "app-applications-tab.tsx"]) {
      const src = fs.readFileSync(new URL(`./${file}`, import.meta.url), "utf8");
      expect(src, file).toContain("usePayOperatorNo()");
      expect(src, file).not.toMatch(/useAuth\(\(s\) => s\.username\)/);
    }
  });

  it("审核待办按钮：服务端给了 allowedActions 就以它为准，不受列表旧行与取不到员工号影响", () => {
    const row = { status: "PENDING_SECOND", assigneeNo: null, applicantNo: "ST-NEARA", executionStatus: "NOT_STARTED" };
    // 认领后列表行还是旧的（assigneeNo 为空），员工号也没取到 —— 只要服务端说能审，同意 / 驳回 / 释放 / 撤回都要出现
    const server = ["review", "release", "assign", "withdraw"];
    for (const k of ["approve", "reject", "release", "withdraw", "assign"]) expect(approvalActionVisible(k, row, null, server), k).toBe(true);
    expect(approvalActionVisible("claim", row, null, server)).toBe(false);
    expect(approvalActionVisible("execute", row, null, server)).toBe(false);
    // 服务端没说（列表行上的菜单）：退回本地判断
    expect(approvalActionVisible("approve", { ...row, assigneeNo: "ST-NEARA" }, "ST-NEARA", undefined)).toBe(true);
    expect(approvalActionVisible("approve", row, "ST-NEARA", undefined)).toBe(false);
    expect(approvalActionVisible("claim", row, "ST-NEARA", undefined)).toBe(true);
  });

  it("员工号取失败会重试，不把一次超时缓存成「不是我」", async () => {
    const src = (await import("node:fs")).readFileSync(new URL("./shared.tsx", import.meta.url), "utf8");
    expect(src).toMatch(/queryKey: \[KEY, "me", api\.source\][^\n]*retry: (?!false)/);
  });
});

describe("数据范围 · 提交启用审批（near-pay DataScopeSubject）", () => {
  it("接入应用：草稿 / 暂停的应用有「提交启用审批」，按 OPEN_APP_ENABLE 提交、冻结版本 = 当前版本 + 1", async () => {
    const src = (await import("node:fs")).readFileSync(new URL("./apps-tab.tsx", import.meta.url), "utf8");
    expect(src).toMatch(/key: "submitEnable"[^\n]*perm: "pay:app:manage"[^\n]*visible: st\("DRAFT", "SUSPENDED"\)/);
    expect(src).toMatch(/subjectType: "OPEN_APP_ENABLE", subjectRef: r!\.appNo, subjectRevision: \(await api\.apps\.detail\(r!\.appNo\)\)\.version \+ 1, action: "ENABLE"/);
  });

  it("凭证版本：已核验的版本有「提交启用审批」，按 CREDENTIAL_ENABLE 提交、对象号 keyId:版本", async () => {
    const src = (await import("node:fs")).readFileSync(new URL("./credentials-tab.tsx", import.meta.url), "utf8");
    expect(src).toMatch(/submit: v\.status === "VERIFIED" && allowed\.includes\("ENABLE"\) && allow\("pay:credential:manage"\)/);
    expect(src).toContain('subjectType: "CREDENTIAL_ENABLE", subjectRef: `${keyId}:${v.versionIndex}`, subjectRevision: v.versionIndex, action: "ENABLE"');
  });

  it("DATA_SCOPE_ENABLE / ENABLE，冻结版本 = 当前版本 + 1，带幂等号", async () => {
    const { f, api } = httpApi({ approvalNo: "AP1" });
    await api.approvals.submit({ subjectType: "DATA_SCOPE_ENABLE", subjectRef: "DSA90145CF72E34A40", subjectRevision: 1, action: "ENABLE", reason: "开放 API 试点" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/approvals", method: "POST", body: { subjectType: "DATA_SCOPE_ENABLE", subjectRef: "DSA90145CF72E34A40", subjectRevision: 1, action: "ENABLE" } });
    expect(call(f, 0).body.requestNo).toMatch(/^ops-/);
  });

  it("页面：草稿行有「提交启用审批」，按 DATA_SCOPE_ENABLE 提交", async () => {
    const src = (await import("node:fs")).readFileSync(new URL("./data-scopes-tab.tsx", import.meta.url), "utf8");
    expect(src).toMatch(/key: "submitEnable"[\s\S]*subjectType: "DATA_SCOPE_ENABLE"[\s\S]*action: "ENABLE"/);
  });
});

describe("P12 · mock 状态机", () => {
  it("★ 提交人不得审核、只能撤回；他人批准即建草稿应用（签名方案带过去）", async () => {
    const api = createMockPayCenterApi();
    const d = await api.appApplications.submit({ merchantNo: "PM1003", appName: "面包坊 ERP", purpose: null, scopes: ["READ"], notifyUrl: "https://erp.bread.tw/notify", callbackDomains: ["bread.tw"], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, contactName: null, contactEmail: null, signType: "RSA" });
    expect(d.allowedActions).toEqual(["approve", "reject", "withdraw"]);
    await expect(api.appApplications.decide(d.record.applicationNo, "approve", { expectedVersion: 0, reason: "自己批" })).rejects.toThrow(/提交人不得审核/);
    await api.appApplications.decide(d.record.applicationNo, "withdraw", { expectedVersion: 0, reason: "撤回" });
    expect((await api.appApplications.detail(d.record.applicationNo)).record.status).toBe("WITHDRAWN");

    const r = await api.appApplications.decide("AAP26112200001", "approve", { expectedVersion: 0, reason: "资料齐全" });
    expect(r.status).toBe("APPROVED");
    const a = (await api.appApplications.detail("AAP26112200001")).record;
    const app = (await api.apps.detail(a.appNo!)).record.app;
    expect(app).toMatchObject({ status: "DRAFT", merchantNo: "PM1001", scopes: ["READ", "COLLECT"], signType: "HMAC" });
    await expect(api.appApplications.decide("AAP26112200001", "reject", { expectedVersion: 1, reason: "再驳" })).rejects.toThrow(/已处理/);
  });

  it("提交校验：回调域名须在白名单、接口权限非空", async () => {
    const api = createMockPayCenterApi();
    const base = { merchantNo: "PM1001", appName: "x", purpose: null, scopes: ["READ"], notifyUrl: null, callbackDomains: [], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, contactName: null, contactEmail: null, signType: "HMAC" as const };
    await expect(api.appApplications.submit({ ...base, notifyUrl: "https://evil.example/n", callbackDomains: ["good.tw"] })).rejects.toThrow(/白名单/);
    await expect(api.appApplications.submit({ ...base, scopes: [] })).rejects.toThrow(/READ/);
  });

  it("★ RSA 商家公钥：挑战 → 私钥签名 → 上传为已核验；挑战一次性；HMAC 应用不给挑战", async () => {
    const api = createMockPayCenterApi();
    await expect(api.apps.keyChallenge("APP9001")).rejects.toThrow(/RSA/);
    const ch = await api.apps.keyChallenge("APP9004");
    expect(ch.stringToSignPrefix).toBe(`pop|APP9004|${ch.challenge}|`);
    expect(merchantCommands(ch)).toContain(ch.stringToSignPrefix);
    const pair = await generateRsaKeyPair("SIGN"); const ts = String(Date.now());
    const other = await generateRsaKeyPair("SIGN");
    const bad = { publicKeyPem: pair.publicPem, challenge: ch.challenge, timestamp: ts, signature: await rsaSignBase64(other.privatePem, ch.stringToSignPrefix + ts), reason: "上线" };
    await expect(api.apps.uploadPublicKey("APP9004", bad)).rejects.toThrow(/持有证明/);
    const ch2 = await api.apps.keyChallenge("APP9004");
    const added = await api.apps.uploadPublicKey("APP9004", { publicKeyPem: pair.publicPem, challenge: ch2.challenge, timestamp: ts, signature: await rsaSignBase64(pair.privatePem, ch2.stringToSignPrefix + ts), reason: "上线" });
    expect(added.version).toMatchObject({ status: "VERIFIED", enteredBy: "admin" });
    expect(added.version.externalKeyId).toMatch(/^K\d{16}$/);
    await expect(api.apps.uploadPublicKey("APP9004", { publicKeyPem: pair.publicPem, challenge: ch2.challenge, timestamp: ts, signature: "x", reason: "重放" })).rejects.toThrow(/挑战/);
  });

  it("泄露应急锁定：吊销应用全部签名密钥版本，单日退款上限置 0", async () => {
    const api = createMockPayCenterApi();
    const r = await api.apps.emergencyLock("APP9001", "疑似泄露");
    expect(r.revokedVersions.length).toBeGreaterThan(0);
    expect(r).toMatchObject({ refundLimitBefore: 50_000_00, refundLimitAfter: 0 });
    expect((await api.credentials.detail(4102)).versions.every((v) => v.status === "REVOKED")).toBe(true);
    expect((await api.apps.detail("APP9001")).record.app.dailyRefundLimitMinor).toBe(0);
    expect((await api.apps.keyUsage("APP9001")).length).toBeGreaterThan(0);
  });

  it("★ 平台密钥：生成是草稿（不公布）→ 核验后预告 → 生成人不能自己启用", async () => {
    const api = createMockPayCenterApi();
    const before = await api.platformKeys.list();
    expect(before.map((k) => [k.usage, k.status])).toEqual([["SIGN", "ACTIVE"], ["ENCRYPT", "VERIFIED"]]);
    const g = await api.platformKeys.generate("SIGN", "轮换");
    expect(g.version.status).toBe("DRAFT");
    expect(JSON.stringify(g)).not.toMatch(/PRIVATE/);
    expect((await api.platformKeys.list()).length).toBe(2);
    await api.credentials.verify(g.keyId, 1, { evidenceRef: "OPS-1", reason: "核验" });
    expect((await api.platformKeys.list()).find((k) => k.keyId === g.keyId)?.status).toBe("VERIFIED");
    await expect(api.credentials.enable(g.keyId, 1, { approvalRef: null, reason: "自己启用" })).rejects.toThrow(/双人/);
    expect((await api.credentials.list({ ownerType: "PLATFORM_OPEN" })).total).toBe(3);
  });
});

describe("P12 · 签名（契约 openapi-pay-open.yaml x-test-vectors）", () => {
  const vec = { method: "POST", query: [] as [string, string][], appId: "APP7Q2K9XW", timestamp: "1761465600000", nonce: "8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6", body: '{"outOrderNo":"M20261026001","amountMinor":12000,"currency":"TWD","payMethod":"LINEPAY"}' };

  it("P1：去前缀的 AppSecret；带前缀签出来的是契约里的错误值", async () => {
    expect(hmacKeyOf(TEST_VECTOR_APP.appSecret!)).toBe("Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay");
    const s = await signRequest({ ...vec, path: "/open/pay/v1/orders", appSecret: TEST_VECTOR_APP.appSecret! });
    expect(s.bodyHash).toBe("a5c9b0a01becc5121d926a7d702ff9e6d0d335367a53b9787e6e481d37bd9a35");
    expect(s.signature).toBe("nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=");
    expect(await hmacBase64(TEST_VECTOR_APP.appSecret!, s.stringToSign)).toBe("COTZmHskHe/MoBidbfalyUR4Qx96IXyeiRbFmEZboFA=");
  });

  it("P2 / P3 向量", async () => {
    const body = "{}";
    const sig = await hmacBase64(hmacKeyOf(TEST_VECTOR_APP.appSecret!), `1761465601000\n5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f\n${await sha256Hex(body)}`);
    expect(sig).toBe("umstQTPXokyyX02RxHKAeiNbQUc1VAAvHykNvWaInfQ=");
    expect(await verifyResponse(TEST_VECTOR_APP.appSecret!, { "x-timestamp": "1761465601000", "x-nonce": "5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f", "x-signature": sig }, body)).toMatchObject({ status: "VALID" });
    expect(await aesGcmEncryptField(TEST_VECTOR_APP.encryptKey!, "APP7Q2K9XW", "refundAccount.accountNo", "8123004567", "a1b2c3d4e5f60718293a4b5c")).toBe("obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=");
  });

  it("★ 调用模拟：前缀取 /open/pay/v1 时复现契约签名；默认按 UME 生产 /openapi/v1 签并出 curl（不含 AppSecret）", async () => {
    const input = { endpoint: ord, params: {}, body: vec.body, appId: vec.appId, scheme: "HMAC" as const, appSecret: TEST_VECTOR_APP.appSecret, timestamp: vec.timestamp, nonce: vec.nonce };
    expect((await buildSignedCall(input, { origin: "https://pay.example.com", prefix: "/open/pay/v1" })).signed.signature).toBe("nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=");
    const base = resolveOpenApiBase(undefined);
    expect(base).toEqual({ origin: "https://tpay.neargo.ai", prefix: "/openapi/v1", base: "https://tpay.neargo.ai/openapi/v1" });
    const prod = await buildSignedCall(input, base);
    expect(prod.signed.stringToSign.split("\n")[1]).toBe("/openapi/v1/orders");
    expect(prod.curl).toContain("curl -X POST 'https://tpay.neargo.ai/openapi/v1/orders'");
    expect(prod.curl).toContain(`X-Signature: ${prod.signed.signature}`);
    expect(prod.curl).not.toContain("Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay");
    expect(() => resolveOpenApiBase("http://tpay.neargo.ai/openapi/v1")).toThrow(/https/);
  });
});

describe("P12 · mock 网关（与守卫同顺序）", () => {
  const gw = () => createMockGateway("/openapi/v1");
  const base = { origin: "https://tpay.neargo.ai", prefix: "/openapi/v1" };
  const send = async (g: ReturnType<typeof gw>, c: Awaited<ReturnType<typeof buildSignedCall>>) => g({ method: c.signed.stringToSign.split("\n")[0], pathWithQuery: c.pathWithQuery, headers: c.signed.headers, body: c.payload });

  it("签名对 → 200 且 P2 有效；同 nonce 重发 70096；AppSecret 改一位 401", async () => {
    const g = gw();
    const c = await buildSignedCall({ endpoint: ping, params: {}, body: '{"hello":"near-pay"}', appId: TEST_VECTOR_APP.appId, scheme: "HMAC", appSecret: TEST_VECTOR_APP.appSecret }, base);
    const r = await send(g, c);
    expect(r.status).toBe(200);
    expect(JSON.parse(r.body).data.echo).toEqual({ hello: "near-pay" });
    expect((await verifyResponse(TEST_VECTOR_APP.appSecret!, r.headers, r.body)).status).toBe("VALID");
    expect(JSON.parse((await send(g, c)).body).code).toBe(70096);
    const wrong = await buildSignedCall({ endpoint: ping, params: {}, body: "{}", appId: TEST_VECTOR_APP.appId, scheme: "HMAC", appSecret: `${TEST_VECTOR_APP.appSecret!.slice(0, -1)}z` }, base);
    expect((await send(g, wrong)).status).toBe(401);
  });

  it("敏感字段传明文 70097；按 P3 加密后通过", async () => {
    const g = gw();
    const refund = OPEN_ENDPOINTS.find((e) => e.key === "refund")!;
    const plain = await buildSignedCall({ endpoint: refund, params: { payOrderNo: "P1" }, body: '{"outRefundNo":"R1","amountMinor":100,"refundAccount":{"accountNo":"8123004567"}}', appId: TEST_VECTOR_APP.appId, scheme: "HMAC", appSecret: TEST_VECTOR_APP.appSecret }, base);
    expect(JSON.parse((await send(g, plain)).body).code).toBe(70097);
    const enc = await buildSignedCall({ endpoint: refund, params: { payOrderNo: "P1" }, body: '{"outRefundNo":"R1","amountMinor":100}', appId: TEST_VECTOR_APP.appId, scheme: "HMAC", appSecret: TEST_VECTOR_APP.appSecret, fields: { "refundAccount.accountNo": "8123004567" }, encryptKey: TEST_VECTOR_APP.encryptKey, encryptSerial: "3" }, base);
    expect(enc.signed.headers["X-Encrypt-Serial"]).toBe("3");
    // 加密后过了 P3 守卫，进到业务：P1 这张单不存在 → 404（不再是字段错误）
    expect(JSON.parse((await send(g, enc)).body).code).toBe(10404);
  });

  it("RSA 应用：商家私钥签名 + X-Key-Serial 通过；序列号不符 401", async () => {
    const g = gw(); const pair = await generateRsaKeyPair("SIGN");
    g.registerRsaApp("APPRSA0001", "K1", pair.publicPem);
    const ok = await buildSignedCall({ endpoint: ping, params: {}, body: "{}", appId: "APPRSA0001", scheme: "RSA", privateKeyPem: pair.privatePem, keySerial: "K1" }, base);
    expect((await send(g, ok)).status).toBe(200);
    const bad = await buildSignedCall({ endpoint: ping, params: {}, body: "{}", appId: "APPRSA0001", scheme: "RSA", privateKeyPem: pair.privatePem, keySerial: "K2" }, base);
    expect((await send(g, bad)).status).toBe(401);
  });
});
