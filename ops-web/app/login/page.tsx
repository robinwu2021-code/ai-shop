"use client";

import { useState, useEffect, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { platformLandingHref } from "@/lib/platform-nav";
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
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { BrandMark } from "@/components/brand-mark";
import { useBrand } from "@/lib/stores/brand";
import { cn } from "@/lib/utils";
import { Eye, EyeOff } from "lucide-react";

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
  const [mode, setMode] = useState<"login" | "forgot">("login");
  const [showPwd, setShowPwd] = useState(false);
  const [notice, setNotice] = useState("");
  const brand = useBrand();
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [paymentSource, setPaymentSource] = useState(false);

  function finishLogin() {
    returnAfterLogin(params.get("returnTo"), window.location, () => router.replace(platformLandingHref(useAuth.getState().perms, useServerMenu.getState().hrefSet) ?? "/"), useLocaleStore.getState().locale);
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

  const envLabel = brand.env === "test" ? t("login.envTest") : brand.env === "staging" ? t("login.envStaging") : "";

  /*
   * 布局沿用线上「浅底 + 居中卡片」（原型 prototypes/ops-login.html v3）。
   * 名称、标识、环境来自 brand.json（TDD-ops-品牌名称与登录页）；生产环境不显示环境标签。
   * 窄屏（< sm）卡片去掉外框铺满，字段与按钮保持触控高度。
   */
  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center bg-background px-4 py-16">
      <div className="absolute end-4 top-4 sm:end-6 sm:top-5"><LangSwitcher /></div>
      <div className="flex w-full max-w-[380px] flex-col gap-5 sm:rounded-card sm:border sm:border-[var(--card-border)] sm:bg-card sm:p-7 sm:shadow-pop">
        <div className="flex items-center gap-3">
          <BrandMark />
          <h1 className="min-w-0 truncate txt-title">{mode === "forgot" ? t("login.forgotTitle") : brand.name}</h1>
          {envLabel && <span className="ms-auto shrink-0 rounded-chip bg-warning-tint px-2.5 py-0.5 txt-label text-warning-ink">{envLabel}</span>}
        </div>

        {mode === "forgot" ? (
          <ForgotPanel onBack={(done) => { setMode("login"); setNotice(done ?? ""); }} />
        ) : (
          <form className="flex flex-col gap-4" onSubmit={submit}>
            {!challenge ? <>
              <p className="-mt-2 txt-body text-muted-foreground">{t("login.hint")}</p>
              {paymentSource && <p className="-mt-2 txt-body text-muted-foreground">{process.env.NEXT_PUBLIC_NEARPAY_LOGIN_HINT || t("login.paymentSource")}</p>}
              {notice && <Notice tone="info">{notice}</Notice>}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="login-username">{t("login.username")}</Label>
                <Input id="login-username" autoComplete="username" autoFocus value={username} invalid={!!err}
                       onChange={(e) => setUsername(e.target.value)} placeholder={t("login.username")} />
              </div>
              <div className="flex flex-col gap-1.5">
                <div className="flex items-baseline justify-between">
                  <Label htmlFor="login-password">{t("login.password")}</Label>
                  {/* 忘记密码贴着密码标签：需要它的人此刻正盯着这一栏 */}
                  <button type="button" onClick={() => { setErr(""); setNotice(""); setMode("forgot"); }}
                          className="focus-ring rounded-field txt-caption text-muted-foreground hover:text-foreground">
                    {t("login.forgot")}
                  </button>
                </div>
                <div className="relative">
                  <Input id="login-password" type={showPwd ? "text" : "password"} autoComplete="current-password"
                         value={password} invalid={!!err} className="pe-10"
                         onChange={(e) => setPassword(e.target.value)} placeholder={t("login.password")} />
                  <button type="button" onClick={() => setShowPwd((v) => !v)}
                          aria-label={t(showPwd ? "login.hidePassword" : "login.showPassword")}
                          className="focus-ring absolute end-1.5 top-1/2 flex size-7 -translate-y-1/2 items-center justify-center rounded-field text-muted-foreground hover:text-foreground">
                    {showPwd ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
              </div>
            </> : <>
              <p className="-mt-2 txt-body text-muted-foreground">{t("login.codeSent", { email: challengeTo })}</p>
              <OtpInput value={code} onChange={setCode} label={t("login.emailCode")} />
            </>}
            {err && <div role="alert" className="rounded-field bg-destructive-tint px-3.5 py-2 txt-body text-destructive">{err}</div>}
            <Button size="lg" className="w-full" type="submit" disabled={busy || (!!challenge && !/^\d{6}$/.test(code))}>
              {busy ? t("common.loading") : t(challenge ? "login.verifyCode" : "login.submit")}
            </Button>
            {challenge && <Button type="button" variant="ghost" className="-mt-2 w-full" disabled={busy}
              onClick={() => { setChallenge(""); setChallengeTo(""); setCode(""); setPassword(""); setErr(""); }}>
              {t("login.restart")}
            </Button>}
          </form>
        )}
      </div>
    </div>
  );
}

/**
 * 6 格验证码：一个真实的 <input>（透明地盖在格子上，负责输入、粘贴与读屏），格子只做显示。
 * 输满 6 位前提交按钮是灰的，这一点由上层的 disabled 判定负责。
 */
function OtpInput({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const [focused, setFocused] = useState(true);
  return (
    <div className="relative">
      <input id="login-code" aria-label={label} value={value} autoFocus required
             inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6}
             onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
             onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 6))}
             className="absolute inset-0 z-10 w-full cursor-text opacity-0" />
      <div aria-hidden className="grid grid-cols-6 gap-2">
        {Array.from({ length: 6 }, (_, i) => (
          <span key={i} className={cn(
            "flex h-12 items-center justify-center rounded-field border border-[var(--card-border)] bg-card txt-title tabular-nums",
            focused && i === Math.min(value.length, 5) && "border-foreground ring-2 ring-ring",
          )}>{value[i] ?? ""}</span>
        ))}
      </div>
    </div>
  );
}

