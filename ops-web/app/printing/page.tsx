"use client";

import { useRef, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PrintTarget, PrintIncident, PrintOperation } from "@/lib/api/print-types";
import { scopedTarget, reconcileRequest } from "@/lib/print-management";
import { useAuth } from "@/lib/auth";
import { useCan } from "@/lib/use-can";
import { useServerMenu, isPointUnimplemented } from "@/lib/stores/server-menu";
import { useCopy } from "@/lib/use-copy";
import { TabHeader } from "@/components/ui/tab-header";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { HelpNote } from "@/components/ui/help-note";
import { DataTable, type Column } from "@/components/ui/data-table";
import { PRINT_COPY } from "./copy";

const READ="ops:print:read", RESOLVE="ops:print:resolve";
type Copy=typeof PRINT_COPY.zh;
const value=(f:FormData,k:string)=>String(f.get(k)??"");

export default function PrintingPage(){
 const c=useCopy(PRINT_COPY),can=useCan();
 const auth=useAuth(s=>[s.username,s.token,s.tenantNo,s.merchantNo,s.communityNo,s.pickupNo,s.perms.join(",")].join("|"));
 const tenant=useAuth(s=>s.tenantNo);
 const closed=useServerMenu(s=>isPointUnimplemented(s.byHref,"/printing"));
 return <div className="space-y-4">
  <TabHeader tabs={[{key:"printing",label:c.title}]} value="printing" onChange={()=>{}} desc={c.desc}/>
  <HelpNote>{c.notice}</HelpNote>
  {!can(READ)?<p role="alert">{c.readDenied}</p>:closed?<p role="alert">{c.closed}</p>:IS_MOCK?<p role="status">{c.mock}</p>:<Target key={auth} tenant={tenant} c={c} resolve={can(RESOLVE)}/>}
 </div>;
}

