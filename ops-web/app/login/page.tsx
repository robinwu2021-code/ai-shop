"use client";

import { useState, useEffect, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import { verifyLoginChallenge } from "@/lib/api/https/dashboard";
import { acceptLoginResult } from "@/lib/auth/login-result";
import { useQueryClient } from "@tanstack/react-query";
import { safeReturnTo, paymentBasePath, returnAfterLogin } from "@/lib/auth/ops-bridge";
import { useServerMenu } from "@/lib/stores/server-menu";
import { parseLocale, useLocaleStore } from "@/lib/stores/locale";
import { LangSwitcher } from "@/components/layout/lang-switcher";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Drawer } from "@/components/ui/drawer";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";

/*
 * 登录：用户名 + 密码，**角色由后端返回**。
 *
 * 这里此前是「选角色即进入」的 mock 登录 —— 在 mock 上没问题，但一旦指向真实后端
 * 就是两件错事：一是后端要的是 {username, password}，收到 {username, role} 直接拒；
 * 二是**让用户自己挑角色**，那是把权限交给被鉴权的一方。
 * 真实后端只认凭据，角色来自 STAFF 账号自身。
 */
export default function LoginPage() {
  return <Suspense><LoginForm /></Suspense>;
}

function LoginForm() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const token = useAuth((s) => s.token);
  const queryClient = useQueryClient();
  const { t } = useI18n();
  const params = useSearchParams();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [challenge, setChallenge] = useState("");
  const [challengeTo, setChallengeTo] = useState("");
  const [code, setCode] = useState("");
  const [forgot, setForgot] = useState(false);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [paymentSource, setPaymentSource] = useState(false);

  function finishLogin() {
    returnAfterLogin(params.get("returnTo"), window.location, () => router.replace("/"), useLocaleStore.getState().locale);
  }

  useEffect(() => {
    const locale = parseLocale(params.get("locale"));
    if (locale) useLocaleStore.getState().setLocale(locale);
    const target = safeReturnTo(params.get("returnTo"), window.location.origin);
    const base = paymentBasePath(window.location.origin);
    setPaymentSource(!!target && (target === base || target.startsWith(`${base}/`)));
  }, [params]);

  useEffect(() => {
    if (token) finishLogin();
    // The token was verified by the shared-session gate or returned by login.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function doLogin(u: string, p: string) {
    setBusy(true);
    setErr("");
    try {
      const result = challenge ? await verifyLoginChallenge(challenge, code) : await api.login(u, p);
      acceptLoginResult(result, (nextChallenge, sentTo) => {
        setChallenge(nextChallenge); setChallengeTo(sentTo); setCode(""); setPassword("");
      }, (r) => {
        queryClient.clear();
        useServerMenu.getState().clear();
        login({
          username: r.username,
          role: r.role,
          token: r.token,
          perms: r.perms,
          merchantNo: r.merchantNo,
          communityNo: r.communityNo,
          pickupNo: r.pickupNo,
      });
      finishLogin();
      });
    } catch (e) {
      setErr((e as Error).message || t("common.failed"));
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr("");
    await doLogin(username, password);
  }

  return (
    <div className="flex h-screen items-center justify-center bg-muted/30">
      <Card className="w-[360px]">
        <CardHeader>
          <div className="flex justify-end"><LangSwitcher /></div>
          <div className="mb-1 flex size-9 items-center justify-center rounded-field bg-primary txt-strong text-primary-foreground">
            邻
          </div>
          <CardTitle>{t("common.appTitle")}</CardTitle>
          <p className="txt-body text-muted-foreground">{t("login.subtitle")}</p>
          {paymentSource && <p className="txt-body text-muted-foreground">{process.env.NEXT_PUBLIC_NEARPAY_LOGIN_HINT || t("login.paymentSource")}</p>}
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={submit}>
            {!challenge ? <>
            <div className="space-y-1">
              <label className="txt-body text-muted-foreground">{t("login.username")}</label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={t("login.username")} />
            </div>
            <div className="space-y-1">
              <label className="txt-body text-muted-foreground">{t("login.password")}</label>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t("login.password")}
              />
            </div>
            </> : <div className="space-y-2">
              <p className="txt-body text-muted-foreground">{t("login.codeSent", { email: challengeTo })}</p>
              <label htmlFor="login-code" className="txt-body">{t("login.emailCode")}</label>
              <Input id="login-code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                     inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required autoFocus />
            </div>}
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 txt-body text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy || (!!challenge && !/^\d{6}$/.test(code))}>
              {busy ? t("common.loading") : t(challenge ? "login.verifyCode" : "login.submit")}
            </Button>
            {challenge && <Button type="button" variant="ghost" className="w-full" disabled={busy}
              onClick={() => { setChallenge(""); setChallengeTo(""); setCode(""); setPassword(""); setErr(""); }}>
              {t("login.restart")}
            </Button>}
          </form>

          {/*
            忘记密码。这条路径此前完全不存在 —— 后端只有登录与改自己的密码，
            运营忘了密码只能找人改库。放在登录按钮下面而不是藏进菜单：
            需要它的人此刻正被挡在门外，看不到任何菜单。
          */}
          <button type="button" onClick={() => setForgot(true)}
                  className="focus-ring mt-3 w-full txt-caption text-muted-foreground hover:text-foreground">
            {t("login.forgot")}
          </button>
        </CardContent>
      </Card>

      <ForgotDialog open={forgot} onClose={() => setForgot(false)} />
    </div>
  );
}

