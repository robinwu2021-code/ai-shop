"use client";
import type { PointsTypeConfig } from "@/lib/types";
import { useState } from "react";
import type { EarningCategory, EarningRule } from "@/lib/points-earning";
import { percentToBasisPoints } from "@/lib/points-earning";
import { usePointsText } from "@/lib/points-i18n";
import { Input, Select } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";

export function EarningRuleEditor({rule,categories,types,defaultTypeCode,onSubmit}:{rule:EarningRule|null;categories:EarningCategory[];types:PointsTypeConfig[];defaultTypeCode?:string;onSubmit:(typeCode:string,scope:string,ratio:number,remark:string)=>void}) {
 const {t}=usePointsText();
 const [mode,setMode]=useState(rule?.mode==="RATIO" && rule.value===0?"NONE":"RATIO");
 const [error,setError]=useState("");
 return <form id="earning-rule-editor" className="space-y-5" onSubmit={e=>{
  e.preventDefault();setError("");
  const f=new FormData(e.currentTarget);
  try {onSubmit(String(f.get("typeCode")),String(f.get("scope")),mode==="NONE"?0:percentToBasisPoints(String(f.get("ratio"))),String(f.get("remark")??""));}
  catch(err){setError(err instanceof Error?err.message:String(err));}
 }}>
  {error&&<Notice tone="warning">{error}</Notice>}
  {rule?.mode==="FIXED"&&<Notice>{t("原固定赠送规则仍在生效。保存比例草稿不会改变它，发布后才切换为比例赠送。","The existing fixed rule remains active until a ratio revision is published.")}</Notice>}
  <label className="block space-y-2">{t("赠送积分","Reward points")}<Select name="typeCode" required defaultValue={rule?.typeCode??defaultTypeCode??""} disabled={!!rule}>
   <option value="">{t("请选择积分","Select points")}</option>{types.filter(type=>type.status==="ACTIVE"||type.typeCode===rule?.typeCode).map(type=><option key={type.typeCode} value={type.typeCode}>{type.name} · {type.typeCode} · {type.currency}</option>)}
  </Select></label>
  {rule&&<input type="hidden" name="typeCode" value={rule.typeCode}/>}
  <label className="block space-y-2">{t("适用品类","Category")}<Select name="scope" required defaultValue={rule?.scope??""} disabled={!!rule}>
   {!rule&&<option value="">{t("请选择品类","Select a category")}</option>}
   {rule?.scope==="DEFAULT"?<option value="DEFAULT">{t("所有未单独配置的品类","All categories without an override")}</option>:categories.map(c=><option key={c.categoryNo} value={c.categoryNo}>{c.path}</option>)}
  </Select></label>
  {rule&&<input type="hidden" name="scope" value={rule.scope}/>}
  <div className="flex flex-wrap items-end gap-4">
   <label className="block w-44 space-y-2">{t("赠送方式","Reward mode")}<Select name="mode" value={mode} onChange={e=>setMode(e.target.value)}><option value="RATIO">{t("按比例赠送","Percentage")}</option><option value="NONE">{t("不赠送","No reward")}</option></Select></label>
   {mode==="RATIO"&&<label className="block w-36 space-y-2">{t("赠送比例","Reward rate")}<div className="flex items-center gap-2"><Input name="ratio" type="number" min="0" max="100" step="0.01" required defaultValue={rule?.mode==="RATIO"?(rule.value/100).toFixed(2):"1.00"}/><span>%</span></div></label>}
  </div>
  <p className="txt-caption text-muted-foreground">{t("按商品现金实付金额计算，不含运费和积分抵扣部分。保存为草稿后，需要单独生效；已有订单不受影响；新增积分类型暂不自动接入订单赠送。","Calculated from cash paid for goods, excluding shipping and points deductions. Save a draft, then publish it. Existing orders are unchanged; new types are not automatically connected to orders.")}</p>
  <label className="block space-y-2">{t("备注","Remark")}<Input name="remark" maxLength={500} defaultValue=""/></label>
 </form>;
}
