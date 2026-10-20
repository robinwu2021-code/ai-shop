"use client";
import {useRef,useState} from "react";
import {useQuery,useMutation,useQueryClient} from "@tanstack/react-query";
import {api} from "@/lib/api";
import type {EarningRule,EarningChange} from "@/lib/points-earning";
import {pointsCommand} from "@/lib/points-command";
import {usePointsText} from "@/lib/points-i18n";
import {Button} from "@/components/ui/button";
import {DataTable} from "@/components/ui/data-table";
import {Drawer} from "@/components/ui/drawer";
import {Notice} from "@/components/ui/notice";
import {SectionHeader} from "@/components/ui/section-header";
import {useConfirm} from "@/components/ui/confirm-dialog";
import {EarningRuleEditor} from "./earning-rule-editor";
import {EarningPreview} from "./earning-preview";

export function PointsEarningRulesPanel({canEdit}:{canEdit:boolean}) {
 const {t}=usePointsText();const qc=useQueryClient();const {confirm,dialog}=useConfirm();
 const context=useQuery({queryKey:["points-earning-context"],queryFn:api.pointsEarningContext});
 const [page,setPage]=useState(1);const [scope,setScope]=useState<string|null>(null);
 const [editor,setEditor]=useState<{rule:EarningRule|null}|null>(null);const [preview,setPreview]=useState(false);
 const rules=useQuery({queryKey:["points-earning-rules",page],queryFn:()=>api.pointsEarningRules({page,size:50}),enabled:!!context.data?.type});
 const selected=rules.data?.rows.find(r=>r.scope===scope);
 const history=useQuery({queryKey:["points-earning-history",scope],queryFn:()=>api.pointsEarningHistory(scope!),enabled:!!selected});
 const intent=useRef<{body:string;requestId:string}|undefined>(undefined);
 const save=useMutation({mutationFn:api.changePointsEarningRule,onSuccess:()=>{intent.current=undefined;setEditor(null);void qc.invalidateQueries({queryKey:["points-earning-rules"]});void qc.invalidateQueries({queryKey:["points-earning-history"]});}});
 function command(value:Omit<EarningChange,"requestId">){const next=pointsCommand({...value,requestId:""},intent.current);intent.current=next.intent;return save.mutateAsync(next.command);}
 const categories=context.data?.categories??[];const type=context.data?.type;
 const name=(r:EarningRule)=>r.scope==="DEFAULT"?t("默认规则","Default rule"):categories.find(c=>c.categoryNo===r.scope)?.name??r.scope;
 const ratio=(mode:string|null,value:number|null)=>mode==="FIXED"?`${value} ${t("积分/件（原规则）","points/item (legacy)")}`:value==null?"—":`${(value/100).toFixed(2)}%`;
 const status=(r:EarningRule)=>r.activeRevision==null?(r.action==="DISABLE"?t("已停用","Disabled"):t("草稿","Draft")):r.activeRevision!==r.revision?t("生效中 · 有草稿","Published · draft changes"):t("生效中","Published");
 const editAllowed=canEdit&&type?.status==="ACTIVE";
 return <div className="space-y-5">
  <div className="flex flex-wrap items-center justify-between gap-3"><SectionHeader title={t("赠送规则","Reward rules")} summary={rules.data?.total??0} className="mb-0"/>
  <div className="flex gap-2">{type&&<Button variant="outline" onClick={()=>setPreview(true)}>{t("计算预览","Calculation preview")}</Button>}{editAllowed&&<Button onClick={()=>{setScope(null);save.reset();setEditor({rule:null});}}>{t("新增品类规则","New category rule")}</Button>}</div></div>
  {context.isPending&&<Notice>{t("正在加载积分配置…","Loading points configuration…")}</Notice>}
  {context.error&&<Notice tone="warning">{context.error.message}<Button onClick={()=>void context.refetch()}>{t("重试","Retry")}</Button></Notice>}
  {context.data&&!type&&<Notice>{t("尚无订单默认积分，请先在积分设置中生效首个匹配市场币种的积分类型。","No order default points type is configured. Activate a matching points type in Settings first.")}</Notice>}
  {type&&<Notice>{t("订单赠送积分：","Order reward points: ")}{type.name} · {type.typeCode} · {type.currency}。{t("所有门店默认参与；按商品现金实付计算，排除运费。","All stores participate. Rewards are based on cash paid for goods, excluding shipping.")}</Notice>}
  {save.error&&<Notice tone="warning">{save.error.message}</Notice>}
  {type&&<DataTable rows={rules.data?.rows??[]} loading={rules.isPending} error={rules.error} onRetry={()=>void rules.refetch()} rowKey={r=>r.scope}
   columns={[
    {header:t("适用范围","Scope"),cell:(r:EarningRule)=>r.scope==="DEFAULT"?t("默认","Default"):t("品类","Category")},
    {header:t("品类名称","Category name"),cell:name},
    {header:t("品类路径","Category path"),cell:(r:EarningRule)=>categories.find(c=>c.categoryNo===r.scope)?.path??"—"},
    {header:t("积分名称","Points name"),cell:()=>type.name},
    {header:t("积分代码","Points code"),cell:()=>type.typeCode},
    {header:t("生效比例","Published rate"),cell:(r:EarningRule)=>ratio(r.activeMode,r.activeValue)},
    {header:t("状态","Status"),cell:status},
    {header:t("修订号","Revision"),cell:(r:EarningRule)=>r.revision,numeric:true},
    {header:t("更新时间","Updated at"),cell:(r:EarningRule)=>new Date(r.at).toLocaleString()},
   ]} rowProps={r=>({role:"button",tabIndex:0,onClick:()=>{setScope(r.scope);save.reset();},onKeyDown:e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();setScope(r.scope);}}})}/>}
  {(rules.data?.total??0)>50&&<div className="flex items-center justify-end gap-3"><Button variant="outline" disabled={page===1} onClick={()=>setPage(page-1)}>{t("上一页","Previous")}</Button><span>{page}</span><Button variant="outline" disabled={page*50>=(rules.data?.total??0)} onClick={()=>setPage(page+1)}>{t("下一页","Next")}</Button></div>}
  <Drawer open={!!selected} onOpenChange={o=>{if(!o)setScope(null);}} title={selected?name(selected):""} desc={t("调整仅影响新订单，历史订单沿用原修订。","Changes apply to new orders only. Existing orders retain their original revision.")}
   footer={selected&&editAllowed?<>
    <Button variant="outline" onClick={()=>{setScope(null);save.reset();setEditor({rule:selected});}}>{t("调整规则","Edit rule")}</Button>
    {selected.activeRevision!==selected.revision&&<Button disabled={save.isPending||selected.mode==="FIXED"} onClick={()=>void confirm({title:t("生效赠送规则","Publish reward rule"),desc:t("新订单将使用此修订，已有订单不变。","New orders will use this revision. Existing orders remain unchanged."),action:()=>command({typeCode:type!.typeCode,scope:selected.scope,expectedRevision:selected.revision,action:"PUBLISH",ratioBp:null,remark:t("生效赠送规则","Publish reward rule")})})}>{t("生效","Publish")}</Button>}
    {selected.scope!=="DEFAULT"&&selected.activeRevision!=null&&<Button variant="outline" disabled={save.isPending} onClick={()=>void confirm({title:t("停用品类规则","Disable category rule"),desc:t("新订单将恢复继承上级品类或默认比例。","New orders will inherit a parent or default rule."),action:()=>command({typeCode:type!.typeCode,scope:selected.scope,expectedRevision:selected.revision,action:"DISABLE",ratioBp:null,remark:t("停用品类规则","Disable category rule")})})}>{t("停用","Disable")}</Button>}
   </>:undefined}>
   {selected&&<div className="space-y-5"><dl className="grid grid-cols-[auto_1fr] gap-3 txt-body"><dt>{t("生效比例","Published rate")}</dt><dd>{ratio(selected.activeMode,selected.activeValue)}</dd><dt>{t("待发布配置","Draft value")}</dt><dd>{ratio(selected.mode,selected.value)}</dd><dt>{t("状态","Status")}</dt><dd>{status(selected)}</dd><dt>{t("生效修订","Published revision")}</dt><dd>{selected.activeRevision??"—"}</dd></dl>
    <h3 className="txt-heading">{t("修订记录","Revision history")}</h3>{history.error&&<Notice tone="warning">{history.error.message}</Notice>}
    {(history.data??[]).map(r=><div key={r.revision} className="space-y-1 border-b border-border pb-3 txt-body"><p>#{r.revision} · {r.action==="PUBLISH"?t("生效","Published"):r.action==="DISABLE"?t("停用","Disabled"):r.action==="SAVE"?t("保存草稿","Draft saved"):t("原规则迁入","Imported")} · {r.actor} · {new Date(r.at).toLocaleString()}</p><p>{ratio(r.mode,r.value)} · {r.remark}</p></div>)}
   </div>}
  </Drawer>
  <Drawer open={!!editor} onOpenChange={o=>{if(!o)setEditor(null);}} title={editor?.rule?t("调整赠送规则","Edit reward rule"):t("新增品类规则","New category rule")} footer={<Button type="submit" form="earning-rule-editor" disabled={save.isPending}>{t("保存草稿","Save draft")}</Button>}>
   {save.error&&<Notice tone="warning">{save.error.message}</Notice>}
   {editor&&<EarningRuleEditor key={`${editor.rule?.scope??"NEW"}:${editor.rule?.revision??0}`} rule={editor.rule} categories={categories} onSubmit={(scope,ratio,remark)=>{void command({typeCode:type!.typeCode,scope,expectedRevision:editor.rule?.revision??0,action:"SAVE",ratioBp:ratio,remark}).catch(()=>{});}}/>}
  </Drawer>
  <Drawer open={preview} onOpenChange={setPreview} title={t("计算预览","Calculation preview")}>{preview&&context.data?.type&&<EarningPreview context={context.data}/>}</Drawer>
  {dialog}
 </div>;
}