/**
 * 忘记密码 → 收码 → 设新密码，两步在同一个抽屉里完成。
 *
 * 不做成两个页面：重置码在邮件里，用户要在邮箱与浏览器之间切一次，
 * 中间再穿一次页面跳转，很容易丢掉上下文（尤其手机上）。
 */
function ForgotDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const [step, setStep] = useState<"ask" | "reset">("ask");
  const [email, setEmail] = useState("");
  const [token, setToken] = useState("");
  const [pwd, setPwd] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

  const close = () => { setStep("ask"); setToken(""); setPwd(""); setMsg(""); setErr(""); onClose(); };

  const sendCode = async () => {
    setBusy(true); setErr("");
    try {
      await api.forgotPassword(email.trim());
      // **无论账号存不存在都是这句** —— 与后端同口径，端上不要自作主张去区分
      setMsg(t("login.forgotSent"));
      setStep("reset");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  };

  const doReset = async () => {
    setBusy(true); setErr("");
    try {
      await api.resetPassword(token.trim(), pwd);
      setMsg(t("login.resetDone"));
      setStep("ask");
      setToken(""); setPwd("");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  };

  return (
    <Drawer open={open} onOpenChange={(o) => !o && close()}
            title={t("login.forgotTitle")} desc={t("login.forgotDesc")}>
      <div className="space-y-4">
        <Notice tone="info">{t("login.forgotNote")}</Notice>

        <div className="space-y-1">
          <Label>{t("login.username")}</Label>
          <Input value={email} onChange={(e) => setEmail(e.target.value)}
                 placeholder="name@neargo.ai" disabled={step === "reset"} />
        </div>

        {step === "ask" ? (
          <Button className="w-full" disabled={!email.trim() || busy} onClick={sendCode}>
            {t("login.forgotSend")}
          </Button>
        ) : (
          <>
            <div className="space-y-1">
              <Label>{t("login.resetToken")}</Label>
              <Input value={token} onChange={(e) => setToken(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label>{t("login.resetNew")}</Label>
              <Input type="password" value={pwd} onChange={(e) => setPwd(e.target.value)}
                     placeholder={t("login.resetNewHint")} />
            </div>
            <Button className="w-full" disabled={!token.trim() || pwd.length < 8 || busy}
                    onClick={doReset}>
              {t("login.resetSubmit")}
            </Button>
          </>
        )}

        {msg && <Notice tone="info">{msg}</Notice>}
        {err && <Notice tone="danger">{err}</Notice>}
      </div>
    </Drawer>
  );
}
