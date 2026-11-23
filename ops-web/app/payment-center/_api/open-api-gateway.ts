// SY13 调用模拟的 mock 网关：按与 near-pay 开放 API 守卫相同的顺序校验（应用 → 时间窗 → 验签 → nonce → scope → 敏感字段），
// 成功回 catalog 里的样例并按 P2 签名。只在 mock 模式用；真实实例（生产无沙箱）运营端只做签名预览，不发请求。
// 照搬 near-pay ops-web/lib/open-api/simulator.ts（createMockGateway），路径按对外前缀（/openapi/v1）匹配。
import { OPEN_ENDPOINTS, type OpenEndpoint } from "./open-api-catalog";
import { canonicalQuery, derToPem, generateRsaKeyPair, hmacBase64, hmacKeyOf, randomNonce, rsaSignBase64, rsaVerifyBase64, sha256Hex } from "./open-api-signer";

export interface SimRequest { method: string; pathWithQuery: string; headers: Record<string, string>; body: string }
export interface SimResponse { status: number; headers: Record<string, string>; body: string; durationMs: number }

export interface MockApp { appId: string; appSecret?: string; encryptKey?: string; rsa?: { serial: string; publicPem: string }; environment: "SANDBOX" | "LIVE"; scopes: string[]; status: "ACTIVE" | "SUSPENDED" }
export type MockGateway = ((req: SimRequest) => Promise<SimResponse>) & { registerRsaApp(appId: string, serial: string, publicPem: string): void; registerApp(app: MockApp): void };

/** 接入应用 scopes（READ / COLLECT / REFUND）→ 网关鉴权码，与 near-pay OpenAppModels.SCOPE_OF 一致 */
export const SCOPE_OF: Record<string, string> = { READ: "pay:read", COLLECT: "pay:create", REFUND: "pay:refund" };

/**
 * 运营端「接入应用」的状态 → 网关能认的应用：应用启用且密钥已启用才放行（near-pay 守卫：应用非 ACTIVE 或无有效密钥一律 401）。
 * appSecret 只来自签发那一次的响应 —— 平台不存明文，商户拿着它来调。
 */
export function gatewayAppOf(app: { appNo: string; environment: string; scopes: string[]; status: string; credentialStatus: string }, appSecret: string, encryptKey?: string): MockApp {
  return { appId: app.appNo, appSecret, encryptKey, environment: app.environment === "LIVE" ? "LIVE" : "SANDBOX", scopes: app.scopes.map((x) => SCOPE_OF[x]).filter(Boolean),
    status: app.status === "ACTIVE" && app.credentialStatus === "ACTIVE" ? "ACTIVE" : "SUSPENDED" };
}

/** 契约测试向量里的沙箱应用（openapi-pay-open.yaml info.x-test-vectors）：mock 网关据此真实验签 */
export const TEST_VECTOR_APP: MockApp = { appId: "APP7Q2K9XW", appSecret: "sk_test_Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay", encryptKey: "Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx", environment: "SANDBOX", scopes: ["pay:create", "pay:read", "pay:refund"], status: "ACTIVE" };
export const MOCK_PLATFORM_SIGN_SERIAL = "P2026010100000001";
const ENCRYPT_SERIAL = "Q2026010100000001";

function match(prefix: string, method: string, pathname: string): OpenEndpoint | null {
  if (!pathname.startsWith(`${prefix}/`)) return null;
  const rel = pathname.slice(prefix.length);
  return OPEN_ENDPOINTS.find((e) => e.method === method && new RegExp(`^${e.path.replace(/\{\w+\}/g, "[^/]+")}$`).test(rel)) ?? null;
}

