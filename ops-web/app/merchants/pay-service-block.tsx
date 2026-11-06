"use client";

// 商家抽屉里的「支付服务」块（TDD-运营端申请支付服务）。
//
// 为主体在独立支付服务 near-pay 里建平台商户、来源链接与门店关联 —— 开通收款的第一步。
// 之后的核验来源链接、登记渠道账户、录入凭证、绑定门店都在支付运营端完成，这里不画。
// 只有持 merchant:merchant:pay-apply 的运营看得到按钮（只给超管）。

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
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
export function toApplyForm(v: Record<string, unknown>): PayServiceApplyForm {
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
  const [form, setForm] = useState<Record<string, unknown> | null>(null);
  const apply = useMutation({
    mutationFn: () => api.applyPayService(merchantNo, toApplyForm(form ?? {})),
    onSuccess: (r) => {
      notify.success(c.payServiceDone.replace("{merchantRef}", r.merchantRef).replace("{status}", r.status));
      setForm(null);
    },
  });
  if (!allow(PAY_APPLY_PERM)) return null;

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
        onSubmit={() => apply.mutate()}
        submitting={apply.isPending}
      />
    </Field>
  );
}
