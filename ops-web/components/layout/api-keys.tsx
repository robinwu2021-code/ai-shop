"use client";

import { useState, type FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { KeySquare } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Drawer } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Radio, RadioGroup } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";
import { isApiError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { parseApiKeyEnrollment } from "@/lib/ops-api-key-enrollment";
import type { MyOpsApiKey, OpsApiKeyAccess } from "@/lib/types/ops-api-key";

const initialForm = { setup: "", label: "", access: "READ_ONLY" as OpsApiKeyAccess, days: "7" };

export function ApiKeysButton() {
  const { t } = useI18n();
  const username = useAuth((s) => s.username);
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="sm" variant="ghost" aria-label={t("apiKeys.title")} title={t("apiKeys.title")}
        onClick={() => setOpen(true)}>
        <KeySquare className="size-4" />
      </Button>
      {open && <MyApiKeys key={username} username={username} onClose={() => setOpen(false)} />}
    </>
  );
}

function MyApiKeys({ username, onClose }: { username: string; onClose: () => void }) {
  const { t, localeTag } = useI18n();
  const queryClient = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [form, setForm] = useState(initialForm);
  const [applying, setApplying] = useState(false);
  const [problem, setProblem] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const queryKey = ["my-ops-api-keys", username];
  const query = useQuery({
    queryKey,
    queryFn: async () => {
      const [keys, identity] = await Promise.all([api.listMyApiKeys(), api.me()]);
      return { keys, identity };
    },
    retry: false,
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey });
  const identity = query.data?.identity;
  const origin = new URL(process.env.NEXT_PUBLIC_API_BASE || window.location.origin, window.location.origin).origin;
  const validDays = Number(form.days);
  const dates = new Intl.DateTimeFormat(localeTag, { dateStyle: "medium", timeStyle: "short" });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!identity?.staffNo || identity.mustChangePassword || submitting) return;
    const setup = parseApiKeyEnrollment(form.setup, origin, identity.staffNo);
    if (typeof setup === "string") {
      setProblem(t(setup === "mismatch" ? "apiKeys.setupMismatch" : "apiKeys.setupInvalid"));
      return;
    }
    setProblem("");
    setSubmitting(true);
    try {
      const key = await api.registerMyApiKey({ ...setup, label: form.label.trim(), access: form.access, validDays });
      setForm(initialForm);
      setApplying(false);
      if (key.status === "ACTIVE") notify.success(t("apiKeys.registered"));
      else notify.error(t("apiKeys.inactiveRegistration"));
      void refresh();
    } catch (error) {
      setProblem(isApiError(error) && error.code === 10409
        ? t("apiKeys.conflict") : t("apiKeys.registerFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  function revoke(key: MyOpsApiKey) {
    void confirm({
      title: t("apiKeys.revokeTitle", { label: key.label }),
      desc: t("apiKeys.revokeHint"),
      danger: true,
      confirmText: t("apiKeys.revoke"),
      action: async () => {
        try {
          await api.revokeMyApiKey(key.keyNo);
        } catch (error) {
          notify.error(t("apiKeys.revokeFailed"));
          throw error;
        }
        await refresh();
        notify.success(t("apiKeys.revoked"));
      },
    });
  }

  return (
    <Drawer open onOpenChange={(open) => { if (!open && !submitting) onClose(); }}
      width="w-[520px]" title={t("apiKeys.title")} desc={t("apiKeys.description")}>
      <div className="space-y-4">
        {query.isPending && <p role="status" className="txt-body text-muted-foreground">{t("apiKeys.loading")}</p>}
        {query.isError && (
          <Notice tone="danger">
            {t("apiKeys.loadFailed")}{" "}
            <Button variant="ghost" size="sm" onClick={() => void query.refetch()}>{t("apiKeys.retry")}</Button>
          </Notice>
        )}
        {identity?.mustChangePassword && <Notice tone="warning">{t("apiKeys.changePassword")}</Notice>}
        {query.data && !query.data.keys.length && <p className="txt-body text-muted-foreground">{t("apiKeys.empty")}</p>}
        {query.data?.keys.map((key) => (
          <section className="space-y-1 rounded-field border border-border p-3 txt-body" key={key.keyNo}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-medium break-all">{key.label}</span>
              <div className="flex items-center gap-1">
                <Badge tone={key.status === "ACTIVE" ? "success" : "muted"}>{t(`apiKeys.status.${key.status}`)}</Badge>
                {key.status === "ACTIVE" && <Button variant="ghost" size="sm" onClick={() => revoke(key)}>
                  {t("apiKeys.revoke")}
                </Button>}
              </div>
            </div>
            <code className="block break-all txt-caption text-muted-foreground">{key.keyNo}</code>
            <p>{t(`apiKeys.access.${key.access}`)}</p>
            <p className="text-muted-foreground">{t("apiKeys.created", { date: dates.format(key.createdAt) })}</p>
            <p className="text-muted-foreground">{t("apiKeys.expires", { date: dates.format(key.expiresAt) })}</p>
          </section>
        ))}
        {identity?.staffNo && !identity.mustChangePassword && !applying && (
          <Button onClick={() => setApplying(true)}>{t("apiKeys.apply")}</Button>
        )}
        {applying && identity?.staffNo && (
          <form className="space-y-3 rounded-field border border-border p-3" onSubmit={submit}>
            <p className="txt-body text-muted-foreground">{t("apiKeys.setupHint")}</p>
            <code className="block break-all rounded-field bg-muted p-2 txt-caption">
              ops-api-key prepare {origin} {identity.staffNo}
            </code>
            <fieldset className="space-y-3" disabled={submitting}>
              <div className="space-y-1">
                <Label htmlFor="api-key-setup" required>{t("apiKeys.setup")}</Label>
                <Textarea id="api-key-setup" required maxLength={1024} rows={3} spellCheck={false}
                  autoComplete="off" value={form.setup} onChange={(setup) => setForm({ ...form, setup })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="api-key-label" required>{t("apiKeys.label")}</Label>
                <Input id="api-key-label" required maxLength={80} value={form.label}
                  onChange={(event) => setForm({ ...form, label: event.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="api-key-days" required>{t("apiKeys.days")}</Label>
                <Input id="api-key-days" type="number" required min={1} max={90} value={form.days}
                  onChange={(event) => setForm({ ...form, days: event.target.value })} />
              </div>
              <fieldset className="space-y-1">
                <legend className="txt-body font-medium">{t("apiKeys.accessLabel")}</legend>
                <RadioGroup value={form.access} name="api-key-access" aria-label={t("apiKeys.accessLabel")}
                  disabled={submitting}
                  onChange={(access) => setForm({ ...form, access: access === "MANAGE" ? "MANAGE" : "READ_ONLY" })}>
                  <Radio value="READ_ONLY" label={t("apiKeys.access.READ_ONLY")} />
                  <Radio value="MANAGE" label={t("apiKeys.access.MANAGE")} />
                </RadioGroup>
              </fieldset>
              {form.access === "MANAGE" && <Notice tone="warning">{t("apiKeys.manageHint")}</Notice>}
              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={!form.label.trim() || !form.setup.trim() || !Number.isInteger(validDays)
                  || validDays < 1 || validDays > 90}>{t("apiKeys.apply")}</Button>
                <Button type="button" variant="ghost" onClick={() => { setApplying(false); setProblem(""); setForm(initialForm); }}>
                  {t("common.cancel")}
                </Button>
              </div>
            </fieldset>
            {submitting && <p role="status" className="txt-body">{t("apiKeys.submitting")}</p>}
            {problem && <Notice tone="danger">{problem}</Notice>}
          </form>
        )}
      </div>
      {dialog}
    </Drawer>
  );
}