function Target({tenant,c,resolve}:{tenant:string;c:Copy;resolve:boolean}){
 const [target,setTarget]=useState<PrintTarget|null>(null),[error,setError]=useState(false);
 function load(e:FormEvent<HTMLFormElement>){e.preventDefault();const f=new FormData(e.currentTarget);try{setTarget(scopedTarget({tenantNo:value(f,"tenantNo"),entityNo:value(f,"entityNo"),storeNo:value(f,"storeNo")}));setError(false);}catch{setError(true);}}
 return <>
  <form className="grid gap-3 md:grid-cols-4" onSubmit={load} onChange={()=>{setTarget(null);setError(false);}}>
   {([["tenantNo",c.tenant,tenant],["entityNo",c.entity,""],["storeNo",c.store,""]] as const).map(([name,label,initial])=><label key={name}>{label}<Input name={name} defaultValue={initial} maxLength={name==="tenantNo"?32:64} required/></label>)}
   <Button type="submit">{c.load}</Button>
  </form>
  {error&&<p role="alert">{c.invalid}</p>}
  {target&&<Scoped key={JSON.stringify(target)} target={target} c={c} resolve={resolve}/>}
 </>;
}
function Scoped({target,c,resolve}:{target:PrintTarget;c:Copy;resolve:boolean}){
 const [session]=useState(()=>crypto.randomUUID());
 const [page,setPage]=useState(1),[status,setStatus]=useState(""),[filter,setFilter]=useState("");
 const health=useQuery({queryKey:["print-health",session,target],queryFn:()=>api.printHealth(target)});
 const incidents=useQuery({queryKey:["print-incidents",session,target,status,page],queryFn:()=>api.printIncidents({...target,status,page,size:20})});
 const cols:Column<PrintIncident>[]=[
  {header:c.incident,cell:r=>r.incidentNo},{header:c.kind,cell:r=>r.incidentType},
  {header:c.target,cell:r=>`${r.targetType} / ${r.targetNo}`},{header:c.severity,cell:r=>r.severity},
  {header:c.state,cell:r=>r.status},{header:c.count,cell:r=>r.occurrenceCount,numeric:true},{header:c.seen,cell:r=>r.lastSeenAt},
 ];
 return <div className="space-y-4">
  <p>{c.range}: {target.tenantNo} / {target.entityNo} / {target.storeNo}</p>
  <section className="space-y-2 rounded-card border p-4"><h2>{c.health}</h2>
   {health.isPending&&<p role="status">…</p>}
   {health.error&&<p role="alert">{health.error.message}<Button onClick={()=>health.refetch()}>{c.retry}</Button></p>}
   {health.data&&<><time>{health.data.asOf}</time>{health.data.stores.map((s,i)=><dl key={i} className="grid grid-cols-2 gap-2 md:grid-cols-4">
    {([[c.active,s.activePrinters],[c.pending,s.pendingJobs],[c.unknown,s.unknownJobs],[c.blocked,s.blockedRequests]] as const).map(([k,v])=><div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
   </dl>)}</>}
  </section>
  <section className="space-y-2"><h2>{c.incidents}</h2>
   <form onSubmit={e=>{
    e.preventDefault();
    const nextStatus=filter.trim();
    // An unchanged query key does not trigger a request; explicit refresh must do so.
    if(nextStatus===status&&page===1)void incidents.refetch();
    else{setStatus(nextStatus);setPage(1);}
   }}><label>{c.status}<Input name="status" value={filter} onChange={e=>setFilter(e.target.value)}/></label><Button type="submit">{c.refresh}</Button></form>
   <DataTable columns={cols} rows={incidents.data?.records} loading={incidents.isPending} error={incidents.error} onRetry={()=>incidents.refetch()} rowKey={r=>r.incidentNo} empty={c.empty}/>
   <div className="flex items-center gap-3"><Button disabled={page<=1||incidents.isFetching} onClick={()=>setPage(p=>p-1)}>{c.previous}</Button><span>{page} · {c.total}: {incidents.data?.total??"—"}</span><Button disabled={!incidents.data||page*20>=incidents.data.total||incidents.isFetching} onClick={()=>setPage(p=>p+1)}>{c.next}</Button></div>
  </section>
  <Operations target={target} c={c} resolve={resolve} session={session}/>
 </div>;
}
function Operations({target,c,resolve,session}:{target:PrintTarget;c:Copy;resolve:boolean;session:string}){
 const [dryRun,setDryRun]=useState(true),[pending,setPending]=useState(false),[error,setError]=useState<string|null>(null);
 const [submitted,setSubmitted]=useState<PrintOperation|null>(null),[lookup,setLookup]=useState("");
 // Synchronous lock closes the double-submit window before React rerenders. An exact failed intent retains its key.
 const lock=useRef(false),intent=useRef<{fingerprint:string;key:string}|null>(null);
 const operation=useQuery({queryKey:["print-operation",session,target,lookup],queryFn:()=>api.printOperation(lookup,target),enabled:!!lookup});
 async function submit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();if(lock.current||!resolve)return;const f=new FormData(e.currentTarget);
  let request;try{request=reconcileRequest(target,value(f,"sourceKind"),value(f,"from"),value(f,"to"),dryRun,value(f,"reason"));if(!dryRun&&f.get("confirm")!=="on")throw Error();}catch{setError(c.invalid);return;}
  const fingerprint=JSON.stringify(request);
  if(intent.current?.fingerprint!==fingerprint)intent.current={fingerprint,key:crypto.randomUUID()};
  lock.current=true;setPending(true);setError(null);setSubmitted(null);setLookup("");
  try{const result=await api.reconcilePrint(request,intent.current.key);setSubmitted(result);}catch(e){setError(e instanceof Error?e.message:String(e));}finally{lock.current=false;setPending(false);}
 }
 return <>
  <section className="space-y-2 rounded-card border p-4"><h2>{c.reconcile}</h2>
   {!resolve?<ReadOnlyNotice what={c.reconcile} perm={RESOLVE} note={c.readonly}/>:<form onSubmit={submit} onChange={()=>{if(!lock.current){intent.current=null;setSubmitted(null);setError(null);}}} className="space-y-3">
    <fieldset disabled={pending} className="space-y-3">
     <label>{c.source}<Select name="sourceKind" defaultValue="SUB_ORDER" required>
      <option value="SUB_ORDER">{c.sourceOrder}</option>
      <option value="REFUND">{c.sourceRefund}</option>
      <option value="RECHARGE">{c.sourceRecharge}</option>
     </Select></label>
     <div className="grid gap-3 md:grid-cols-2"><label>{c.from}<Input type="datetime-local" name="from" required/></label><label>{c.to}<Input type="datetime-local" name="to" required/></label></div>
     <p className="txt-caption text-muted-foreground">{c.rangeHint}</p>
     <label>{c.reason}<Input name="reason" required/></label>
     <label className="flex gap-2"><input name="dryRun" type="checkbox" checked={dryRun} onChange={e=>setDryRun(e.target.checked)}/>{c.dryRun}</label>
     {!dryRun&&<label className="flex gap-2"><input key="execute" name="confirm" type="checkbox" required/>{c.execute} ({target.tenantNo} / {target.entityNo} / {target.storeNo})</label>}
     <Button type="submit" disabled={pending}>{pending?"…":c.submit}</Button>
    </fieldset>
   </form>}
   {error&&<p role="alert">{error}</p>}
  </section>
  <section className="space-y-2"><h2>{c.operation}</h2>
   <form onChange={()=>{setLookup("");setSubmitted(null);}} onSubmit={e=>{e.preventDefault();const no=value(new FormData(e.currentTarget),"operationNo").trim();if(!no||no.length>64){setError(c.invalid);return;}setSubmitted(null);setLookup(no);if(no===lookup)void operation.refetch();}}>
    <fieldset disabled={pending}><label>{c.operationNo}<Input name="operationNo" maxLength={64} required/></label><Button type="submit">{c.lookup}</Button></fieldset>
   </form>
   {operation.isFetching&&<p role="status">…</p>}
   {operation.error&&lookup&&<p role="alert">{operation.error.message}<Button onClick={()=>operation.refetch()}>{c.retry}</Button></p>}
   {(submitted||operation.data)&&<Operation result={(submitted??operation.data)!} c={c}/>}
   {!submitted&&!lookup&&<p>{c.noOperation}</p>}
  </section>
 </>;
}
function Operation({result:r,c}:{result:PrintOperation;c:Copy}){
 return <article className="space-y-2 rounded-card border p-4" data-operation={r.operationNo}>
  <p>{r.operationNo} · {c[r.status]} · {r.targetType} / {r.targetNo}</p><time>{r.createdAt}</time>{r.finishedAt&&<time>{r.finishedAt}</time>}
  {r.result&&<details><summary>{c.result}</summary><pre className="overflow-auto">{r.result.canonicalJson}</pre></details>}
  {r.error&&<p role="alert">{c.trace}: {r.error.key} / {r.error.traceNo}</p>}
 </article>;
}
