// 开放 API 接入全流程（mock）：运营端从接入申请走到商户拿密钥调通，再到泄露应急锁定。
// 对照 2026-11-23 生产试点 ume早餐店 APP07071A89D8EC4F20 的实际步骤；mock 与生产的差别写在各步注释里：
// - 生产的「应用启用」「密钥启用」各要一张已批准的审批（OPEN_APP_ENABLE / CREDENTIAL_ENABLE）、执行人 ≠ 登记人 / 录入人；
//   mock 只有 admin 一个身份、审批不设闸，这两道门在这里只校验「启用人 ≠ 录入人」。
// - 门店授权由商户端（b-app）发起，mock 网关不校验门店。
import { describe, expect, it } from "vitest";
import { createMockPayCenterApi } from "./_api/mock";
import { createMockGateway, gatewayAppOf, TEST_VECTOR_APP, type MockApp } from "./_api/open-api-gateway";
import { openWebhook, type Delivery } from "./_api/open-api-payment-sim";
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

    // 5. 商户调用：建单、查单通过且响应签名可验；scope 外的退款 403；密钥错 401；正式环境不给 ping
    const created = await call("createOrder", appNo, issued.secret, {}, '{"outOrderNo":"O1","storeRef":"ST2001","amountMinor":100,"currency":"TWD","subject":"早餐"}');
    expect(created.status).toBe(200);
    const payOrderNo = JSON.parse(created.body).data.payOrderNo as string;
    const ok = await call("queryOrder", appNo, issued.secret, { payOrderNo });
    expect(ok.status).toBe(200);
    expect(JSON.parse(ok.body).data).toMatchObject({ outOrderNo: "O1", status: "PENDING" });
    expect((await verifyResponse(issued.secret, ok.headers, ok.body)).status).toBe("VALID");
    expect((await call("refund", appNo, issued.secret, { payOrderNo: "P1" }, '{"outRefundNo":"R1","amountMinor":100}')).status).toBe(403);
    expect((await call("queryOrder", appNo, `${issued.secret.slice(0, -1)}x`, { payOrderNo })).status).toBe(401);
    expect((await call("ping", appNo, issued.secret, {}, "{}")).status).toBe(403);

    // 6. 泄露应急：吊销全部密钥版本、日退款上限置 0 → 再调 401
    const locked = await api.apps.emergencyLock(appNo, "密钥疑似泄露");
    expect(locked.revokedVersions).toContain(`${issued.keyId}:v1`);
    expect(locked.refundLimitAfter).toBe(0);
    app = await appView(appNo);
    expect(app.credentialStatus).toBe("REVOKED");
    gateway.registerApp(gatewayAppOf(app, issued.secret));
    expect((await call("queryOrder", appNo, issued.secret, { payOrderNo })).status).toBe(401);
  });

  it("没配回调地址的应用可以直接启用（与 near-pay 一致：notifyUrl 为空不要求核验）", async () => {
    const api = createMockPayCenterApi();
    const d = await api.apps.create({ name: "无回调", purpose: null, ownerKind: "MERCHANT", merchantNo: "PM1001", environment: "LIVE", scopes: ["READ"], notifyUrl: null, callbackDomains: [], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, reason: "试点" } as never);
    const no = d.record.app.appNo;
    await api.apps.command(no, "enable", cmd(d.record.app.version, "启用"));
    expect((await api.apps.detail(no)).record.app.status).toBe("ACTIVE");
  });
});

