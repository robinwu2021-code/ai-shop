"use client";

// SY13 调用模拟（P12）：模拟外部系统按 P1 签名调用开放 API，生成可直接执行的 curl。
// - mock：发给浏览器内的 mock 网关（与守卫同顺序验签），并按 P2 校验响应签名。
// - 真实实例（UME 生产，无沙箱）：只在浏览器内签名、出 curl，不从运营端发请求；RSA 私钥不在运营端输入。
// AppSecret / EncryptKey / 演示私钥只在这个组件的内存里：不进草稿、不进查询缓存、不进 curl、不发给运营接口。
import * as React from "react";
import { Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { DrawerFooter } from "@/components/ui/drawer";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { useCopy } from "@/lib/use-copy";
import { resolveOpenApiBase, type OpenEndpoint } from "@/app/payment-center/_api/open-api-catalog";
import { buildSignedCall, type Scheme, type SimCall } from "@/app/payment-center/_api/open-api-sim";
import { generateRsaKeyPair, verifyResponse, verifyResponseRsa, type ResponseCheck } from "@/app/payment-center/_api/open-api-signer";
import { createMockGateway, TEST_VECTOR_APP, type MockGateway, type SimResponse } from "@/app/payment-center/_api/open-api-gateway";
import { usePc } from "./shared";
import { P12_COPY } from "./p12-copy";

const PRE = "max-h-72 overflow-auto whitespace-pre-wrap break-all rounded-field bg-muted p-3 font-mono txt-caption";
const ROWS = ["METHOD", "PATH", "QUERY", "X-App-Id", "X-Timestamp", "X-Nonce", "SHA256(body)"];
const pretty = (body: string) => { try { return JSON.stringify(JSON.parse(body), null, 2); } catch { return body; } };
const sampleOf = (e: OpenEndpoint) => (e.sampleBody ? JSON.stringify(e.sampleBody, null, 2) : "");
type PlatformKey = { serial: string; usage: string; publicKeyPem: string };

// mock 网关全页一份：nonce 防重放与登记的 RSA 测试应用在关抽屉后仍在，像真网关一样
let gatewaySingleton: MockGateway | null = null;
const mockGateway = (prefix: string) => (gatewaySingleton ??= createMockGateway(prefix));

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{label}</span>{children}</label>;
}

