"use client";

// 登录后改自己的密码（顶栏入口）。
//
// 此前运营端只有「忘记密码」（登录页 → 邮件重置码），登录之后没有任何地方能改密码 ——
// 后端 `POST /ops/staffs/me/password` 早就在，一直登记在 known-orphan-endpoints.txt 里当能力缺口。
//
// 两件要注意的事：
// 1. 旧密码不对时后端回 `code=10401`（HTTP 200）。那个码的通用文案是「未登录」，
//    照搬会让人以为会话过期了 —— 这里翻成「当前密码不正确」，也**不**把人踢回登录页。
// 2. 成功后后端吊销这个账号的全部会话，包括手里这一张；不主动登出的话，
//    下一次请求会以 401 被动踢出，人会以为出了故障。所以成功即登出、回登录页并说清原因。
import * as React from "react";
import { useRouter } from "next/navigation";
import { KeyRound } from "lucide-react";
import { api } from "@/lib/api";
import { isApiError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";

/** 前端先拦的三种错；返回 i18n key 或 null。后端仍是最终判定 */
export function changePasswordProblem(oldPwd: string, newPwd: string, confirm: string): string | null {
  if (newPwd.length < 8) return "account.tooShort";
  if (newPwd === oldPwd) return "account.sameAsOld";
  if (confirm !== newPwd) return "account.mismatch";
  return null;
}

export function ChangePasswordButton() {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(false);
  return (
    <>
      <Button size="sm" variant="ghost" aria-label={t("account.changePwd")} title={t("account.changePwd")} onClick={() => setOpen(true)}>
        <KeyRound className="size-4" />
      </Button>
      <ChangePasswordDrawer open={open} onClose={() => setOpen(false)} />
    </>
  );
}

function ChangePasswordDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const router = useRouter();
  const logout = useAuth((s) => s.logout);
  const [form, setForm] = React.useState({ old: "", next: "", confirm: "" });
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState("");

  const close = () => { setForm({ old: "", next: "", confirm: "" }); setErr(""); onClose(); };
  const problem = changePasswordProblem(form.old, form.next, form.confirm);
  // 新密码与确认都填了才提示，边打边红很烦人
  const showProblem = form.next && form.confirm ? problem : null;

  const submit = async () => {
    setBusy(true); setErr("");
    try {
      await api.changeOwnPassword(form.old, form.next);
      notify.success(t("account.changed"));
      close();
      logout();
      router.push("/login");
    } catch (e) {
      setErr(isApiError(e) && e.code === 10401 ? t("account.oldWrong") : e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Drawer open={open} onOpenChange={(o) => !o && close()} title={t("account.changePwd")} desc={t("account.changePwdDesc")}
      footer={<Button className="w-full" disabled={!form.old || !!problem || busy} loading={busy} onClick={submit}>{t("account.submit")}</Button>}>
      <div className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="cp-old" required>{t("account.oldPwd")}</Label>
          <Input id="cp-old" type="password" autoComplete="current-password" value={form.old}
            onChange={(e) => setForm((f) => ({ ...f, old: e.target.value }))} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="cp-new" required>{t("account.newPwd")}</Label>
          <Input id="cp-new" type="password" autoComplete="new-password" placeholder={t("account.newPwdHint")} value={form.next}
            onChange={(e) => setForm((f) => ({ ...f, next: e.target.value }))} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="cp-confirm" required>{t("account.confirmPwd")}</Label>
          <Input id="cp-confirm" type="password" autoComplete="new-password" value={form.confirm} invalid={!!showProblem}
            onChange={(e) => setForm((f) => ({ ...f, confirm: e.target.value }))} />
        </div>
        {showProblem && <Notice tone="warning">{t(showProblem)}</Notice>}
        {err && <Notice tone="danger">{err}</Notice>}
      </div>
    </Drawer>
  );
}
