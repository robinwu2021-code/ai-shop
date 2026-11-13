"use client";

import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { ApplyOnBehalfResult } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { HelpNote } from "@/components/ui/help-note";
import { Textarea } from "@/components/ui/textarea";
import { ApplyTab } from "./apply-tab";
import type { MerchantsCopy } from "./copy";

export function OnBehalfTab({ c, canSubmit }: { c: MerchantsCopy; canSubmit: boolean }) {
  const [open, setOpen] = useState(false);
  const [done, setDone] = useState<ApplyOnBehalfResult | null>(null);
  const qc = useQueryClient();
  if (!canSubmit) return <Notice tone="danger">{c.obNoPerm}</Notice>;
  return <div className="space-y-4">
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <p className="txt-body text-muted-foreground">{c.obListDesc}</p>
          <HelpNote>{c.obWarning}<p className="mt-2">{c.obDesc}</p></HelpNote>
        </div>
        <Dialog.Trigger asChild><Button>{c.obNew}</Button></Dialog.Trigger>
      </div>
      {open && <ApplicationDialog c={c} onDone={(result) => {
        setDone(result); setOpen(false);
        void qc.invalidateQueries({ queryKey: ["applies"] });
      }} onCancel={() => setOpen(false)} />}
    </Dialog.Root>
    {done && <Notice tone="info">{c.obDone}：{done.applyNo}<p className="mt-1 txt-caption">{c.obDoneHint}</p></Notice>}
    <ApplyTab c={c} canAudit={false} onBehalfOnly />
  </div>;
}

