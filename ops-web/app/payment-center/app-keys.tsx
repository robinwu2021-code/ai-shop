"use client";

// 接入应用的签名密钥小件（P12，near-pay OpsAppKeyController）：RSA 商家公钥上传（持有证明）、近 7 天密钥用量。
// 运营全程只经手公钥；mock 下可一键生成演示密钥对（私钥只在这个抽屉的内存里，关掉即丢）。
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { DrawerFooter } from "@/components/ui/drawer";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import type { AppKeyChallenge, AppRow, KeyUsageRow } from "@/app/payment-center/_api/types";
import { generateRsaKeyPair, rsaSignBase64 } from "@/app/payment-center/_api/open-api-signer";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, MiniTable, usePc } from "./shared";
import { P12_COPY } from "./p12-copy";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const PRE = "max-h-56 overflow-auto whitespace-pre-wrap break-all rounded-field bg-muted p-3 font-mono txt-caption";
const copyText = (text: string, ok: string) => { void navigator.clipboard?.writeText(text).then(() => notify.success(ok), () => {}); };

/** 交给商家执行的命令：本地生成密钥对 → 对挑战签名 → 把 TS、签名与公钥交回运营 */
export function merchantCommands(ch: AppKeyChallenge): string {
  return [
    "# 1. Generate a key pair locally (never send the private key to anyone)",
    "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out app_private.pem",
    "openssl pkey -in app_private.pem -pubout -out app_public.pem",
    "# 2. Within 5 minutes sign the challenge; send TS, the signature and app_public.pem to ops",
    "TS=$(($(date +%s)*1000)); echo \"TS=$TS\"",
    `printf '%s' "${ch.stringToSignPrefix}$TS" | openssl dgst -sha256 -sign app_private.pem | base64 | tr -d '\\n'; echo`,
  ].join("\n");
}

export function PublicKeyUpload({ api, app, run, onDone }: { api: PayCenterApi; app: AppRow; run: Run; onDone: () => void }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const [challenge, setChallenge] = React.useState<AppKeyChallenge | null>(null);
  const [form, setForm] = React.useState({ publicKeyPem: "", timestamp: "", signature: "", reason: "" });
  const [demoPrivate, setDemoPrivate] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [added, setAdded] = React.useState<string | null>(null);
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const go = async (fn: () => Promise<void>) => { setBusy(true); try { await fn(); } catch { /* useRun 已提示 */ } finally { setBusy(false); } };

  const issue = () => go(async () => { const ch = await run(() => api.apps.keyChallenge(app.appNo)); setChallenge(ch); setForm((f) => ({ ...f, timestamp: "", signature: "" })); });
  const upload = () => go(async () => {
    const r = await run(() => api.apps.uploadPublicKey(app.appNo, { ...form, publicKeyPem: form.publicKeyPem.trim(), challenge: challenge!.challenge, reason: form.reason.trim() }));
    setAdded(r.version.externalKeyId ?? `v${r.version.versionIndex}`); setChallenge(null); setDemoPrivate("");
  });
  const demo = () => go(async () => {
    const pair = await generateRsaKeyPair("SIGN"); const ts = String(Date.now());
    setForm((f) => ({ ...f, publicKeyPem: pair.publicPem, timestamp: ts, signature: "", reason: f.reason || "mock demo key" }));
    const sig = await rsaSignBase64(pair.privatePem, challenge!.stringToSignPrefix + ts);
    setForm((f) => ({ ...f, signature: sig })); setDemoPrivate(pair.privatePem);
  });

  if (app.status !== "ACTIVE") return <><Notice tone="info">{m.publicKeyInactive}</Notice><DrawerFooter><Button variant="outline" onClick={onDone}>{c.done}</Button></DrawerFooter></>;
  if (added) return <><Notice tone="info">{fill(m.publicKeyAdded, { serial: added })}</Notice><DrawerFooter><Button onClick={onDone}>{c.done}</Button></DrawerFooter></>;
  const cmds = challenge ? merchantCommands(challenge) : "";
  const ready = !!challenge && !!form.publicKeyPem.trim() && /^\d{13}$/.test(form.timestamp) && !!form.signature.trim() && !!form.reason.trim();
  return (
    <>
      <Notice tone="info" className="mb-4">{m.publicKeyNote}</Notice>
      {challenge && (
        <div className="space-y-3" data-challenge={challenge.challenge}>
          <p className="txt-caption">{m.challenge} <span className="font-mono">{challenge.challenge}</span> · {m.challengeExpires} {fmtTime(challenge.expiresAt)}</p>
          <p className="txt-caption">{m.stringToSign} <span className="font-mono break-all">{challenge.stringToSignPrefix}{"{TS}"}</span></p>
          <div>
            <p className="mb-1 txt-label text-muted-foreground">{m.merchantCommands}</p>
            <pre className={PRE}>{cmds}</pre>
            <div className="mt-1 flex gap-2">
              <Button size="sm" variant="ghost" onClick={() => copyText(cmds, c.done)}>{m.copyCommands}</Button>
              {api.source === "mock" && <Button size="sm" variant="ghost" loading={busy} onClick={() => void demo()}>{m.demoFill}</Button>}
            </div>
          </div>
          <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{m.publicKeyPem}</span>
            <Textarea rows={5} className="w-full font-mono" value={form.publicKeyPem} onChange={(v) => set("publicKeyPem", v)} /></label>
          <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{m.timestamp}</span>
            <Input className="w-full font-mono" value={form.timestamp} onChange={(e) => set("timestamp", e.target.value.trim())} /></label>
          <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{m.popSignature}</span>
            <Textarea rows={3} className="w-full font-mono" value={form.signature} onChange={(v) => set("signature", v.trim())} /></label>
          <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{c.reasonField}</span>
            <Input className="w-full" maxLength={255} value={form.reason} onChange={(e) => set("reason", e.target.value)} /></label>
          {demoPrivate && (
            <details><summary className="cursor-pointer txt-caption text-muted-foreground">{m.demoPrivate}</summary>
              <pre className={PRE}>{demoPrivate}</pre></details>
          )}
        </div>
      )}
      <DrawerFooter>
        {challenge && <Button variant="ghost" disabled={busy} onClick={() => { setChallenge(null); setDemoPrivate(""); }}>{m.abandonChallenge}</Button>}
        {!challenge ? <Button loading={busy} onClick={() => void issue()}>{m.issueChallenge}</Button>
          : <Button loading={busy} disabled={!ready} onClick={() => void upload()}>{m.uploadKey}</Button>}
      </DrawerFooter>
    </>
  );
}

export function KeyUsageTable({ api, appNo, name }: { api: PayCenterApi; appNo: string; name: string }) {
  const m = useCopy(P12_COPY);
  return (
    <DetailLoader name={name} id={`${appNo}:key-usage`} load={() => api.apps.keyUsage(appNo, 7)}>
      {(rows) => (
        <>
          <p className="mb-2 txt-caption text-muted-foreground">{m.keyUsageNote}</p>
          <MiniTable<KeyUsageRow> rows={rows} rowKey={(u) => String(u.keyVersion)} empty={m.noKeyUsage} columns={[
            { header: m.keyVersion, cell: (u) => `v${u.keyVersion}` },
            { header: m.calls, cell: (u) => u.calls },
            { header: m.lastCall, cell: (u) => (u.lastAt ? fmtTime(u.lastAt) : "—") },
          ]} />
        </>
      )}
    </DetailLoader>
  );
}