export function createMockGateway(prefix: string, initial: MockApp[] = [TEST_VECTOR_APP], now: () => number = () => Date.now()): MockGateway {
  const apps = [...initial]; const seen = new Map<string, number>(); let seq = 0;
  let platform: Promise<{ privatePem: string; publicPem: string }> | null = null;
  const platformKey = () => (platform ??= generateRsaKeyPair("SIGN"));
  let encrypt: Promise<CryptoKeyPair> | null = null;
  const encryptPair = () => (encrypt ??= crypto.subtle.generateKey({ name: "RSA-OAEP", hash: "SHA-256", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) }, true, ["encrypt", "decrypt"]) as Promise<CryptoKeyPair>);
  const te = new TextEncoder(), td = new TextDecoder();

  /** P3：明文 70097；解不开 / 绑定不符 70098 */
  async function checkFields(app: MockApp, paths: string[], body: string): Promise<number | null> {
    let root: Record<string, unknown>; try { root = JSON.parse(body); } catch { return null; }
    for (const path of paths) {
      const parts = path.split("."); let node: unknown = root;
      for (const part of parts.slice(0, -1)) node = (node as Record<string, unknown> | undefined)?.[part];
      const value = (node as Record<string, unknown> | undefined)?.[parts[parts.length - 1]];
      if (value === undefined || value === null) continue;
      let packed: Uint8Array<ArrayBuffer>;
      try { packed = Uint8Array.from(atob(String(value)), (ch) => ch.charCodeAt(0)); } catch { return 70097; }
      if (packed.length < 28) return 70097;
      try {
        if (app.rsa) {
          const plain = td.decode(await crypto.subtle.decrypt({ name: "RSA-OAEP" }, (await encryptPair()).privateKey, packed));
          if (!plain.startsWith(`${app.appId}|${path}|`)) return 70098;
        } else {
          if (!app.encryptKey) return 70098;
          const key = await crypto.subtle.importKey("raw", te.encode(app.encryptKey), { name: "AES-GCM" }, false, ["decrypt"]);
          await crypto.subtle.decrypt({ name: "AES-GCM", iv: packed.slice(0, 12), additionalData: te.encode(`${app.appId}|${path}`), tagLength: 128 }, key, packed.slice(12));
        }
      } catch { return 70098; }
    }
    return null;
  }

  const gateway = async (req: SimRequest): Promise<SimResponse> => {
    const started = Date.now();
    const h = Object.fromEntries(Object.entries(req.headers).map(([k, v]) => [k.toLowerCase(), v]));
    const requestId = `mock-${now()}-${++seq}`;
    const url = new URL(req.pathWithQuery, "http://mock.invalid");
    const app = apps.find((a) => a.appId === h["x-app-id"]);
    const reply = async (status: number, code: number, message: string, data: unknown, sign: boolean): Promise<SimResponse> => {
      const body = JSON.stringify({ code, message, data, requestId }); const headers: Record<string, string> = { "content-type": "application/json" };
      if (sign && app) {
        const ts = String(now()), nonce = randomNonce(), toSign = `${ts}\n${nonce}\n${await sha256Hex(body)}`;
        Object.assign(headers, app.rsa
          ? { "x-timestamp": ts, "x-nonce": nonce, "x-key-serial": MOCK_PLATFORM_SIGN_SERIAL, "x-signature": await rsaSignBase64((await platformKey()).privatePem, toSign) }
          : { "x-timestamp": ts, "x-nonce": nonce, "x-key-serial": "1", "x-signature": await hmacBase64(hmacKeyOf(app.appSecret ?? ""), toSign) });
      }
      return { status, headers, body, durationMs: Date.now() - started };
    };
    if (!h["x-app-id"] || !h["x-timestamp"] || !h["x-nonce"] || !h["x-signature"]) return reply(401, 10401, "err.unauthorized", null, false);
    if (!app || app.status !== "ACTIVE") return reply(401, 10401, "err.unauthorized", null, false);
    const ts = Number(h["x-timestamp"]);
    if (!Number.isSafeInteger(ts) || Math.abs(now() - ts) > 300_000) return reply(401, 70095, "err.pay.open.timestamp_expired", null, true);
    if (h["x-nonce"].length < 8 || h["x-nonce"].length > 64) return reply(401, 10401, "err.unauthorized", null, true);
    const toSign = [req.method.toUpperCase(), url.pathname, canonicalQuery([...url.searchParams.entries()]), app.appId, h["x-timestamp"], h["x-nonce"], await sha256Hex(req.body)].join("\n");
    const valid = app.rsa
      ? h["x-key-serial"] === app.rsa.serial && await rsaVerifyBase64(app.rsa.publicPem, toSign, h["x-signature"])
      : (await hmacBase64(hmacKeyOf(app.appSecret ?? ""), toSign)) === h["x-signature"];
    if (!valid) return reply(401, 10401, "err.unauthorized", null, true);
    const nonceKey = `${app.appId}:${h["x-nonce"]}`;
    for (const [k, at] of seen) if (now() - at > 600_000) seen.delete(k);
    if (seen.has(nonceKey)) return reply(401, 70096, "err.pay.open.replayed", null, true);
    seen.set(nonceKey, now());
    const hit = match(prefix, req.method.toUpperCase(), url.pathname);
    if (!hit) return reply(403, 10403, "err.forbidden", null, true);
    if (hit.key === "ping") {
      if (app.environment !== "SANDBOX") return reply(403, 10403, "err.forbidden", null, true);
      let echo: unknown = {}; try { echo = req.body ? JSON.parse(req.body) : {}; } catch { return reply(400, 10400, "err.bad_request", null, true); }
      return reply(200, 0, "OK", { appNo: app.appId, environment: app.environment, scopes: app.scopes, keyVersion: 1, serverTime: now(), echo }, true);
    }
    if (hit.key === "certificates") return reply(200, 0, "OK", [
      { serial: MOCK_PLATFORM_SIGN_SERIAL, usage: "SIGN", algorithm: "RSA_SHA256", status: "ACTIVE", fingerprint: "mock", publicKeyPem: (await platformKey()).publicPem, activatedAt: now(), overlapUntil: null },
      { serial: ENCRYPT_SERIAL, usage: "ENCRYPT", algorithm: "RSA_OAEP_SHA256", status: "ACTIVE", fingerprint: "mock", publicKeyPem: derToPem(await crypto.subtle.exportKey("spki", (await encryptPair()).publicKey), "PUBLIC KEY"), activatedAt: now(), overlapUntil: null }], true);
    if (!hit.scope || !app.scopes.includes(hit.scope)) return reply(403, 10403, "err.forbidden", null, true);
    if (req.body) { try { JSON.parse(req.body); } catch { return reply(400, 10400, "err.bad_request", null, true); } }
    const fieldError = hit.sensitive ? await checkFields(app, hit.sensitive, req.body) : null;
    if (fieldError) return reply(400, fieldError, fieldError === 70097 ? "err.pay.open.field_encrypt_required" : "err.pay.open.field_decrypt_failed", null, true);
    return reply(200, 0, "OK", hit.sampleResponse, true);
  };
  return Object.assign(gateway, {
    registerRsaApp(appId: string, serial: string, publicPem: string) {
      const i = apps.findIndex((a) => a.appId === appId);
      const app: MockApp = { appId, rsa: { serial, publicPem }, environment: "SANDBOX", scopes: ["pay:create", "pay:read", "pay:refund"], status: "ACTIVE" };
      if (i >= 0) apps[i] = app; else apps.push(app);
    },
    registerApp(app: MockApp) {
      const i = apps.findIndex((a) => a.appId === app.appId);
      if (i >= 0) apps[i] = app; else apps.push(app);
    },
  });
}
