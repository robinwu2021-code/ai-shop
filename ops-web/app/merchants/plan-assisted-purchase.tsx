"use client";
import {useState,useRef} from "react";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {useAuth} from "@/lib/auth";
import {useFinancialLock} from "@/components/offline/financial-lock";
import {isApiError} from "@/lib/api/error";
import {api} from "@/lib/api";
import {useCopy} from "@/lib/use-copy";
import type {EntityPlanOffer,EntityAssistedRequest} from "@/lib/entity-plan-purchase-types";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/input";
import {Field} from "@/components/ui/drawer";
import {ASSISTED_PURCHASE_COPY} from "./plan-purchases-copy";
type Props={entityNo:string;offers:EntityPlanOffer[];onChanged:()=>unknown};
type PendingAssistance={command:EntityAssistedRequest;refused:boolean};
export function EntityAssistedPurchase(props:Props){
 const username=useAuth(s=>s.username),tenant=useAuth(s=>s.tenantNo);
 return <EntityAssistedForm key={JSON.stringify([tenant,username,props.entityNo])} {...props} operatorKey={[tenant,username]}/>;
}
function EntityAssistedForm({entityNo,offers,onChanged,operatorKey}:{operatorKey:string[]} & Props){
 const client=useQueryClient(),cacheKey=["entity-assisted-pending",...operatorKey,entityNo];
 const stored=client.getQueryData<PendingAssistance>(cacheKey);
 const c=useCopy(ASSISTED_PURCHASE_COPY),q=useQuery({queryKey:["entity-assistance",entityNo],queryFn:()=>api.entityPlanAssistanceContext(entityNo)});
 const [offerNo,setOffer]=useState(""),[storeNo,setStore]=useState(""),[ownerAccountNo,setOwner]=useState("");
 const [pending,setPending]=useState<EntityAssistedRequest|null>(stored?.command??null),[absent,setAbsent]=useState(false),[busy,b]=useState(false),[error,e]=useState("");
 useFinancialLock(`assisted:${entityNo}`,!!pending||busy);
 const refused=useRef(stored?.refused??false);
 function forget(command:EntityAssistedRequest){
  if(client.getQueryData<PendingAssistance>(cacheKey)?.command.requestNo===command.requestNo)client.removeQueries({queryKey:cacheKey,exact:true});
  setPending(null);
 }
 async function submit(cmd:EntityAssistedRequest){
  const prior=client.getQueryData<PendingAssistance>(cacheKey);
  if(prior&&prior.command.requestNo!==cmd.requestNo){setPending(prior.command);refused.current=prior.refused;return;}
  // The root QueryClient survives host tabs/routes; unresolved keys must not expire on unmount.
  client.setQueryDefaults(["entity-assisted-pending"],{gcTime:Infinity});
  client.setQueryData(cacheKey,{command:cmd,refused:false});
  setPending(cmd);setAbsent(false);b(true);e("");
  try{await api.assistedEntityPlanPurchase(entityNo,cmd);forget(cmd);await onChanged();}
  catch(err){
   refused.current=isApiError(err)&&[10400,10403,10404,10409].includes(err.code);
   const cached=client.getQueryData<PendingAssistance>(cacheKey);
   if(cached?.command.requestNo===cmd.requestNo)client.setQueryData(cacheKey,{...cached,refused:refused.current});
   e(c.error);
  }finally{b(false);}
 }
 async function lookup(){if(!pending)return;b(true);e("");try{const rows=await api.entityPlanPurchases(entityNo);if(rows.some(o=>o.requestNo===pending.requestNo)){forget(pending);await onChanged();}else if(refused.current)forget(pending);else setAbsent(true);}catch{e(c.error);}finally{b(false);}}
 return <section className="border rounded-card p-3 mt-3 space-y-2"><h3>{c.title}</h3><p>{c.hint}</p>{(error||q.error)&&<p role="alert">{error||c.error}</p>}
 {pending?<><p>{c.unknown} {pending.requestNo} · {pending.offerNo} · {pending.storeNo} · {pending.ownerAccountNo}</p><Button disabled={busy} onClick={()=>void lookup()}>{c.lookup}</Button>{absent&&<Button disabled={busy} onClick={()=>void submit(pending)}>{c.retry}</Button>}</>:<fieldset disabled={busy||!q.data} className="space-y-2">
 <Field label={c.owner}><Select value={ownerAccountNo} onChange={v=>setOwner(v.target.value)}><option value="">{c.pick}</option>{q.data?.owners.map(o=><option key={o.accountNo} value={o.accountNo}>{o.displayName} · {o.accountNo} · {o.subjectKind}</option>)}</Select></Field>
 <Field label={c.store}><Select value={storeNo} onChange={v=>setStore(v.target.value)}><option value="">{c.pick}</option>{q.data?.stores.map(s=><option key={s.storeNo} value={s.storeNo}>{s.name} · {s.storeNo}</option>)}</Select></Field>
 <Field label={c.offer}><Select value={offerNo} onChange={v=>setOffer(v.target.value)}><option value="">{c.pick}</option>{offers.filter(o=>o.enabled).map(o=><option key={o.offerNo} value={o.offerNo}>{o.name} · {o.price.currency} {o.price.minor/100}</option>)}</Select></Field>
 <Button disabled={!ownerAccountNo||!storeNo||!offers.some(o=>o.enabled&&o.offerNo===offerNo)} onClick={()=>{const offer=offers.find(o=>o.offerNo===offerNo)!;void submit({requestNo:crypto.randomUUID(),offerNo,offerVersion:offer.version,storeNo,ownerAccountNo});}}>{c.buy}</Button>
 </fieldset>}</section>;
}
