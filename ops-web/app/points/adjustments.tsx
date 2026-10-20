"use client";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsAdjustmentSubmission, PointsAdjustmentReview } from "@/lib/api/points-adjustment-types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
export function PointsAdjustments({ canMake, canCheck }: { canMake: boolean; canCheck: boolean }) {
 const { t }=usePointsText(); const qc=useQueryClient();
 const types=useQuery({queryKey:["points-types"],queryFn:()=>api.pointsTypes()});
 const [selected,select]=useState(""); const code=selected || types.data?.[0]?.typeCode || "";
 const [after,setAfter]=useState(0); const [reason,setReason]=useState("");
 const page=useQuery({queryKey:["points-adjustments",code,after],queryFn:()=>api.pointsAdjustments({typeCode:code,after,limit:30}),enabled:!!code});
 const intent=useRef<{body:string;requestId:string} | undefined>(undefined);
 type Command={kind:"SUBMIT";value:PointsAdjustmentSubmission}|{kind:"REVIEW";value:PointsAdjustmentReview};
 const mutation=useMutation({mutationFn:(command:Command)=>command.kind==="SUBMIT"?api.submitPointsAdjustment(command.value):api.reviewPointsAdjustment(command.value),
  onSuccess:()=>{intent.current=undefined;void qc.invalidateQueries({queryKey:["points-adjustments"]});}});
 function send(value:Command){const next=pointsCommand(value,intent.current);intent.current=next.intent;
  const command={...value,value:{...value.value,requestId:next.command.requestId}} as Command;mutation.mutate(command);}
 const disabled=mutation.isPending || !reason.trim() || IS_MOCK;
 return <section className="space-y-3 rounded-card border border-border p-4">
  <h3>{t("用户积分调账 · 双人审批","User adjustments · maker and checker")}</h3>
  <p>{t("调增消耗平台预算；调减仅扣可用积分并释放回平台可发预算，待生效和占用积分不参与，不发起外部转账。","Increases consume platform budget; decreases release available points back to platform issuance budget without an external transfer. Pending and held points are excluded.")}</p>
  <label>{t("积分类型","Point type")}<Select value={code} onChange={e=>{select(e.target.value);setAfter(0);}}>{types.data?.map(x=><option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode}</option>)}</Select></label>
  <label>{t("操作原因","Reason")}<Input value={reason} maxLength={1000} onChange={e=>setReason(e.target.value)} /></label>
  {canMake && <form className="space-y-2" onSubmit={e=>{e.preventDefault();const d=new FormData(e.currentTarget);send({kind:"SUBMIT",value:{requestId:"",adjustmentNo:String(d.get("no")).trim(),typeCode:code,userNo:String(d.get("user")).trim(),direction:d.get("direction") as "INCREASE"|"DECREASE",points:Number(d.get("points")),reason:reason.trim()}});}}>
   <label>{t("申请号（重试保留原号）","Request number (retain for retry)")}<Input name="no" required maxLength={40}/></label>
   <label>{t("用户编号","User number")}<Input name="user" required maxLength={40}/></label>
   <Select name="direction"><option value="INCREASE">{t("调增","Increase")}</option><option value="DECREASE">{t("调减","Decrease")}</option></Select>
   <label>{t("积分数量","Points")}<Input name="points" type="number" min={1} max={Number.MAX_SAFE_INTEGER} step={1} required/></label>
   <Button type="submit" disabled={disabled || !code}>{t("提交调账申请","Submit adjustment")}</Button>
  </form>}
  {(mutation.error || page.error) && <p role="alert">{String(mutation.error?.message || page.error?.message)}</p>}
  {page.isFetching && <p>{t("加载中…","Loading…")}</p>}
  {page.data?.rows.map(r=><div key={r.id} className="border-t border-border py-2">
   <p>{r.adjustmentNo} · {r.userNo} · {r.direction} {r.points} · {r.state} · v{r.version}</p><p>{r.reason} · {r.submittedBy} / {r.approvedBy || "—"}</p>
   {canCheck && r.state==="SUBMITTED" && <div>{[true,false].map(approve=><Button key={String(approve)} disabled={disabled} onClick={()=>send({kind:"REVIEW",value:{requestId:"",adjustmentNo:r.adjustmentNo,typeCode:r.typeCode,expectedVersion:r.version,approve,reason:reason.trim()}})}>{approve?t("批准","Approve"):t("驳回","Reject")}</Button>)}</div>}
  </div>)}
  <Button disabled={!after || page.isFetching} onClick={()=>setAfter(0)}>{t("回到首页","First page")}</Button>
  <Button disabled={!page.data?.hasMore || page.isFetching} onClick={()=>setAfter(page.data!.next)}>{t("下一页","Next page")}</Button>
 </section>;
}