describe("拿到密钥后的支付全链路（mock 第三方渠道 + 推送）", () => {
  const NOTIFY = "https://erp.ume.tw/pay/notify";
  /** 接入方服务器（mock）：收推送、按 eventId 幂等处理；statuses 依次决定回给平台的 HTTP 状态 */
  function merchantServer(appSecret: string, encryptKey: string | undefined, statuses: number[] = []) {
    const inbox: Delivery[] = []; const processed = new Map<string, Record<string, unknown>>();
    const deliver = async (d: Delivery) => {
      inbox.push(d);
      const evt = await openWebhook(appSecret, encryptKey, d.headers, d.body);
      if (!evt.valid) return 401;
      const status = statuses.shift() ?? 200;
      if (status === 200 && !processed.has(evt.eventId!)) processed.set(evt.eventId!, { eventType: evt.eventType, ...evt.data });
      return status;
    };
    return { inbox, processed, deliver };
  }
  const setup = (app: MockApp, statuses?: number[]) => {
    const server = merchantServer(app.appSecret!, app.encryptKey, statuses);
    const gateway = createMockGateway(BASE.prefix, [app], undefined, { deliver: server.deliver });
    const call = async (key: string, params: Record<string, string> = {}, body = "") => {
      const c = await buildSignedCall({ endpoint: ep(key), params, body, appId: app.appId, scheme: "HMAC", appSecret: app.appSecret }, BASE);
      const r = await gateway({ method: ep(key).method, pathWithQuery: c.pathWithQuery, headers: c.signed.headers, body: c.payload });
      expect((await verifyResponse(app.appSecret!, r.headers, r.body)).status).toBe("VALID"); // 每个响应都按 P2 签了
      return { status: r.status, ...(JSON.parse(r.body) as { code: number; data: Record<string, unknown> }) };
    };
    return { server, gateway, call };
  };
  const ORDER = '{"outOrderNo":"UME-20261123-001","storeRef":"ST2001","amountMinor":12000,"currency":"TWD","subject":"早餐套餐"}';

  it("建单 → 发起支付 → 渠道付款并回调平台 → 平台推送（P4 加密、P2 签名，失败重推）→ 接入方验签解密、幂等 → 查单为准", async () => {
    const app: MockApp = { ...TEST_VECTOR_APP, notifyUrl: NOTIFY };
    const { server, gateway, call } = setup(app, [500, 200]); // 第一次推送接入方回 500，重推才收下

    // 建单：幂等（同单号同内容回原单，内容不同 409），可按接入方单号查
    const created = await call("createOrder", {}, ORDER);
    expect(created).toMatchObject({ status: 200, code: 0, data: { outOrderNo: "UME-20261123-001", status: "PENDING", amountMinor: 12000, paidMinor: 0 } });
    const payOrderNo = created.data.payOrderNo as string;
    expect((await call("createOrder", {}, ORDER)).data.payOrderNo).toBe(payOrderNo);
    expect((await call("createOrder", {}, ORDER.replace("12000", "13000"))).status).toBe(409);
    expect((await call("queryByOut", { outOrderNo: "UME-20261123-001" })).data.payOrderNo).toBe(payOrderNo);

    // 发起支付：冻结渠道，给收银台地址
    expect((await call("startPayment", { payOrderNo }, '{"payMethod":"BITCOIN"}')).status).toBe(400);
    const started = await call("startPayment", { payOrderNo }, '{"payMethod":"LINEPAY"}');
    expect(started.data).toMatchObject({ payOrderNo, payChannel: "LINEPAY" });
    const payUrl = (started.data.payParams as { payUrl: string }).payUrl;
    expect(payUrl).toMatch(/^https:\/\/channel\.mock\/linepay\/checkout\//);
    expect((await call("queryOrder", { payOrderNo })).data.status).toBe("PAYING");

    // 渠道回调平台：伪造签名、金额不符都拒收，订单不动、不推送
    expect(await gateway.payments.channel.forge(payUrl)).toEqual({ accepted: false, reason: "渠道签名不符" });
    expect(await gateway.payments.channel.pay(payUrl, { amountMinor: 1 })).toEqual({ accepted: false, reason: "金额不符" });
    expect((await call("queryOrder", { payOrderNo })).data.status).toBe("PAYING");
    expect(server.inbox).toHaveLength(0);

    // 付款人确认 → 平台置成功 → 推送；接入方第一次回 500 → 待重推
    expect(await gateway.payments.channel.pay(payUrl)).toEqual({ accepted: true });
    expect(server.inbox).toHaveLength(1);
    expect(gateway.payments.notifies[0]).toMatchObject({ eventType: "PAYMENT_SUCCEEDED", attempts: 1, delivered: false, lastStatus: 500 });
    await gateway.payments.redeliver();
    expect(gateway.payments.notifies[0]).toMatchObject({ attempts: 2, delivered: true, lastStatus: 200 });

    // 推送报文：头齐全、resource 加密（不带明文 data）、重推同 eventId 换 nonce
    const [first, retry] = server.inbox;
    expect(first.url).toBe(NOTIFY);
    expect(first.headers).toMatchObject({ "x-app-id": app.appId, "x-event-id": gateway.payments.notifies[0].eventId });
    const evtBody = JSON.parse(first.body);
    expect(evtBody.resource).toMatchObject({ algorithm: "AEAD_AES_256_GCM", associatedData: `PAYMENT_SUCCEEDED|${evtBody.eventId}` });
    expect(evtBody.data).toBeUndefined();
    expect(first.body).not.toContain("12000");
    expect(retry.headers["x-event-id"]).toBe(first.headers["x-event-id"]);
    expect(retry.headers["x-nonce"]).not.toBe(first.headers["x-nonce"]);

    // 接入方：验签 + 解密拿到订单；篡改体、错 EncryptKey 都过不了
    const opened = await openWebhook(app.appSecret!, app.encryptKey, retry.headers, retry.body);
    expect(opened).toMatchObject({ valid: true, eventType: "PAYMENT_SUCCEEDED", data: { payOrderNo, status: "SUCCEEDED", paidMinor: 12000, payChannel: "LINEPAY" } });
    expect((await openWebhook(app.appSecret!, app.encryptKey, retry.headers, retry.body.replace("PAYMENT_SUCCEEDED", "PAYMENT_CLOSED"))).valid).toBe(false);
    await expect(openWebhook(app.appSecret!, "x".repeat(32), retry.headers, retry.body)).rejects.toThrow();
    expect([...server.processed.values()]).toEqual([expect.objectContaining({ eventType: "PAYMENT_SUCCEEDED", status: "SUCCEEDED" })]);

    // 以查单为准；渠道重复回调幂等、不再推送；已付款不能关单
    expect((await call("queryOrder", { payOrderNo })).data).toMatchObject({ status: "SUCCEEDED", paidMinor: 12000 });
    expect(await gateway.payments.channel.pay(payUrl)).toEqual({ accepted: true });
    expect(gateway.payments.notifies).toHaveLength(1);
    expect((await call("closeOrder", { payOrderNo }, "{}")).status).toBe(409);
  });

  it("没付款就关单：推 PAYMENT_CLOSED，之后不能再发起支付；接入方还没有 EncryptKey 时推送带明文 data", async () => {
    const app: MockApp = { ...TEST_VECTOR_APP, encryptKey: undefined, notifyUrl: NOTIFY };
    const { server, call } = setup(app);
    const payOrderNo = (await call("createOrder", {}, ORDER)).data.payOrderNo as string;
    expect((await call("closeOrder", { payOrderNo }, '{"reason":"顾客取消"}')).data).toMatchObject({ status: "CLOSED" });
    expect((await call("startPayment", { payOrderNo }, '{"payMethod":"LINEPAY"}')).status).toBe(409);
    expect(server.inbox).toHaveLength(1);
    const body = JSON.parse(server.inbox[0].body);
    expect(body).toMatchObject({ eventType: "PAYMENT_CLOSED", data: { payOrderNo, status: "CLOSED" } });
    expect(body.resource).toBeUndefined();
    expect(server.processed.size).toBe(1);
  });

  it("没配回调地址：不推送，接入方靠查单；别的应用查不到这张单", async () => {
    const app: MockApp = { ...TEST_VECTOR_APP, notifyUrl: null };
    const other: MockApp = { ...TEST_VECTOR_APP, appId: "APPOTHER01" };
    const server = merchantServer(app.appSecret!, app.encryptKey);
    const gateway = createMockGateway(BASE.prefix, [app, other], undefined, { deliver: server.deliver });
    const send = async (appId: string, key: string, params: Record<string, string>, body = "") => {
      const c = await buildSignedCall({ endpoint: ep(key), params, body, appId, scheme: "HMAC", appSecret: app.appSecret }, BASE);
      const r = await gateway({ method: ep(key).method, pathWithQuery: c.pathWithQuery, headers: c.signed.headers, body: c.payload });
      return { status: r.status, data: JSON.parse(r.body).data };
    };
    const payOrderNo = (await send(app.appId, "createOrder", {}, ORDER)).data.payOrderNo;
    const payUrl = (await send(app.appId, "startPayment", { payOrderNo }, '{"payMethod":"CARD"}')).data.payParams.payUrl;
    expect(payUrl).toContain("/newebpay/");
    expect(await gateway.payments.channel.pay(payUrl)).toEqual({ accepted: true });
    expect(server.inbox).toHaveLength(0);
    expect((await send(app.appId, "queryOrder", { payOrderNo })).data.status).toBe("SUCCEEDED");
    expect((await send(other.appId, "queryOrder", { payOrderNo })).status).toBe(404);
  });
});
