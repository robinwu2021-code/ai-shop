"use client";
import {useState} from "react";
import {useMutation} from "@tanstack/react-query";
import {api} from "@/lib/api";
import type {AdmissionEntry,SelfOperatedStore} from "@/lib/types";
import {AdmissionDetailDrawer as Drawer,AdmissionDetailSection as DrawerSection,AdmissionDetailField as Field,AdmissionDetailGrid as FieldGrid} from "./admission-detail-layout";
import {Input} from "@/components/ui/input";
import {Label} from "@/components/ui/label";
import {Button} from "@/components/ui/button";
import {Notice} from "@/components/ui/notice";
import {notify} from "@/lib/notify";
import type {MerchantsCopy} from "./copy";

export function SelfOperatedDetails({c,entry,canAddStore,onClose}:{c:MerchantsCopy;entry:AdmissionEntry["selfOperated"];canAddStore:boolean;onClose:()=>void}) {
 return <Drawer open={!!entry} onOpenChange={open=>{if(!open)onClose();}} title={entry?.name??""}>
  {entry&&<SelfOperatedDetailBody key={entry.merchantNo} c={c} entry={entry} canAddStore={canAddStore} />}
 </Drawer>;
}
function SelfOperatedDetailBody({c,entry,canAddStore}:{c:MerchantsCopy;entry:NonNullable<AdmissionEntry["selfOperated"]>;canAddStore:boolean}) {
 const [name,setName]=useState("");const [address,setAddress]=useState("");const [stores,setStores]=useState<SelfOperatedStore[]>([]);
 const add=useMutation({mutationFn:()=>api.addSelfOperatedStore({merchantNo:entry.merchantNo,name:name.trim(),address:address.trim()||undefined}),onSuccess:store=>{setStores(prev=>[...prev,store]);setName("");setAddress("");notify.success(c.soStoreAdded);}});
 return <>
  <DrawerSection title={c.applySectionInfo} first><FieldGrid>
   <Field label={c.admissionType}>{c.admissionSelf}</Field><Field label={c.soScope}>{entry.serviceScope==="CITY"?c.soScopeCity:entry.serviceScope==="PLATFORM"?c.applyScopePlatform:c.soScopeCommunity}</Field>
   <Field label={c.soResultMerchant} wide>{entry.merchantNo}</Field>
   <Field label={c.admissionOwner} wide>{entry.ownerUserNo}</Field>
  </FieldGrid></DrawerSection>
  {canAddStore&&<DrawerSection title={c.soStoreSubmit}><form className="space-y-4" onSubmit={e=>{e.preventDefault();if(name.trim()&&!add.isPending)add.mutate();}}>
   <div className="space-y-1.5"><Label required htmlFor="admission-store-name">{c.soStoreName}</Label><Input id="admission-store-name" value={name} onChange={e=>setName(e.target.value)} disabled={add.isPending} /></div>
   <div className="space-y-1.5"><Label htmlFor="admission-store-address">{c.soStoreAddress}</Label><Input id="admission-store-address" value={address} onChange={e=>setAddress(e.target.value)} disabled={add.isPending} /></div>
   {add.error&&<Notice tone="danger">{add.error.message}</Notice>}
   <Button type="submit" disabled={!name.trim()||add.isPending} loading={add.isPending}>{c.soStoreSubmit}</Button>
  </form>{stores.map(s=><p key={s.storeNo} className="mt-3 txt-body">{s.name} · {s.storeNo}</p>)}</DrawerSection>}
 </>;
}