/**
 * 忘记密码 → 收码 → 设新密码，在登录卡片里原地切换（原先是另弹一个抽屉）。
 *
 * 不做成两个页面：重置码在邮件里，用户要在邮箱与浏览器之间切一次，
 * 中间再穿一次页面跳转，很容易丢掉上下文（尤其手机上）。
 */
function ForgotPanel({ onBack }: { onBack: (done?: string) => void }) {
  const { t } = useI18n();
  const [step, setStep] = useState<"ask" | "reset">("ask");
  const [email, setEmail] = useState("");
  const [token, setToken] = useState("");
  const [pwd, setPwd] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

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
      onBack(t("login.resetDone"));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  };

  return (
    <div className="flex flex-col gap-4">
      {step === "ask" ? <>
        <p className="-mt-2 txt-body text-muted-foreground">{t("login.forgotDesc")}</p>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="forgot-email">{t("login.forgotEmail")}</Label>
          <Input id="forgot-email" type="email" autoComplete="email" autoFocus value={email}
                 onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" />
        </div>
        {err && <div role="alert" className="rounded-field bg-destructive-tint px-3.5 py-2 txt-body text-destructive">{err}</div>}
        <Button size="lg" className="w-full" disabled={!email.trim() || busy} onClick={sendCode}>{t("login.forgotSend")}</Button>
      </> : <>
        <p className="-mt-2 txt-body text-muted-foreground">{msg}</p>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="reset-token">{t("login.resetToken")}</Label>
          <Input id="reset-token" autoFocus autoComplete="one-time-code" value={token} onChange={(e) => setToken(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="reset-password">{t("login.resetNew")}</Label>
          <Input id="reset-password" type="password" autoComplete="new-password" value={pwd}
                 onChange={(e) => setPwd(e.target.value)} placeholder={t("login.resetNewHint")} />
        </div>
        {err && <div role="alert" className="rounded-field bg-destructive-tint px-3.5 py-2 txt-body text-destructive">{err}</div>}
        <Button size="lg" className="w-full" disabled={!token.trim() || pwd.length < 8 || busy} onClick={doReset}>{t("login.resetSubmit")}</Button>
      </>}
      <Button type="button" variant="ghost" className="-mt-2 w-full" onClick={() => onBack()}>{t("login.backToLogin")}</Button>
    </div>
  );
}
