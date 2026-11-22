// SY13 调用模拟的纯函数部分：按 catalog 解析路径 → P3 加密敏感字段 → P1 签名 → 拼可直接执行的 curl。
// 页面（api-simulator.tsx）只管表单与展示；这里不碰任何存储，密钥只是入参。
import { resolvePath, type OpenEndpoint } from "./open-api-catalog";
import { aesGcmEncryptField, curlOf, rsaEncryptField, setPath, signRequest, signRequestRsa, type SignedRequest } from "./open-api-signer";

export type Scheme = "HMAC" | "RSA";
export interface SimInput {
  endpoint: OpenEndpoint; params: Record<string, string>; body: string; appId: string; scheme: Scheme;
  appSecret?: string; privateKeyPem?: string; keySerial?: string;
  /** 敏感字段明文（JSON 点路径 → 明文）；填了才加密写入请求体 */
  fields?: Record<string, string>;
  /** HMAC：EncryptKey 与其 KeySerial */
  encryptKey?: string; encryptSerial?: string;
  /** RSA：平台加密公钥（来自 /certificates） */
  platformEncrypt?: { serial: string; publicKeyPem: string } | null;
  timestamp?: string; nonce?: string;
}
export interface SimCall { signed: SignedRequest; pathWithQuery: string; url: string; payload: string; curl: string }

/** 请求体压缩成一行：签名对象就是实际发送的字节。GET 不带体；不是合法 JSON 抛 SyntaxError */
export function compactBody(endpoint: OpenEndpoint, body: string): string {
  if (endpoint.method === "GET" || !body.trim()) return "";
  return JSON.stringify(JSON.parse(body));
}

export async function buildSignedCall(input: SimInput, base: { origin: string; prefix: string }): Promise<SimCall> {
  const { endpoint } = input;
  const resolved = resolvePath(endpoint, input.params);
  const path = `${base.prefix}${resolved.path}`;
  let payload = compactBody(endpoint, input.body);
  let encryptSerial: string | null = null;
  const filled = (endpoint.sensitive ?? []).filter((p) => input.fields?.[p]?.trim());
  if (filled.length) {
    const root = (payload ? JSON.parse(payload) : {}) as Record<string, unknown>;
    for (const p of filled) {
      const plain = input.fields![p].trim();
      if (input.scheme === "RSA") {
        if (!input.platformEncrypt) throw new Error("没有平台加密公钥（ENCRYPT）：平台须先启用加密密钥");
        setPath(root, p, await rsaEncryptField(input.platformEncrypt.publicKeyPem, input.appId.trim(), p, plain));
      } else {
        setPath(root, p, await aesGcmEncryptField(input.encryptKey ?? "", input.appId.trim(), p, plain));
      }
    }
    payload = JSON.stringify(root);
    encryptSerial = input.scheme === "RSA" ? input.platformEncrypt!.serial : input.encryptSerial?.trim() || null;
  }
  const common = { method: endpoint.method, path, query: resolved.query, appId: input.appId.trim(), body: payload, timestamp: input.timestamp, nonce: input.nonce };
  const signed = input.scheme === "RSA"
    ? await signRequestRsa({ ...common, privateKeyPem: input.privateKeyPem ?? "", keySerial: input.keySerial ?? "" })
    : await signRequest({ ...common, appSecret: input.appSecret ?? "", keySerial: input.keySerial });
  if (encryptSerial) signed.headers["X-Encrypt-Serial"] = encryptSerial;
  const qs = new URLSearchParams(resolved.query).toString();
  const pathWithQuery = path + (qs ? `?${qs}` : "");
  const url = base.origin + pathWithQuery;
  return { signed, pathWithQuery, url, payload, curl: curlOf(url, endpoint.method, signed.headers, payload) };
}
