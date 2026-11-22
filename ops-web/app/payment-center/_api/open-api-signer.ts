// 开放 API 签名（near-pay docs/api/openapi-pay-open.yaml v1.2 info.x-signing / x-signing-rsa），与后端 SignatureCanonicalizer 逐字一致：
//   P1 待签串 = METHOD \n PATH \n 规范化查询串 \n X-App-Id \n X-Timestamp \n X-Nonce \n hex(SHA256(请求体))
//   P2 待签串 = X-Timestamp \n X-Nonce \n hex(SHA256(响应体))
// HMAC 密钥是 AppSecret 去掉 sk_live_ / sk_test_ 前缀后的部分；RSA 方案待签串相同，换成商家私钥 SHA256withRSA。
// 只用 WebCrypto，密钥只在调用方内存里，不落任何存储。照搬 near-pay ops-web/lib/open-api/signer.ts。
const encoder = new TextEncoder();
const PREFIXES = ["sk_live_", "sk_test_"];

export function hmacKeyOf(appSecret: string): string {
  const prefix = PREFIXES.find((p) => appSecret.startsWith(p));
  return prefix ? appSecret.slice(prefix.length) : appSecret;
}

/** RFC 3986：只保留 A-Z a-z 0-9 - _ . ~ 不编码 */
export function rfc3986(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

/** 参数名按字典序、同名按值字典序；名与值各自编码后用 & 连接 */
export function canonicalQuery(params: [string, string][]): string {
  return [...params].sort(([ak, av], [bk, bv]) => (ak < bk ? -1 : ak > bk ? 1 : av < bv ? -1 : av > bv ? 1 : 0))
    .map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join("&");
}

const hex = (buffer: ArrayBuffer) => [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
const base64 = (buffer: ArrayBuffer) => { let s = ""; for (const b of new Uint8Array(buffer)) s += String.fromCharCode(b); return btoa(s); };

export async function sha256Hex(body: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", encoder.encode(body)));
}

export async function hmacBase64(key: string, message: string): Promise<string> {
  const k = await crypto.subtle.importKey("raw", encoder.encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64(await crypto.subtle.sign("HMAC", k, encoder.encode(message)));
}

export function randomNonce(): string {
  const bytes = new Uint8Array(16); crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export interface SignInput { method: string; path: string; query: [string, string][]; appId: string; body: string; timestamp?: string; nonce?: string; keySerial?: string }
export interface SignedRequest { stringToSign: string; bodyHash: string; signature: string; headers: Record<string, string> }

function stringToSignOf(input: SignInput, timestamp: string, nonce: string, bodyHash: string): string {
  return [input.method.toUpperCase(), input.path, canonicalQuery(input.query), input.appId, timestamp, nonce, bodyHash].join("\n");
}

/** P1（HMAC）：X-Key-Serial 可选（AppSecret 版本） */
export async function signRequest(input: SignInput & { appSecret: string }): Promise<SignedRequest> {
  const timestamp = input.timestamp ?? String(Date.now()); const nonce = input.nonce ?? randomNonce();
  const bodyHash = await sha256Hex(input.body);
  const stringToSign = stringToSignOf(input, timestamp, nonce, bodyHash);
  const signature = await hmacBase64(hmacKeyOf(input.appSecret), stringToSign);
  const headers: Record<string, string> = { "X-App-Id": input.appId, "X-Timestamp": timestamp, "X-Nonce": nonce, "X-Signature": signature };
  if (input.keySerial?.trim()) headers["X-Key-Serial"] = input.keySerial.trim();
  return { stringToSign, bodyHash, signature, headers };
}

export type ResponseCheck = { status: "VALID" | "INVALID" | "UNSIGNED"; expected?: string };
/** P2（HMAC）：响应头 X-Timestamp / X-Nonce / X-Signature；AppId 不存在的 401 平台不签 */
export async function verifyResponse(appSecret: string, headers: Record<string, string | undefined>, body: string): Promise<ResponseCheck> {
  const ts = headers["x-timestamp"], nonce = headers["x-nonce"], signature = headers["x-signature"];
  if (!ts || !nonce || !signature) return { status: "UNSIGNED" };
  const expected = await hmacBase64(hmacKeyOf(appSecret), `${ts}\n${nonce}\n${await sha256Hex(body)}`);
  return { status: expected === signature ? "VALID" : "INVALID", expected };
}

/** P3（HMAC）：Base64(IV(12) ‖ 密文 ‖ Tag(16))，AES-256-GCM，密钥 = EncryptKey 的 UTF-8 字节，AAD = AppId|字段路径。ivHex 只供测试向量 */
export async function aesGcmEncryptField(encryptKey: string, appId: string, fieldPath: string, value: string, ivHex?: string): Promise<string> {
  const raw = encoder.encode(encryptKey);
  if (raw.length !== 32) throw new Error("EncryptKey 须为 32 字节（32 位字母数字）");
  const key = await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["encrypt"]);
  const iv = ivHex ? Uint8Array.from(ivHex.match(/../g)!.map((h) => parseInt(h, 16))) : crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(`${appId}|${fieldPath}`), tagLength: 128 }, key, encoder.encode(value)));
  const out = new Uint8Array(iv.length + ct.length); out.set(iv); out.set(ct, iv.length);
  return base64(out.buffer);
}