export function ApiSimulator({ endpoint, mock, onDone }: { endpoint: OpenEndpoint; mock: boolean; onDone: () => void }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const { locale } = useI18n();
  const base = React.useMemo(() => resolveOpenApiBase(), []);
  const gateway = mock ? mockGateway(base.prefix) : null;
  const [scheme, setScheme] = React.useState<Scheme>("HMAC");
  const [appId, setAppId] = React.useState(mock ? TEST_VECTOR_APP.appId : "");
  const [appSecret, setAppSecret] = React.useState("");
  const [privateKeyPem, setPrivateKeyPem] = React.useState("");
  const [keySerial, setKeySerial] = React.useState("");
  const [params, setParams] = React.useState<Record<string, string>>(() => Object.fromEntries(endpoint.params.map((p) => [p.name, p.example])));
  const [body, setBody] = React.useState(() => sampleOf(endpoint));
  const [fields, setFields] = React.useState<Record<string, string>>({});
  const [encryptKey, setEncryptKey] = React.useState("");
  const [encryptSerial, setEncryptSerial] = React.useState("");
  const [platformKeys, setPlatformKeys] = React.useState<PlatformKey[]>([]);
  const [call, setCall] = React.useState<SimCall | null>(null);
  const [response, setResponse] = React.useState<SimResponse | null>(null);
  const [check, setCheck] = React.useState<ResponseCheck | null>(null);
  const [error, setError] = React.useState("");
  const [busy, setBusy] = React.useState(false);

  const rsaAllowed = scheme === "HMAC" || mock;
  const filledFields = (endpoint.sensitive ?? []).some((p) => fields[p]?.trim());
  const ready = !!appId.trim() && endpoint.params.every((p) => !p.required || params[p.name]?.trim())
    && (scheme === "RSA" ? mock && !!privateKeyPem.trim() && !!keySerial.trim() : !!appSecret)
    && (!filledFields || scheme === "RSA" || encryptKey.length === 32);

  /** RSA（仅 mock）：签名调一次 /certificates 拿平台公钥（签名与加密），缓存到本组件 */
  async function certificates(): Promise<PlatformKey[]> {
    if (!gateway) return platformKeys;
    const cert = await buildSignedCall({ endpoint: { ...endpoint, key: "certificates", method: "GET", path: "/certificates", params: [], sensitive: [] }, params: {}, body: "", appId, scheme, privateKeyPem, keySerial }, base);
    const r = await gateway({ method: "GET", pathWithQuery: cert.pathWithQuery, headers: cert.signed.headers, body: "" });
    const fresh = (JSON.parse(r.body).data ?? []) as PlatformKey[];
    setPlatformKeys(fresh); return fresh;
  }

  async function go(send: boolean, reuseNonce = false) {
    setBusy(true); setError(""); setResponse(null); setCheck(null);
    try {
      const keys = scheme === "RSA" && filledFields ? (platformKeys.some((k) => k.usage === "ENCRYPT") ? platformKeys : await certificates()) : platformKeys;
      const enc = keys.find((k) => k.usage === "ENCRYPT") ?? null;
      const built = await buildSignedCall({ endpoint, params, body, appId, scheme, appSecret, privateKeyPem, keySerial, fields, encryptKey, encryptSerial, platformEncrypt: enc, nonce: reuseNonce ? call?.signed.headers["X-Nonce"] : undefined }, base);
      setCall(built);
      if (!send || !gateway) return;
      const res = await gateway({ method: endpoint.method, pathWithQuery: built.pathWithQuery, headers: built.signed.headers, body: built.payload });
      setResponse(res);
      if (scheme === "RSA") {
        const sk = keys.some((k) => k.serial === res.headers["x-key-serial"]) ? keys : endpoint.key === "certificates" ? ((JSON.parse(res.body).data ?? []) as PlatformKey[]) : await certificates();
        setCheck(await verifyResponseRsa(sk, res.headers, res.body));
      } else {
        setCheck(await verifyResponse(appSecret, res.headers, res.body));
      }
    } catch (e) {
      setError(e instanceof SyntaxError ? m.badJson : e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  async function mockRsaApp() {
    if (!gateway) return;
    const pair = await generateRsaKeyPair("SIGN"); const serial = "K2026010100000001";
    gateway.registerRsaApp("APPRSA0001", serial, pair.publicPem);
    setScheme("RSA"); setAppId("APPRSA0001"); setKeySerial(serial); setPrivateKeyPem(pair.privatePem); setPlatformKeys([]);
  }

  const envelope = (() => { try { return response ? JSON.parse(response.body) as { code?: number } : null; } catch { return null; } })();
  const L = (v: { zh: string; en: string }) => (locale === "en" ? v.en : v.zh);
  return (
    <>
      <div className="space-y-4" data-simulator={endpoint.key}>
        <Notice tone={mock ? "info" : "warning"}>{mock ? m.simMockNote : m.simProdNote}</Notice>
        <p className="font-mono txt-caption">{endpoint.method} {base.base}{endpoint.path}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="X-App-Id"><Input className="w-full" value={appId} onChange={(e) => setAppId(e.target.value)} placeholder="APP…" /></Field>
          <Field label={m.scheme}>
            <Select className="w-full" value={scheme} onChange={(e) => { setScheme(e.target.value as Scheme); setCall(null); setResponse(null); setCheck(null); }}>
              <option value="HMAC">{m.signType_HMAC}</option>
              <option value="RSA">{m.signType_RSA}</option>
            </Select>
          </Field>
          {scheme === "HMAC"
            ? <Field label={m.appSecret}><Input className="w-full" type="password" autoComplete="off" value={appSecret} onChange={(e) => setAppSecret(e.target.value)} placeholder="sk_live_… / sk_test_…" /></Field>
            : <div />}
          <Field label={scheme === "RSA" ? m.keySerialRsa : m.keySerialHmac}><Input className="w-full" value={keySerial} onChange={(e) => setKeySerial(e.target.value)} /></Field>
          {endpoint.params.map((p) => (
            <Field key={p.name} label={`${p.name}${p.required ? " *" : ""} (${p.in})`}>
              <Input className="w-full" value={params[p.name] ?? ""} onChange={(e) => setParams((v) => ({ ...v, [p.name]: e.target.value }))} />
            </Field>
          ))}
        </div>
        {scheme === "RSA" && !rsaAllowed && <Notice tone="warning">{m.rsaProdNote}</Notice>}
        {scheme === "RSA" && mock && (
          <>
            <Field label={m.privateKey}><Textarea rows={4} className="w-full font-mono" value={privateKeyPem} onChange={setPrivateKeyPem} /></Field>
            <Button size="sm" variant="ghost" onClick={() => void mockRsaApp()}>{m.mockRsaApp}</Button>
          </>
        )}
        {(endpoint.sensitive?.length ?? 0) > 0 && rsaAllowed && (
          <fieldset className="space-y-2 rounded-field bg-muted/40 p-3">
            <legend className="px-1 txt-label">{m.sensitiveLegend}</legend>
            {scheme === "HMAC" ? (
              <div className="grid gap-2 sm:grid-cols-2">
                <Field label={m.encryptKey}><Input className="w-full" type="password" autoComplete="off" value={encryptKey} onChange={(e) => setEncryptKey(e.target.value)} /></Field>
                <Field label={m.encryptSerial}><Input className="w-full" value={encryptSerial} onChange={(e) => setEncryptSerial(e.target.value)} /></Field>
              </div>
            ) : <p className="txt-caption text-muted-foreground">{m.rsaEncryptNote}</p>}
            <div className="grid gap-2 sm:grid-cols-3">
              {endpoint.sensitive!.map((p) => <Field key={p} label={p}><Input className="w-full" value={fields[p] ?? ""} onChange={(e) => setFields((v) => ({ ...v, [p]: e.target.value }))} /></Field>)}
            </div>
            {scheme === "HMAC" && filledFields && encryptKey.length !== 32 && <p className="txt-caption text-destructive">{m.fieldsNeedKey}</p>}
          </fieldset>
        )}
        {endpoint.method === "POST" && <Field label={m.requestBody}><Textarea rows={6} className="w-full font-mono" value={body} onChange={setBody} /></Field>}
        <p className="txt-caption text-muted-foreground">{L(endpoint.summary)} · scope <span className="font-mono">{endpoint.scope ?? m.scopeNone}</span></p>
        {error && <p role="alert" className="txt-caption text-destructive">{error}</p>}

        {call && (
          <section className="space-y-3">
            <div><p className="mb-1 txt-label text-muted-foreground">{m.stringToSignLabel}</p>
              <pre className={PRE} data-string-to-sign>{call.signed.stringToSign.split("\n").map((l, i) => `${ROWS[i].padEnd(12)} │ ${l}`).join("\n")}</pre></div>
            <div><p className="mb-1 txt-label text-muted-foreground">{m.headersLabel}</p>
              <pre className={PRE}>{Object.entries(call.signed.headers).map(([k, v]) => `${k}: ${v}`).join("\n")}</pre></div>
            <div><p className="mb-1 txt-label text-muted-foreground">{m.curlLabel}</p>
              <pre className={PRE} data-curl>{call.curl}</pre>
              <Button size="sm" variant="ghost" className="mt-1" onClick={() => { void navigator.clipboard?.writeText(call.curl).then(() => notify.success(c.done), () => {}); }}><Copy className="size-3.5" /> {m.copyCurl}</Button></div>
          </section>
        )}
        {response && (
          <section className="space-y-2" data-sim-status={response.status}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="txt-label">{m.response}</span>
              <Badge tone={response.status < 300 ? "success" : response.status < 500 ? "warning" : "danger"}>HTTP {response.status}</Badge>
              {envelope && <Badge tone={envelope.code === 0 ? "success" : "danger"}>code {String(envelope.code)}</Badge>}
              <Badge tone="muted">{response.durationMs} ms · mock</Badge>
              {check && <Badge tone={check.status === "VALID" ? "success" : check.status === "UNSIGNED" ? "muted" : "danger"}>{check.status === "VALID" ? m.respValid : check.status === "UNSIGNED" ? m.respUnsigned : m.respInvalid}</Badge>}
            </div>
            <pre className={PRE}>{pretty(response.body)}</pre>
          </section>
        )}
      </div>
      <DrawerFooter>
        <Button variant="outline" onClick={onDone}>{c.done}</Button>
        {mock && call && <Button variant="ghost" loading={busy} onClick={() => void go(true, true)}>{m.resendSameNonce}</Button>}
        <Button variant={mock ? "outline" : "default"} loading={busy} disabled={!ready} onClick={() => void go(false)}>{m.sign}</Button>
        {mock && <Button loading={busy} disabled={!ready} onClick={() => void go(true)}>{m.signAndSend}</Button>}
      </DrawerFooter>
    </>
  );
}
