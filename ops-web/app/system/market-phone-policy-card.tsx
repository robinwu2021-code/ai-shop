"use client";
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { ConfigCard } from "@/components/ui/config-card";
import { CheckboxField } from "@/components/ui/checkbox";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { DataTable } from "@/components/ui/data-table";
import type { SYSTEM_COPY } from "./copy";

export function MarketPhonePolicyCard({c,canWrite}:{c:typeof SYSTEM_COPY.zh;canWrite:boolean}) {
  const qc=useQueryClient();
  const q=useQuery({queryKey:["market-phone-policies"],queryFn:()=>api.marketPhonePolicies()});
  const [selectedMarket,setMarket]=React.useState("");
  const market=q.data?.find((x)=>x.market===selectedMarket)?.market ?? q.data?.find((x)=>x.market==="TW")?.market ?? q.data?.[0]?.market ?? "";
  const current=q.data?.find((x)=>x.market===market);
  const [form,setForm]=React.useState({recordOnly:true,smsVerificationEnabled:false,reason:""});
  React.useEffect(()=>{if(current)setForm({recordOnly:current.recordOnly,smsVerificationEnabled:current.smsVerificationEnabled,reason:""});},[current]);
  const save=useMutation({mutationFn:()=>api.saveMarketPhonePolicy(market,form),onSuccess:()=>{qc.invalidateQueries({queryKey:["market-phone-policies"]});notify.success(c.phonePolicySaved);}});
  if(q.error)return <DataTable columns={[]} rows={undefined} error={q.error} onRetry={()=>q.refetch()} rowKey={()=>""} empty={c.marketEmpty}/>;
  return <ConfigCard className="mt-4" title={c.phonePolicyTitle} notice={c.phonePolicyNotice} canSave={canWrite&&!!current&&!q.isFetching&&!!form.reason.trim()} saving={save.isPending} onSave={()=>save.mutate()}>
    <div className="space-y-3">
      <Label htmlFor="phone-policy-market">{c.phonePolicyRegion}</Label>
      <Select id="phone-policy-market" value={market} onChange={(e)=>setMarket(e.target.value)}>
        {(q.data??[]).map((x)=><option key={x.market} value={x.market}>{x.market} {x.callingPrefix}</option>)}
      </Select>
      <CheckboxField label={c.phoneRecordOnly} checked={form.recordOnly} disabled={!canWrite||!current} onChange={(v)=>setForm((f)=>({...f,recordOnly:v===true}))}/>
      <CheckboxField label={c.phoneSmsEnabled} checked={form.smsVerificationEnabled} disabled={!canWrite||!current?.smsChannelReady} onChange={(v)=>setForm((f)=>({...f,smsVerificationEnabled:v===true}))}/>
      {!current?.smsChannelReady&&<Notice tone="muted">{c.phoneSmsUnavailable}</Notice>}
      <Label htmlFor="phone-policy-reason">{c.phonePolicyReason}</Label>
      <Input id="phone-policy-reason" value={form.reason} disabled={!canWrite} onChange={(e)=>setForm((f)=>({...f,reason:e.target.value}))}/>
    </div>
  </ConfigCard>;
}