/** 把点路径（如 refundAccount.accountNo）写进 JSON 对象，缺的中间层自动补 */
export function setPath(target: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.split("."); let node = target;
  for (const p of parts.slice(0, -1)) { if (typeof node[p] !== "object" || node[p] === null) node[p] = {}; node = node[p] as Record<string, unknown>; }
  node[parts[parts.length - 1]] = value;
}

// ── RSA 方案（near-pay TDD-开放API密钥方案抽象与非对称实现 §2） ──
const RSA_SIGN = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" } as const;
const RSA_OAEP = { name: "RSA-OAEP", hash: "SHA-256" } as const;

export function pemToDer(pem: string): ArrayBuffer {
  const bin = atob(pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}
export function derToPem(der: ArrayBuffer, label: "PUBLIC KEY" | "PRIVATE KEY"): string {
  const lines = base64(der).match(/.{1,64}/g) ?? [];
  return `-----BEGIN ${label}-----\n${lines.join("\n")}\n-----END ${label}-----\n`;
}
/** 商家私钥只收 PKCS#8（-----BEGIN PRIVATE KEY-----）；openssl genpkey 默认即是 */
export async function rsaSignBase64(privatePem: string, message: string): Promise<string> {
  if (!privatePem.includes("-----BEGIN PRIVATE KEY-----")) throw new Error("私钥须为 PKCS#8 PEM（-----BEGIN PRIVATE KEY-----），可用 openssl pkcs8 -topk8 -nocrypt 转换");
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(privatePem), RSA_SIGN, false, ["sign"]);
  return base64(await crypto.subtle.sign(RSA_SIGN, key, encoder.encode(message)));
}
export async function rsaVerifyBase64(publicPem: string, message: string, signatureBase64: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey("spki", pemToDer(publicPem), RSA_SIGN, false, ["verify"]);
    const sig = Uint8Array.from(atob(signatureBase64), (c) => c.charCodeAt(0));
    return await crypto.subtle.verify(RSA_SIGN, key, sig, encoder.encode(message));
  } catch { return false; }
}
/** P3-RSA：Base64(RSA-OAEP-SHA256(平台加密公钥, AppId|字段路径|明文)) */
export async function rsaEncryptField(platformEncryptPem: string, appId: string, fieldPath: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("spki", pemToDer(platformEncryptPem), RSA_OAEP, false, ["encrypt"]);
  return base64(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, key, encoder.encode(`${appId}|${fieldPath}|${value}`)));
}
/** RSA-2048 测试密钥对（PKCS#8 私钥 + SPKI 公钥），只在内存里；仅 mock 演示用 */
export async function generateRsaKeyPair(usage: "SIGN" | "ENCRYPT" = "SIGN"): Promise<{ privatePem: string; publicPem: string }> {
  const algo = usage === "SIGN" ? RSA_SIGN : RSA_OAEP;
  const pair = await crypto.subtle.generateKey({ ...algo, modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) }, true,
    usage === "SIGN" ? ["sign", "verify"] : ["encrypt", "decrypt"]) as CryptoKeyPair;
  return {
    privatePem: derToPem(await crypto.subtle.exportKey("pkcs8", pair.privateKey), "PRIVATE KEY"),
    publicPem: derToPem(await crypto.subtle.exportKey("spki", pair.publicKey), "PUBLIC KEY"),
  };
}

/** P1-RSA：待签串同 HMAC；X-Key-Serial 必填（商家公钥序列号） */
export async function signRequestRsa(input: SignInput & { privateKeyPem: string; keySerial: string }): Promise<SignedRequest> {
  if (!input.keySerial?.trim()) throw new Error("RSA 方案必须填写 X-Key-Serial（商家公钥序列号）");
  const timestamp = input.timestamp ?? String(Date.now()); const nonce = input.nonce ?? randomNonce();
  const bodyHash = await sha256Hex(input.body);
  const stringToSign = stringToSignOf(input, timestamp, nonce, bodyHash);
  const signature = await rsaSignBase64(input.privateKeyPem, stringToSign);
  return { stringToSign, bodyHash, signature, headers: { "X-App-Id": input.appId, "X-Timestamp": timestamp, "X-Nonce": nonce, "X-Signature": signature, "X-Key-Serial": input.keySerial.trim() } };
}
/** P2-RSA：按响应头 X-Key-Serial 从平台公钥表取签名公钥验 */
export async function verifyResponseRsa(platformKeys: { serial: string; usage: string; publicKeyPem: string }[], headers: Record<string, string | undefined>, body: string): Promise<ResponseCheck> {
  const ts = headers["x-timestamp"], nonce = headers["x-nonce"], signature = headers["x-signature"], serial = headers["x-key-serial"];
  if (!ts || !nonce || !signature) return { status: "UNSIGNED" };
  const key = platformKeys.find((k) => k.usage === "SIGN" && k.serial === serial);
  if (!key) return { status: "INVALID", expected: serial ?? "" };
  return { status: (await rsaVerifyBase64(key.publicKeyPem, `${ts}\n${nonce}\n${await sha256Hex(body)}`, signature)) ? "VALID" : "INVALID" };
}

/** 可复制的 curl：只含已算好的签名头，不含 AppSecret / 私钥 */
export function curlOf(url: string, method: string, headers: Record<string, string>, body: string): string {
  const q = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;
  const lines = [`curl -X ${method.toUpperCase()} ${q(url)}`, ...Object.entries(headers).map(([k, v]) => `  -H ${q(`${k}: ${v}`)}`)];
  if (body) lines.push("  -H 'Content-Type: application/json'", `  --data-raw ${q(body)}`);
  return lines.join(" \\\n");
}
