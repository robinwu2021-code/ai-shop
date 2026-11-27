"use client";

import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/drawer";
import {Input} from "@/components/ui/input";
import {api} from "@/lib/api";
import type {PlanReviewQuota} from "@/lib/entity-plan-purchase-types";
import {useCopy} from "@/lib/use-copy";

const copy = {
  zh:{title:"退款额度复核",refund:"退款号",store:"门店号",before:"购买前额度",current:"当前额度",retained:"结单时保留的额度",limits:"门店 / 员工",base:"基础额度",override:"单独约定",none:"无",unknown:"未留存",stats:"跨店统计",yes:"开",no:"关",expiry:"到期",reason:"复核说明",close:"保留当前额度并结单",closed:"已确认保留额度",refresh:"重新读取",error:"请重新读取复核记录，核对额度后再提交。"},
  en:{title:"Refund quota review",refund:"Refund",store:"Store",before:"Before purchase",current:"Current quota",retained:"Quota retained at closure",limits:"Stores / staff",base:"Base quota",override:"Overrides",none:"None",unknown:"Not recorded",stats:"Cross-store reporting",yes:"On",no:"Off",expiry:"Expiry",reason:"Review notes",close:"Keep current quota and close",closed:"Quota retention confirmed",refresh:"Reload",error:"Reload the review and check the quota before submitting."},
};

function Quota({label,value}:{label:string;value:PlanReviewQuota}) {
  const c=useCopy(copy);
  return <div><strong>{label} · {value.planCode}</strong>
    <p>{c.base} ({c.limits}) · {value.stores??c.unknown} / {value.staff??c.unknown}</p>
    <p>{c.override} ({c.limits}) · {value.overridesKnown?`${value.storeOverride??c.none} / ${value.staffOverride??c.none}`:c.unknown}</p>
    <p>{c.stats} · {value.crossStore===null?c.unknown:value.crossStore?c.yes:c.no} · {c.expiry} · {value.expiry===null?c.none:new Date(value.expiry).toLocaleDateString()}</p>
  </div>;
}

export function PlanRefundReviewPanel({entityNo,orderNo,canClose,onChanged}:{entityNo:string;orderNo:string;canClose:boolean;onChanged:()=>unknown}) {
  const c=useCopy(copy);
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
