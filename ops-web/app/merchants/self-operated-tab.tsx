"use client";
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { SelfOperatedResult, SelfOperatedStore } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { ConfigCard } from "@/components/ui/config-card";
import { Input,Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Textarea } from "@/components/ui/textarea";
import { DataTable } from "@/components/ui/data-table";
import type { MerchantsCopy } from "./copy";

export function SelfOperatedTab({c,canCreate}:{c:MerchantsCopy;canCreate:boolean}) {
 const context=useQuery({queryKey:["self-operated-context"],queryFn:()=>api.selfOperatedContext(),enabled:canCreate});
 const phoneOwner=context.data?.market==="CN" && !context.data.recordOnly;
 const [ownerEmail,setOwnerEmail]=useState("");const [phone,setPhone]=useState("");
 const [name,setName]=useState("");const [description,setDescription]=useState("");const [industry,setIndustry]=useState("");
 const [scope,setScope]=useState("COMMUNITY");const [communityNos,setCommunityNos]=useState<string[]>([]);
 const [done,setDone]=useState<SelfOperatedResult|null>(null);
 const [storeName,setStoreName]=useState("");const [storeAddress,setStoreAddress]=useState("");const [addedStores,setAddedStores]=useState<SelfOperatedStore[]>([]);
 const industries=useQuery({queryKey:["industries","for-self-operated"],queryFn:()=>api.listIndustries(),enabled:canCreate});
 const communities=useQuery({queryKey:["communities","for-self-operated"],queryFn:()=>api.listCommunities({page:1,size:200}),enabled:canCreate});
 const create=useMutation({mutationFn:()=>api.createSelfOperated({phone:phoneOwner?phone.trim():undefined,ownerEmail:phoneOwner?undefined:ownerEmail.trim(),name:name.trim(),serviceScope:scope,industry:industry||undefined,communityNos:scope==="COMMUNITY"?communityNos:[],description:description.trim()||undefined}),onSuccess:r=>{setDone(r);if(done?.merchantNo!==r.merchantNo)setAddedStores([]);notify.success(r.created?c.soToastCreated:c.soToastIdempotent);}});
 const addStore=useMutation({mutationFn:()=>api.addSelfOperatedStore({merchantNo:done!.merchantNo,name:storeName.trim(),address:storeAddress.trim()||undefined}),onSuccess:st=>{setAddedStores(prev=>[...prev,st]);setStoreName("");setStoreAddress("");notify.success(c.soStoreAdded);}});
 const ready=!!context.data?.marketEnabled && !context.isError && !!name.trim() && (phoneOwner?/^1[3-9]\d{9}$/.test(phone.trim()):/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(ownerEmail.trim())) && (scope!=="COMMUNITY" || communityNos.length>0);
 if(!canCreate)return <Notice tone="danger">{c.soNoPerm}</Notice>;
 return <div className="space-y-4">
  <form className="space-y-5 rounded-card border bg-card p-5" onSubmit={e=>{e.preventDefault();if(ready&&!create.isPending)create.mutate();}}>
   <p className="txt-caption text-muted-foreground">{c.obSystemMarket}：{context.data?.market??"—"}</p>
   {context.error&&<Notice tone="danger">{context.error.message}</Notice>}
   {industries.error&&<Notice tone="danger">{industries.error.message}</Notice>}
   {communities.error&&<Notice tone="danger">{communities.error.message}</Notice>}
   {create.error&&<Notice tone="danger">{create.error.message}</Notice>}
   <fieldset className="grid gap-4 sm:grid-cols-2" disabled={create.isPending}>
    <div className="space-y-1.5"><Label required htmlFor="so-owner">{phoneOwner?c.soPhone:c.obOwnerEmail}</Label><Input id="so-owner" type={phoneOwner?"text":"email"} value={phoneOwner?phone:ownerEmail} maxLength={phoneOwner?32:254} onChange={e=>phoneOwner?setPhone(e.target.value):setOwnerEmail(e.target.value)} /></div>
    <div className="space-y-1.5"><Label required htmlFor="so-name">{c.soName}</Label><Input id="so-name" value={name} onChange={e=>setName(e.target.value)} /></div>
    <div className="space-y-1.5"><Label htmlFor="so-industry">{c.soIndustry}</Label><Select className="w-full" id="so-industry" value={industry} onChange={e=>setIndustry(e.target.value)}><option value="">{c.obChooseIndustry}</option>{(industries.data??[]).filter(i=>i.enabled).map(i=><option key={i.industry} value={i.industry}>{i.name}</option>)}</Select></div>
    <div className="space-y-1.5"><Label required htmlFor="so-scope">{c.soScope}</Label><Select className="w-full" id="so-scope" value={scope} onChange={e=>setScope(e.target.value)}><option value="COMMUNITY">{c.soScopeCommunity}</option><option value="CITY">{c.soScopeCity}</option></Select></div>
    {scope==="COMMUNITY"&&<div className="space-y-2 sm:col-span-2"><Label required>{c.soCommunities}</Label><div className="flex flex-wrap gap-3">{(communities.data?.records??[]).map(cm=><CheckboxField key={cm.communityNo} label={cm.name} checked={communityNos.includes(cm.communityNo)} onChange={()=>setCommunityNos(prev=>prev.includes(cm.communityNo)?prev.filter(x=>x!==cm.communityNo):[...prev,cm.communityNo])} />)}</div>{!communities.isPending && !communities.isError && !communities.data?.records.length && <Notice tone="danger">{c.soNoCommunities}</Notice>}</div>}
   </fieldset>
   <details className="border-t pt-3"><summary className="cursor-pointer txt-body">{c.obOptional}</summary><div className="mt-3 space-y-1.5"><Label htmlFor="so-description">{c.soDescription}</Label><Textarea id="so-description" value={description} onChange={setDescription} rows={2} disabled={create.isPending} /></div></details>
   <div className="flex justify-end"><Button type="submit" disabled={!ready||create.isPending} loading={create.isPending}>{c.soSubmit}</Button></div>
  </form>
  {done&&<ConfigCard title={c.soResultTitle}>
   <dl className="mb-4 grid gap-3 sm:grid-cols-3">{[[c.soResultMerchant,done.merchantNo],[c.soResultStore,done.storeNo],[c.soResultOwner,done.ownerUserNo]].map(([label,value])=><div key={label}><dt className="txt-caption text-muted-foreground">{label}</dt><dd className="txt-body tabular-nums">{value}</dd></div>)}</dl>
   {done.reachableCommunities===0&&<Notice tone="danger" className="mb-3">{c.soResultReachZero}</Notice>}
   {addedStores.length>0&&<DataTable columns={[{header:c.soStoreName,cell:(st:SelfOperatedStore)=>st.name},{header:c.soResultStore,cell:(st:SelfOperatedStore)=>st.storeNo}]} rows={addedStores} rowKey={st=>st.storeNo} />}
   <form className="mt-4 flex flex-wrap items-end gap-3" onSubmit={e=>{e.preventDefault();if(storeName.trim()&&!addStore.isPending)addStore.mutate();}}>
    <div className="min-w-0 flex-1 space-y-1"><Label required htmlFor="so-store-name">{c.soStoreName}</Label><Input id="so-store-name" value={storeName} disabled={addStore.isPending} onChange={e=>setStoreName(e.target.value)} /></div>
    <div className="min-w-0 flex-1 space-y-1"><Label htmlFor="so-store-address">{c.soStoreAddress}</Label><Input id="so-store-address" value={storeAddress} disabled={addStore.isPending} onChange={e=>setStoreAddress(e.target.value)} /></div>
    <Button type="submit" disabled={!storeName.trim()||addStore.isPending} loading={addStore.isPending}>{c.soStoreSubmit}</Button>
   </form>
   {addStore.error&&<Notice tone="danger" className="mt-3">{addStore.error.message}</Notice>}
   <details className="mt-4 border-t pt-3"><summary className="cursor-pointer txt-caption">{c.soMoreResults}</summary><dl className="mt-3 grid grid-cols-2 gap-2 txt-caption"><dt>{c.soResultSelfOp}</dt><dd>{done.selfOperated?c.soYes:c.soNo}</dd><dt>{c.soResultReach}</dt><dd>{done.reachableCommunities}</dd><dt>{c.soResultFunds}</dt><dd>{done.fundsMode==="AGGREGATED"?c.fundsAggregated:done.fundsMode==="DIRECT"?c.fundsDirect:done.fundsMode}</dd><dt>{c.soResultBusiness}</dt><dd>{done.businessMode==="SELF_OPERATED"?c.soBusinessSelf:done.businessMode}</dd><dt>{c.soResultScope}</dt><dd>{done.serviceScope==="CITY"?c.soScopeCity:done.serviceScope==="COMMUNITY"?c.soScopeCommunity:done.serviceScope==="PLATFORM"?c.applyScopePlatform:done.serviceScope}</dd></dl></details>
  </ConfigCard>}
 </div>;
}