function ApplicationDialog({ c, onDone, onCancel }: { c: MerchantsCopy; onDone: (r: ApplyOnBehalfResult) => void; onCancel: () => void }) {
  const context = useQuery({queryKey:["onbehalf-context"],queryFn:()=>api.onBehalfContext()});
  const policy = context.data;
  const market = policy?.market;
  const phoneOwner = market === "CN" && policy?.recordOnly === false;
  const [ownerEmail, setOwnerEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [storeName, setStoreName] = useState("");
  const [storeAddress, setStoreAddress] = useState("");
  const [storeIndustry, setStoreIndustry] = useState("");
  const [storeSubIndustry, setStoreSubIndustry] = useState("");
  const [name, setName] = useState("");
  const [subject, setSubject] = useState("ENTERPRISE");
  const [licenseUrl, setLicenseUrl] = useState("");
  const [licenseCode, setLicenseCode] = useState("");
  const [contactName, setContactName] = useState("");
  const [contactPhone, setContactPhone] = useState("");
  const [category, setCategory] = useState("");
  const [industry, setIndustry] = useState("");
  const [description, setDescription] = useState("");
  const storeChoices = useQuery({queryKey:["onbehalf-store-industries"],queryFn:()=>api.onBehalfStoreIndustries()});
  const industries = useQuery({queryKey:["industries","for-on-behalf"],queryFn:()=>api.listIndustries()});
  const storeParents = (storeChoices.data ?? []).filter(r=>r.enabled && !r.subIndustryCode);
  const storeChildren = (storeChoices.data ?? []).filter(r=>r.enabled && r.subIndustryCode && r.industryCode===storeIndustry);
  const chosenStore = storeChildren.find(r=>r.subIndustryCode===storeSubIndustry);
  const phoneOk = policy?.recordOnly === true || (market === "CN" ? /^1[3-9]\d{9}$/.test(phone.trim()) : /^\+?\d{3,20}$/.test(phone.trim()));
  const ready = !!chosenStore && !!storeName.trim() && !!storeAddress.trim() && !!policy?.marketEnabled && !context.isError && phoneOk && (phoneOwner || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(ownerEmail.trim())) && !!name.trim() && !!licenseUrl.trim();
  const submit = useMutation({
    mutationFn: () => api.applyOnBehalf({
      ownerEmail: phoneOwner ? undefined : ownerEmail.trim(),
      phone: policy?.recordOnly ? phone : phone.trim(),
      storeProfile: {name:storeName.trim(),address:storeAddress.trim(),industryCode:storeIndustry,subIndustryCode:storeSubIndustry,businessTypeCode:chosenStore!.businessTypeCode},
      name:name.trim(), subject, contactName:contactName.trim() || undefined,
      contactPhone:contactPhone.trim() || undefined, category:category.trim() || undefined,
      industry:industry || undefined, description:description.trim() || undefined,
      qualificationItems:[{type:"BUSINESS_LICENSE",code:licenseCode.trim() || undefined,imageUrl:licenseUrl.trim()}],
    }),
    onSuccess:onDone,
    onError:(e:Error)=>notify.error(e.message),
  });
  const cell = "space-y-1.5";
  return <Dialog.Portal>
    <Dialog.Overlay className="fixed inset-0 z-[var(--z-dialog)] bg-black/40" />
    <Dialog.Content className="fixed left-1/2 top-1/2 z-[var(--z-dialog)] flex max-h-[90dvh] w-[min(94vw,880px)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-sheet bg-card shadow-pop outline-none"
      onInteractOutside={e=>e.preventDefault()} onEscapeKeyDown={e=>{if(submit.isPending)e.preventDefault();}}>
      <div className="border-b p-5">
        <Dialog.Title className="txt-heading">{c.obNew}</Dialog.Title>
        <Dialog.Description className="sr-only">{c.obDesc}</Dialog.Description>
        <p className="mt-1 txt-caption text-muted-foreground">{c.obSystemMarket}：{market ?? "—"} · {c.obSystemMarketHint}</p>
      </div>
      <form className="flex min-h-0 flex-1 flex-col" onSubmit={e=>{e.preventDefault();if(ready && !submit.isPending)submit.mutate();}}>
        <div className="min-h-0 space-y-5 overflow-y-auto p-5">
          {context.isPending && <Notice tone="muted">{c.obLoadingContext}</Notice>}
          {context.error && <Notice tone="danger">{context.error.message}</Notice>}
          {storeChoices.error && <Notice tone="danger">{storeChoices.error.message}</Notice>}
          {industries.error && <Notice tone="danger">{industries.error.message}</Notice>}
          {submit.error && <Notice tone="danger">{submit.error.message}</Notice>}
          <fieldset disabled={submit.isPending} className="space-y-5">
            <section className="space-y-3">
              <h3 className="txt-body font-semibold">{c.obOwnerSection}</h3>
              <div className="grid gap-4 sm:grid-cols-2">
                {!phoneOwner && <div className={cell}><Label required htmlFor="ob-owner-email">{c.obOwnerEmail}</Label><Input id="ob-owner-email" type="email" maxLength={254} value={ownerEmail} onChange={e=>setOwnerEmail(e.target.value)} /></div>}
                <div className={cell}><Label htmlFor="ob-phone">{phoneOwner ? c.obPhone : c.obContactPhone}</Label><Input id="ob-phone" maxLength={32} value={phone} onChange={e=>setPhone(e.target.value)} />
                  {policy?.recordOnly===false && phone.trim().length>=11 && !phoneOk && <Notice tone="danger">{c.soPhoneBad}</Notice>}
                </div>
              </div>
              <p className="txt-caption text-muted-foreground">{phoneOwner ? c.obPhoneHint : c.obRecordPhoneHint}</p>
            </section>
            <section className="space-y-3 border-t pt-4">
              <h3 className="txt-body font-semibold">{c.applySectionStore}</h3>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className={cell}><Label required htmlFor="ob-store-name">{c.obStoreName}</Label><Input id="ob-store-name" maxLength={64} value={storeName} onChange={e=>setStoreName(e.target.value)} /></div>
                <div className={cell}><Label required htmlFor="ob-store-address">{c.obStoreAddress}</Label><Input id="ob-store-address" maxLength={255} value={storeAddress} onChange={e=>setStoreAddress(e.target.value)} /></div>
                <div className={cell}><Label required htmlFor="ob-store-industry">{c.obStoreIndustry}</Label><Select id="ob-store-industry" required value={storeIndustry} onChange={e=>{setStoreIndustry(e.target.value);setStoreSubIndustry("");}}><option value="">{c.obChooseIndustry}</option>{storeParents.map(r=><option key={r.industryCode} value={r.industryCode}>{r.nameZh}</option>)}</Select></div>
                <div className={cell}><Label required htmlFor="ob-store-sub-industry">{c.obStoreSubIndustry}</Label><Select id="ob-store-sub-industry" required value={storeSubIndustry} disabled={!storeIndustry} onChange={e=>setStoreSubIndustry(e.target.value)}><option value="">{c.obChooseIndustry}</option>{storeChildren.map(r=><option key={r.subIndustryCode} value={r.subIndustryCode!}>{r.nameZh}</option>)}</Select></div>
              </div>
            </section>
            <section className="space-y-3 border-t pt-4">
              <h3 className="txt-body font-semibold">{c.obEntitySection}</h3>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className={cell}><Label required htmlFor="ob-name">{c.obName}</Label><Input id="ob-name" required value={name} onChange={e=>setName(e.target.value)} /></div>
                <div className={cell}><Label required htmlFor="ob-subject">{c.obSubject}</Label><Select id="ob-subject" required value={subject} onChange={e=>setSubject(e.target.value)}><option value="ENTERPRISE">{c.obSubjectEnterprise}</option><option value="INDIVIDUAL">{c.obSubjectIndividual}</option></Select></div>
                <div className={cell}><Label htmlFor="ob-license-code">{c.obLicenseCode}</Label><Input id="ob-license-code" value={licenseCode} onChange={e=>setLicenseCode(e.target.value)} /></div>
                <div className={cell}><Label required htmlFor="ob-license">{c.obLicense}</Label><Input id="ob-license" required value={licenseUrl} onChange={e=>setLicenseUrl(e.target.value)} /></div>
              </div>
            </section>
            <details className="border-t pt-4">
              <summary className="cursor-pointer txt-body font-medium">{c.obOptional}</summary>
              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                <div className={cell}><Label htmlFor="ob-contact-name">{c.obContactName}</Label><Input id="ob-contact-name" value={contactName} onChange={e=>setContactName(e.target.value)} /></div>
                <div className={cell}><Label htmlFor="ob-contact-phone">{c.obContactPhone}</Label><Input id="ob-contact-phone" value={contactPhone} onChange={e=>setContactPhone(e.target.value)} /></div>
                <div className={cell}><Label htmlFor="ob-category">{c.obCategory}</Label><Input id="ob-category" value={category} onChange={e=>setCategory(e.target.value)} /></div>
                <div className={cell}><Label htmlFor="ob-industry">{c.soIndustry}</Label><Select id="ob-industry" value={industry} onChange={e=>setIndustry(e.target.value)}><option value="">{c.obChooseIndustry}</option>{(industries.data??[]).filter(i=>i.enabled).map(i=><option key={i.industry} value={i.industry}>{i.name}</option>)}</Select></div>
                <div className={`${cell} sm:col-span-2`}><Label htmlFor="ob-description">{c.soDescription}</Label><Textarea id="ob-description" value={description} onChange={setDescription} rows={2} /></div>
              </div>
            </details>
          </fieldset>
        </div>
        <div className="flex shrink-0 justify-end gap-2 border-t bg-muted/40 px-5 py-4">
          <Button type="button" variant="secondary" disabled={submit.isPending} onClick={onCancel}>{c.obCancel}</Button>
          <Button type="submit" disabled={!ready || submit.isPending} loading={submit.isPending}>{c.obSubmit}</Button>
        </div>
      </form>
    </Dialog.Content>
  </Dialog.Portal>;
}
