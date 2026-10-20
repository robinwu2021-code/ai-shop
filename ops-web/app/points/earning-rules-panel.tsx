"use client";
import {useRef,useState} from "react";
import {useQuery,useMutation,useQueryClient} from "@tanstack/react-query";
import {api} from "@/lib/api";
import type {EarningRule,EarningChange} from "@/lib/points-earning";
import {pointsCommand} from "@/lib/points-command";
import {usePointsText} from "@/lib/points-i18n";
import {Select} from "@/components/ui/input";
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
 const [page,setPage]=useState(1);const [typeCode,setTypeCode]=useState("");const [scope,setScope]=useState<string|null>(null);
 const [editor,setEditor]=useState<{rule:EarningRule|null}|null>(null);const [preview,setPreview]=useState(false);
 const rules=useQuery({queryKey:["points-earning-rules",typeCode,page],queryFn:()=>api.pointsEarningRules({page,size:50,...(typeCode?{typeCode}:{all:true})}),enabled:!!context.data});
 const selected=rules.data?.rows.find(r=>`${r.typeCode}:${r.scope}`===scope);
 const history=useQuery({queryKey:["points-earning-history",selected?.typeCode,selected?.scope],queryFn:()=>api.pointsEarningHistory(selected!.scope,selected!.typeCode),enabled:!!selected});
 const intent=useRef<{body:string;requestId:string}|undefined>(undefined);
 const save=useMutation({mutationFn:api.changePointsEarningRule,onSuccess:()=>{intent.current=undefined;setEditor(null);void qc.invalidateQueries({queryKey:["points-earning-rules"]});void qc.invalidateQueries({queryKey:["points-earning-history"]});}});
 function command(value:Omit<EarningChange,"requestId">){const next=pointsCommand({...value,requestId:""},intent.current);intent.current=next.intent;return save.mutateAsync(next.command);}
 const categories=context.data?.categories??[];const types=context.data?.types??[];const type=types.find(t=>t.typeCode===selected?.typeCode);
 const canCreate=canEdit&&types.some(t=>t.status==="ACTIVE");
 const name=(r:EarningRule)=>r.scope==="DEFAULT"?t("默认规则","Default rule"):categories.find(c=>c.categoryNo===r.scope)?.name??r.scope;
 const ratio=(mode:string|null,value:number|null)=>mode==="FIXED"?`${value} ${t("积分/件（原规则）","points/item (legacy)")}`:value==null?"—":`${(value/100).toFixed(2)}%`;
 const status=(r:EarningRule)=>r.activeRevision==null?(r.action==="DISABLE"?t("已停用","Disabled"):t("草稿","Draft")):r.activeRevision!==r.revision?t("生效中 · 有草稿","Published · draft changes"):t("生效中","Published");
 const editAllowed=canEdit&&type?.status==="ACTIVE";
 return <div className="space-y-5">
  <div className="flex flex-wrap items-center justify-between gap-3"><SectionHeader title={t("赠送规则","Reward rules")} summary={rules.data?.total??0} className="mb-0"/>
  <div className="flex gap-2">{types.some(t=>t.status==="ACTIVE")&&<Button variant="outline" onClick={()=>setPreview(true)}>{t("计算预览","Calculation preview")}</Button>}{canCreate&&<Button onClick={()=>{setScope(null);save.reset();setEditor({rule:null});}}>{t("新增品类规则","New category rule")}</Button>}</div></div>
  {context.isPending&&<Notice>{t("正在加载积分配置…","Loading points configuration…")}</Notice>}
  {context.error&&<Notice tone="warning">{context.error.message}<Button onClick={()=>void context.refetch()}>{t("重试","Retry")}</Button></Notice>}
  {context.data&&<label className="block w-72 space-y-2">{t("积分类型","Points type")}<Select name="filterTypeCode" value={typeCode} onChange={e=>{setTypeCode(e.target.value);setPage(1);setScope(null);}}><option value="">{t("全部积分","All points")}</option>{types.map(type=><option key={type.typeCode} value={type.typeCode}>{type.name} · {type.typeCode}</option>)}</Select></label>}
  {context.data&&!types.some(type=>type.status==="ACTIVE")&&<Notice>{t("暂无已生效积分，请先在积分设置中生效积分类型。","Activate a points type in Settings before configuring new rewards.")}</Notice>}
  <Notice>{t("各积分类型独立配置；所有门店默认参与。新增类型的规则暂不自动接入订单赠送。","Each points type is configured independently. All stores participate. New types are not automatically connected to order rewards.")}</Notice>
  {save.error&&<Notice tone="warning">{save.error.message}</Notice>}
  {context.data&&<DataTable rows={rules.data?.rows??[]} loading={rules.isPending} error={rules.error} onRetry={()=>void rules.refetch()} rowKey={r=>`${r.typeCode}:${r.scope}`}
   columns={[
    {header:t("适用范围","Scope"),cell:(r:EarningRule)=>r.scope==="DEFAULT"?t("默认","Default"):t("品类","Category")},
    {header:t("品类名称","Category name"),cell:name},
    {header:t("品类路径","Category path"),cell:(r:EarningRule)=>categories.find(c=>c.categoryNo===r.scope)?.path??"—"},
    {header:t("积分名称","Points name"),cell:(r:EarningRule)=>types.find(t=>t.typeCode===r.typeCode)?.name??"—"},
    {header:t("积分代码","Points code"),cell:(r:EarningRule)=>r.typeCode},
    {header:t("币种","Currency"),cell:(r:EarningRule)=>types.find(t=>t.typeCode===r.typeCode)?.currency??"—"},
    {header:t("生效比例","Published rate"),cell:(r:EarningRule)=>ratio(r.activeMode,r.activeValue)},
    {header:t("状态","Status"),cell:status},
    {header:t("修订号","Revision"),cell:(r:EarningRule)=>r.revision,numeric:true},
    {header:t("更新时间","Updated at"),cell:(r:EarningRule)=>new Date(r.at).toLocaleString()},
   ]} rowProps={r=>({role:"button",tabIndex:0,onClick:()=>{setScope(`${r.typeCode}:${r.scope}`);save.reset();},onKeyDown:e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();setScope(`${r.typeCode}:${r.scope}`);}}})}/>}
  {(rules.data?.total??0)>50&&<div className="flex items-center justify-end gap-3"><Button variant="outline" disabled={page===1} onClick={()=>setPage(page-1)}>{t("上一页","Previous")}</Button><span>{page}</span><Button variant="outline" disabled={page*50>=(rules.data?.total??0)} onClick={()=>setPage(page+1)}>{t("下一页","Next")}</Button></div>}
  <Drawer open={!!selected} onOpenChange={o=>{if(!o)setScope(null);}} title={selected?name(selected):""} desc={t("修订发布后供业务接入使用；历史快照保持不变。","Published revisions are available to business integrations. Historical snapshots remain unchanged.")}
   footer={selected&&editAllowed?<>
    <Button variant="outline" onClick={()=>{setScope(null);save.reset();setEditor({rule:selected});}}>{t("调整规则","Edit rule")}</Button>
    {selected.activeRevision!==selected.revision&&<Button disabled={save.isPending||selected.mode==="FIXED"} onClick={()=>void confirm({title:t("生效赠送规则","Publish reward rule"),desc:t("接入该积分类型的业务将使用此修订，历史快照不变。","Consumers of this points type will use this revision. Historical snapshots remain unchanged."),action:()=>command({typeCode:selected.typeCode,scope:selected.scope,expectedRevision:selected.revision,action:"PUBLISH",ratioBp:null,remark:t("生效赠送规则","Publish reward rule")})})}>{t("生效","Publish")}</Button>}
    {selected.scope!=="DEFAULT"&&selected.activeRevision!=null&&<Button variant="outline" disabled={save.isPending} onClick={()=>void confirm({title:t("停用品类规则","Disable category rule"),desc:t("新订单将恢复继承上级品类或默认比例。","New orders will inherit a parent or default rule."),action:()=>command({typeCode:selected.typeCode,scope:selected.scope,expectedRevision:selected.revision,action:"DISABLE",ratioBp:null,remark:t("停用品类规则","Disable category rule")})})}>{t("停用","Disable")}</Button>}
   </>:undefined}>
   {selected&&<div className="space-y-5"><dl className="grid grid-cols-[auto_1fr] gap-3 txt-body"><dt>{t("积分名称","Points name")}</dt><dd>{type?.name??"—"}</dd><dt>{t("积分代码","Points code")}</dt><dd>{selected.typeCode}</dd><dt>{t("币种","Currency")}</dt><dd>{type?.currency??"—"}</dd><dt>{t("生效比例","Published rate")}</dt><dd>{ratio(selected.activeMode,selected.activeValue)}</dd><dt>{t("待发布配置","Draft value")}</dt><dd>{ratio(selected.mode,selected.value)}</dd><dt>{t("状态","Status")}</dt><dd>{status(selected)}</dd><dt>{t("生效修订","Published revision")}</dt><dd>{selected.activeRevision??"—"}</dd></dl>
    <h3 className="txt-heading">{t("修订记录","Revision history")}</h3>{history.error&&<Notice tone="warning">{history.error.message}</Notice>}
    {(history.data??[]).map(r=><div key={r.revision} className="space-y-1 border-b border-border pb-3 txt-body"><p>#{r.revision} · {r.action==="PUBLISH"?t("生效","Published"):r.action==="DISABLE"?t("停用","Disabled"):r.action==="SAVE"?t("保存草稿","Draft saved"):t("原规则迁入","Imported")} · {r.actor} · {new Date(r.at).toLocaleString()}</p><p>{ratio(r.mode,r.value)} · {r.remark}</p></div>)}
   </div>}
  </Drawer>
  <Drawer open={!!editor} onOpenChange={o=>{if(!o)setEditor(null);}} title={editor?.rule?t("调整赠送规则","Edit reward rule"):t("新增品类规则","New category rule")} footer={<Button type="submit" form="earning-rule-editor" disabled={save.isPending}>{t("保存草稿","Save draft")}</Button>}>
   {save.error&&<Notice tone="warning">{save.error.message}</Notice>}
   {editor&&<EarningRuleEditor key={`${editor.rule?.typeCode??"NEW"}:${editor.rule?.scope??"NEW"}:${editor.rule?.revision??0}`} rule={editor.rule} categories={categories} types={types} defaultTypeCode={!typeCode?context.data?.type?.typeCode:typeCode} onSubmit={(code,scope,ratio,remark)=>{void command({typeCode:code,scope,expectedRevision:editor.rule?.revision??0,action:"SAVE",ratioBp:ratio,remark}).catch(()=>{});}}/>}
  </Drawer>
  <Drawer open={preview} onOpenChange={setPreview} title={t("计算预览","Calculation preview")}>{preview&&context.data&&<EarningPreview context={context.data} defaultTypeCode={!typeCode?context.data.type?.typeCode:typeCode}/>}</Drawer>
  {dialog}
 </div>;
}
