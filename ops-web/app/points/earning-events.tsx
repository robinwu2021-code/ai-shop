"use client";
import {useRef,useState} from "react";
import {useMutation,useQuery,useQueryClient} from "@tanstack/react-query";
import {api} from "@/lib/api";
import type {PointsEventView} from "@/lib/types";
import {pointsCommand} from "@/lib/points-command";
import {usePointsText} from "@/lib/points-i18n";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Notice} from "@/components/ui/notice";
import {Drawer} from "@/components/ui/drawer";
import {DataTable} from "@/components/ui/data-table";
export function EarningEvents({typeCode,typeName,open,onOpenChange,canRetry}:{typeCode:string;typeName?:string;open:boolean;onOpenChange:(open:boolean)=>void;canRetry:boolean}){
 const {t}=usePointsText();const qc=useQueryClient();const [after,setAfter]=useState(0);const [selected,setSelected]=useState<PointsEventView|null>(null);
 const intent=useRef<{body:string;requestId:string}|undefined>(undefined);
 const query=useQuery({queryKey:["points-events",typeCode,after],queryFn:()=>api.pointsEvents({typeCode,after,limit:50}),enabled:open&&!!typeCode});
 const retry=useMutation({mutationFn:api.retryPointsEvent,onSuccess:()=>{intent.current=undefined;setSelected(null);void qc.invalidateQueries({queryKey:["points-events"]});}});
 const state=(s:string)=>({DONE:t("已完成","Completed"),MANUAL:t("待人工处理","Needs attention"),RETRY:t("等待自动重试","Automatic retry pending"),PROCESSING:t("处理中","Processing"),NEW:t("待处理","Pending")})[s]??s;
 return <Drawer open={open} onOpenChange={o=>{setSelected(null);onOpenChange(o);}} title={t("积分处理记录","Points processing records")} width="w-[900px]">
 <p className="txt-title">{typeName??t("积分名称未加载","Points name unavailable")}</p>
 {selected?<div className="space-y-4"><Button variant="outline" onClick={()=>setSelected(null)}>{t("返回列表","Back to list")}</Button>
  <dl className="grid grid-cols-[auto_1fr] gap-3 txt-body"><dt>{t("事件号","Event number")}</dt><dd className="break-all">{selected.eventNo}</dd><dt>{t("订单子单号","Sub-order number")}</dt><dd>{selected.subOrderNo??"—"}</dd><dt>{t("状态","Status")}</dt><dd>{state(selected.state)}</dd><dt>{t("失败原因","Failure reason")}</dt><dd>{selected.reason??"—"}</dd></dl>
  {selected.reason?.includes("POINTS_POOL_INSUFFICIENT")&&<Notice>{t("平台预算不足。请先补足平台预算，系统会自动重试；达到人工处理状态后可在此重新提交。现金退款不受影响。","The platform budget is insufficient. Replenish it for automatic retry; manual resubmission is available when attention is required. Cash refunds are unaffected.")}</Notice>}
  {retry.error&&<Notice tone="warning">{retry.error.message}</Notice>}
  {canRetry&&selected.state==="MANUAL"&&<form className="space-y-3" onSubmit={e=>{e.preventDefault();const f=new FormData(e.currentTarget);const next=pointsCommand({requestId:"",typeCode,eventId:selected.id,expectedVersion:selected.version,reason:String(f.get("remark"))},intent.current);intent.current=next.intent;retry.mutate(next.command);}}>
   <label className="block space-y-2">{t("处理备注","Processing remark")}<Input name="remark" required maxLength={500}/></label><Button type="submit" disabled={retry.isPending}>{t("重新提交处理","Resubmit for processing")}</Button>
  </form>}
 </div>:<div className="space-y-4"><div className="flex justify-end"><Button variant="outline" onClick={()=>void query.refetch()}>{t("刷新","Refresh")}</Button></div>
 <DataTable rows={query.data?.rows??[]} loading={query.isPending} error={query.error} onRetry={()=>void query.refetch()} rowKey={r=>String(r.id)} columns={[
  {header:t("事件号","Event number"),cell:(r:PointsEventView)=>r.eventNo},{header:t("类型","Kind"),cell:(r:PointsEventView)=>r.kind},{header:t("状态","Status"),cell:(r:PointsEventView)=>state(r.state)},
  {header:t("重试次数","Attempts"),cell:(r:PointsEventView)=>r.attempt,numeric:true},{header:t("失败原因","Failure reason"),cell:(r:PointsEventView)=>r.reason??"—"},
 ]} rowProps={r=>({role:"button",tabIndex:0,onClick:()=>{retry.reset();setSelected(r);},onKeyDown:e=>{if(e.key==="Enter")setSelected(r);}})}/>
 <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={()=>setAfter(0)}>{t("首页","First page")}</Button><Button variant="outline" disabled={!query.data?.hasMore} onClick={()=>setAfter(query.data?.next??0)}>{t("下一页","Next page")}</Button></div>
 </div>}
 </Drawer>;
}
