"use client";

// 商家抽屉里的「支付服务」块（TDD-运营端申请支付服务）。
//
// 为主体在独立支付服务 near-pay 里建平台商户、来源链接与门店关联 —— 开通收款的第一步。
// 之后的核验来源链接、登记渠道账户、录入凭证、绑定门店都在支付运营端完成，这里不画。
// 只有持 merchant:merchant:pay-apply 的运营看得到按钮（只给超管）。

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayServiceApplyForm } from "@/lib/api/contracts/merchant";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/drawer";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import type { MerchantsCopy } from "./copy";

export const PAY_APPLY_PERM = "merchant:merchant:pay-apply";

/** 表单值 → 请求体：去空白，可选项空串不发 */
export function toApplyForm(v: Record<string, unknown>): Omit<PayServiceApplyForm, "requestNo"> {
  const s = (k: string) => String(v[k] ?? "").trim();
  const opt = (k: string) => s(k) || undefined;
  return {
    legalName: s("legalName"),
    registrationNo: s("registrationNo"),
    representativeName: opt("representativeName"),
    contactPhone: opt("contactPhone"),
    registeredAddress: opt("registeredAddress"),
  };
}

export function PayServiceBlock({ merchantNo, merchantName, c }: { merchantNo: string; merchantName?: string; c: MerchantsCopy }) {
  const allow = useCan();
  if (!allow(PAY_APPLY_PERM)) return null;
  return <PaymentDetails key={merchantNo} merchantNo={merchantNo} merchantName={merchantName} c={c} />;
}

function PaymentDetails({ merchantNo, merchantName, c }: { merchantNo: string; merchantName?: string; c: MerchantsCopy }) {
  const [form, setForm] = useState<Record<string, unknown> | null>(null);
  const submission = useRef<{ fingerprint: string; requestNo: string } | null>(null);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const queryClient = useQueryClient();
  const statusKey = ["merchant-pay-service", merchantNo];
  const status = useQuery({ queryKey: statusKey, queryFn: () => api.payServiceStatus(merchantNo), retry: false });
  const apply = useMutation({
    mutationFn: (body: PayServiceApplyForm) => api.applyPayService(merchantNo, body),
    // A new submission or an uncertain response invalidates the previous profile's review conclusion.
    onSettled: () => { void queryClient.resetQueries({ queryKey: ["merchant-pay-service", merchantNo], exact: true }); },
    onSuccess: (r) => {
      if (!active.current) return;
      notify.success(c.payServiceDone.replace("{merchantRef}", r.merchantRef).replace("{status}", r.status));
      setForm(null);
      submission.current = null;
    },
  });

  const submit = () => {
    const body = toApplyForm(form ?? {});
    const fingerprint = JSON.stringify(body);
    if (submission.current?.fingerprint !== fingerprint)
      submission.current = { fingerprint, requestNo: crypto.randomUUID() };
    apply.mutate({ ...body, requestNo: submission.current.requestNo });
  };

  const fields: FieldDef[] = [
    { key: "legalName", label: c.payServiceLegalName, required: true, maxLength: 128 },
    { key: "registrationNo", label: c.payServiceRegistrationNo, required: true, maxLength: 64 },
    { key: "representativeName", label: c.payServiceRepresentative, maxLength: 64 },
    { key: "contactPhone", label: c.payServiceContactPhone, maxLength: 32 },
    { key: "registeredAddress", label: c.payServiceAddress, maxLength: 256 },
  ];

  return (
    <Field label={c.payServiceField}>
      <p className="mb-2 txt-caption text-muted-foreground">{c.payServiceHelp}</p>
      <div className="mb-3 space-y-1 txt-caption" aria-live="polite">
        {status.isPending && <p>{c.payServiceLoading}</p>}
        {status.isError && <p role="alert">{c.payServiceLoadFailed}</p>}
        {status.data && !status.isError && <>
          <p>{c.payServiceSourceStatus}：{status.data.status}</p>
          {status.data.merchantRef && <p>{c.payServiceMerchantRef}：{status.data.merchantRef}</p>}
          {status.data.linkStatus && <p>{c.payServiceLinkStatus}：{status.data.linkStatus}</p>}
          {status.data.profileStatus && <p>{c.payServiceProfileStatus}：{status.data.profileStatus}</p>}
          {status.data.reviewReason && <p>{c.payServiceReviewReason}：{status.data.reviewReason}</p>}
          <p className="text-muted-foreground">{c.payServiceCollectionHelp}</p>
          {status.data.merchantOpsUrl && <p><a className="focus-ring underline" href={status.data.merchantOpsUrl} target="_blank" rel="noopener noreferrer">{c.payServiceMerchantOps}</a></p>}
          {status.data.onboardingOpsUrl && <p><a className="focus-ring underline" href={status.data.onboardingOpsUrl} target="_blank" rel="noopener noreferrer">{c.payServiceOnboardingOps}</a></p>}
          {!status.data.merchantOpsUrl && !status.data.onboardingOpsUrl && <p className="text-muted-foreground">{c.payServiceNoOpsLink}</p>}
        </>}
      </div>
      <Button size="sm" variant="outline" disabled={status.isFetching} onClick={() => void status.refetch()}>{c.payServiceRefresh}</Button>{" "}
      <Button size="sm" variant="outline" onClick={() => setForm({ legalName: merchantName ?? "" })}>
        {c.payServiceApply}
      </Button>
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew={c.payServiceDrawer}
        titleEdit={c.payServiceDrawer}
        isEdit={false}
        fields={fields}
        value={form ?? {}}
        onChange={setForm}
        onSubmit={submit}
        submitting={apply.isPending}
      />
    </Field>
  );
}
