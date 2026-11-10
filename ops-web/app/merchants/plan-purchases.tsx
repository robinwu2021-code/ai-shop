"use client";

import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {api} from "@/lib/api";
import {isApiError} from "@/lib/api/error";
import {useCan} from "@/lib/use-can";
import {useCopy} from "@/lib/use-copy";
import {parseMajor} from "@/lib/commerce-money";
import type {EntityPlanOffer,EntityPlanPurchase,EntityPlanConfirm} from "@/lib/entity-plan-purchase-types";
import {Button} from "@/components/ui/button";
import {Input,Select} from "@/components/ui/input";
import {DrawerSection,Field} from "@/components/ui/drawer";
import {PLAN_PURCHASES_COPY} from "./plan-purchases-copy";

export function EntityPlanPurchasesPanel({entityNo}:{entityNo:string}) {
 const c=useCopy(PLAN_PURCHASES_COPY),allow=useCan();
 const q=useQuery({queryKey:["entity-plan-purchases",entityNo],queryFn:()=>api.entityPlanPurchases(entityNo)});
 const offers=useQuery({queryKey:["entity-plan-offers",entityNo],queryFn:()=>api.entityPlanOffers(entityNo)});
 return <DrawerSection title={c.title} desc={c.hint}>
  <Button variant="outline" onClick={()=>q.refetch()}>{c.refresh}</Button>
  {q.error&&<p role="alert">{c.error}</p>}
  {q.data?.length===0&&<p>{c.empty}</p>}
  {q.data?.map(o=><Purchase key={o.orderNo} order={o} onChanged={()=>q.refetch()}/>)}
  {allow("system:param:update")&&<OfferEditor entityNo={entityNo} offers={offers.data??[]} onSaved={()=>offers.refetch()}/>}
 </DrawerSection>;
}
function OfferEditor({entityNo,offers,onSaved}:{entityNo:string;offers:EntityPlanOffer[];onSaved:()=>unknown}) {
 const c=useCopy(PLAN_PURCHASES_COPY);
 const [form,set]=useState({offerNo:"",planCode:"PRO",name:"",days:"30",price:"",currency:"TWD"});
 const [selected,select]=useState<EntityPlanOffer|null>(null),[busy,b]=useState(false),[error,e]=useState("");
 const field=(key:keyof typeof form,label:string)=><Field label={label}><Input value={form[key]} onChange={v=>set({...form,[key]:v.target.value})}/></Field>;
 async function save(enabled:boolean){
  const minor=parseMajor(form.price,form.currency);if(!minor||Number(minor)<=0||!Number.isSafeInteger(Number(minor))||!form.name.trim()||!form.offerNo.trim())return e(c.noProof);
  b(true);e("");try{await api.saveEntityPlanOffer(entityNo,{...form,expectedVersion:selected?.version??0,days:Number(form.days),price:{currency:form.currency,minor:Number(minor)},enabled});await onSaved();select(null);set({...form,offerNo:"",name:"",price:""});}catch{e(c.error);}finally{b(false);}
 }
 return <div className="space-y-3 mt-4"><h3>{c.offers}</h3><p>{c.configure}</p><p>{c.saveHint}</p>
  {offers.map(o=><Button key={o.offerNo} variant="outline" onClick={()=>{select(o);set({offerNo:o.offerNo,planCode:o.planCode,name:o.name,days:String(o.days),price:String(o.price.minor/100),currency:o.price.currency});}}>{o.name} · {o.price.currency} {o.price.minor/100} · {c.quota} {o.storeQuota}/{o.staffQuota} · {o.enabled?c.enabled:c.disable}</Button>)}
  <fieldset disabled={busy} className="space-y-2">{field("offerNo",c.offerNo)}{field("name",c.name)}<Select value={form.planCode} onChange={v=>set({...form,planCode:v.target.value})}><option>PRO</option><option>CHAIN</option></Select>{field("days",c.days)}<Select value={form.currency} onChange={v=>set({...form,currency:v.target.value})}><option>TWD</option><option>CNY</option></Select>{field("price",c.price)}<Button onClick={()=>save(true)}>{c.save}</Button>{selected&&<Button variant="outline" onClick={()=>save(false)}>{c.disable}</Button>}</fieldset>{error&&<p role="alert">{error}</p>}
 </div>;
}
function Purchase({order:o,onChanged}:{order:EntityPlanPurchase;onChanged:()=>unknown}) {
 const c=useCopy(PLAN_PURCHASES_COPY),allow=useCan();
 const [busy,b]=useState(false),[error,e]=useState(""),[evidence,k]=useState("");
 const [f,set]=useState({amount:"",at:"",reference:"",reason:""});
 const [attempt,a]=useState<EntityPlanConfirm|null>(null),[absent,markAbsent]=useState(false);
 const claim=o.claims.find(x=>x.status==="SUBMITTED");
 const canConfirm=allow("commerce:mall:offline:confirm")&&allow("merchant:merchant:ban");
 const canVoid=allow("commerce:mall:offline:void")&&allow("merchant:merchant:ban");
 const closing=["PREPARED","AWAITING_PAYMENT","CANCELLATION_REQUESTED"].includes(o.status);
 async function perform(work:()=>Promise<unknown>){b(true);e("");try{await work();await onChanged();}catch{e(c.error);await onChanged();}finally{b(false);}}
 async function confirm(command?:EntityPlanConfirm){
  const minor=parseMajor(f.amount,o.offer.price.currency);
  if(!command&&(!minor||!Number.isSafeInteger(Number(minor))||!f.at||!f.reason.trim()||!f.reference.trim()||!evidence||!claim))return e(c.noProof);
  const cmd=command??{actionNo:crypto.randomUUID(),claimNo:claim!.claimNo,amount:{currency:o.offer.price.currency,minor:Number(minor)},paidAt:new Date(f.at).toISOString().replace(/\.\d{3}Z$/,"Z"),bankReference:f.reference.trim(),evidenceKey:evidence,reason:f.reason.trim()};
  a(cmd);markAbsent(false);b(true);e("");try{const r=await api.confirmEntityPlanPurchase(o.entityNo,o.orderNo,cmd);if(r.status!=="ACCEPTED")a(null);await onChanged();}catch(err){
   if(isApiError(err)&&err.code===10400){
    try {const fresh=await api.entityPlanPurchase(o.entityNo,o.orderNo);if(!fresh.actions.some(x=>x.actionNo===cmd.actionNo)){a(null);e(c.noProof);}else e(c.unknown);}catch{e(c.unknown);}
   }else e(c.unknown);
  }finally{b(false);}
 }
 async function lookup(){if(!attempt)return;await perform(async()=>{const fresh=await api.entityPlanPurchase(o.entityNo,o.orderNo),found=fresh.actions.find(x=>x.actionNo===attempt.actionNo);if(found&&found.status!=="ACCEPTED")a(null);markAbsent(!found);});}
 const status=(s:string)=>c[s as keyof typeof c]??s;
 return <section className="border rounded-card p-3 mt-3 space-y-2"><strong>{o.offer.name}</strong><p>{o.orderNo}</p><p>{o.offer.price.currency} {o.offer.price.minor/100} · {status(o.status)}</p>
  {o.instructions&&<p>{Object.values(o.instructions.instructions).join(" · ")}</p>}
  {o.actions.map(x=><p key={x.actionNo}>{status(x.status)} · {x.reason}</p>)}
  {o.claims.map(x=><div key={x.claimNo}><p>{status(x.status)} · {x.declaration.amount.currency} {x.declaration.amount.minor/100} · {x.reviewNote}</p><Button variant="outline" onClick={()=>perform(async()=>{const r=await api.entityPlanEvidenceUrl(o.entityNo,o.orderNo,x.declaration.evidenceKey);window.open(r.url,"_blank","noopener,noreferrer");})}>{c.buyerProof}</Button></div>)}
  {attempt?<div><p role="status">{c.unknown}</p><Button disabled={busy} onClick={lookup}>{c.lookup}</Button>{absent&&<Button disabled={busy} onClick={()=>confirm(attempt)}>{c.retry}</Button>}</div>:null}
  {((claim&&canConfirm)||(closing&&canVoid))&&!attempt&&<fieldset disabled={busy} className="space-y-2">
   <Field label={c.price}><Input value={f.amount} onChange={v=>set({...f,amount:v.target.value})}/></Field><Field label={c.paidAt}><Input type="datetime-local" step="1" value={f.at} onChange={v=>set({...f,at:v.target.value})}/></Field><Field label={c.reference}><Input value={f.reference} onChange={v=>set({...f,reference:v.target.value})}/></Field><Field label={c.reason}><Input value={f.reason} onChange={v=>set({...f,reason:v.target.value})}/></Field>
   <Field label={c.proof}><Input type="file" accept="image/png,image/jpeg" onChange={v=>{const file=v.target.files?.[0];if(file)void perform(async()=>{const r=await api.reserveEntityPlanEvidence(o.entityNo,o.orderNo);await api.uploadEntityPlanEvidence(o.entityNo,o.orderNo,r.assetKey,file);k(r.assetKey);});}}/></Field>
   {evidence&&<span>✓</span>}{claim&&canConfirm&&<Button onClick={()=>confirm()}>{c.confirm}</Button>}{closing&&canVoid&&<Button disabled={!evidence||!f.reference.trim()||!f.reason.trim()} onClick={()=>perform(()=>api.cancelEntityPlanPurchase(o.entityNo,o.orderNo,{actionNo:crypto.randomUUID(),reference:f.reference.trim(),evidenceKey:evidence,reason:f.reason.trim()}))}>{c.cancel}</Button>}
  </fieldset>}
  {claim&&!attempt&&allow("commerce:mall:offline:review")&&allow("merchant:merchant:ban")&&<div><Field label={c.reason}><Input disabled={busy} value={f.reason} onChange={v=>set({...f,reason:v.target.value})}/></Field><Button disabled={busy||!f.reason.trim()} onClick={()=>perform(()=>api.declineEntityPlanPurchase(o.entityNo,o.orderNo,claim.claimNo,f.reason.trim()))}>{c.decline}</Button></div>}
  {((canConfirm&&["PREPARED","CONFIRMING"].includes(o.status))||(canVoid&&o.status==="CLOSING"))&&<Button disabled={busy} onClick={()=>perform(()=>api.recoverEntityPlanPurchase(o.entityNo,o.orderNo))}>{c.recover}</Button>}

  {error&&<p role="alert">{error}</p>}
 </section>;
}
