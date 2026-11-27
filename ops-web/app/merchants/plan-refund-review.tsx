"use client";

import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/drawer";
import {Input} from "@/components/ui/input";
import {api} from "@/lib/api";
import type {PlanReviewQuota} from "@/lib/entity-plan-purchase-types";
import {useCopy} from "@/lib/use-copy";

import {PLAN_REFUND_REVIEW_COPY} from "./plan-purchases-copy";

function Quota({label,value}:{label:string;value:PlanReviewQuota}) {
  const c=useCopy(PLAN_REFUND_REVIEW_COPY);
  return <div><strong>{label} · {value.planCode}</strong>
    <p>{c.base} ({c.limits}) · {value.stores??c.unknown} / {value.staff??c.unknown}</p>
    <p>{c.override} ({c.limits}) · {value.overridesKnown?`${value.storeOverride??c.none} / ${value.staffOverride??c.none}`:c.unknown}</p>
    <p>{c.stats} · {value.crossStore===null?c.unknown:value.crossStore?c.yes:c.no} · {c.expiry} · {value.expiry===null?c.none:new Date(value.expiry).toLocaleDateString()}</p>
  </div>;
}

export function PlanRefundReviewPanel({entityNo,orderNo,canClose,onChanged}:{entityNo:string;orderNo:string;canClose:boolean;onChanged:()=>unknown}) {
  const c=useCopy(PLAN_REFUND_REVIEW_COPY);
  const q=useQuery({queryKey:["entity-plan-refund-review",entityNo,orderNo],queryFn:()=>api.entityPlanRefundReview(entityNo,orderNo),refetchOnWindowFocus:false});
  const [draft,setDraft]=useState({reason:"",revision:""}),[busy,setBusy]=useState(false),[failed,setFailed]=useState(false);
  const review=q.data;
  async function reload(){
    setDraft({reason:"",revision:""});
    const fresh=await q.refetch();
    setFailed(fresh.isError);
    if(fresh.data?.decision) await onChanged();
  }
  async function close(){
    if(!review||!draft.reason.trim())return;
    setBusy(true);
    try {
      await api.closeEntityPlanRefundReview(entityNo,orderNo,{refundNo:review.refundNo,quotaRevision:draft.revision,reason:draft.reason.trim()});
      await reload();
    } catch {setFailed(true);} finally {setBusy(false);}
  }
  return <section className="space-y-2"><h4>{c.title}</h4>
    {review&&<>
      <p>{c.refund} · {review.refundNo} · {c.store} · {review.storeNo}</p>
      <Quota label={c.before} value={review.before}/><Quota label={c.current} value={review.current}/>
      {review.decision?<><Quota label={c.retained} value={review.decision.retainedQuota}/>
        <p>{c.closed} · {review.decision.operatorNo} · {new Date(review.decision.reviewedAt).toLocaleString()}</p><p>{review.decision.command.reason}</p></>
        :canClose&&<><Field label={c.reason}><Input value={draft.reason} maxLength={500} onChange={e=>setDraft({reason:e.target.value,revision:draft.reason?draft.revision:review.quotaRevision})}/></Field>
          <Button disabled={busy||failed||q.isError||!draft.reason.trim()} onClick={close}>{c.close}</Button></>}
    </>}
    {(q.isError||failed)&&<><p role="alert">{c.error}</p><Button disabled={busy} onClick={reload}>{c.refresh}</Button></>}
  </section>;
}
