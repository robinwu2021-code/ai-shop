"use client";
import {useState} from "react";
import {useMutation} from "@tanstack/react-query";
import {api} from "@/lib/api";
import {fiatToMinor,type EarningContext} from "@/lib/points-earning";
import {usePointsText} from "@/lib/points-i18n";
import {Input,Select} from "@/components/ui/input";
import {Button} from "@/components/ui/button";
import {Notice} from "@/components/ui/notice";
export function EarningPreview({context,defaultTypeCode}:{context:EarningContext;defaultTypeCode?:string}) {
 const {t}=usePointsText();
 const types=context.types.filter(type=>type.status==="ACTIVE");
 const [code,setCode]=useState(types.find(type=>type.typeCode===defaultTypeCode)?.typeCode??types[0]?.typeCode??"");
 const type=types.find(type=>type.typeCode===code);
 return <div className="space-y-5"><label className="flex min-w-0 flex-col gap-2 txt-label">{t("赠送积分","Reward points")}<Select className="w-full min-w-0" name="previewTypeCode" value={code} onChange={e=>setCode(e.target.value)}>{types.map(type=><option key={type.typeCode} value={type.typeCode}>{type.name}</option>)}</Select></label>
 {type&&<PreviewForm key={type.typeCode} type={type} context={context}/>}</div>;
}
function PreviewForm({type,context}:{type:EarningContext["types"][number];context:EarningContext}) {
 const {t}=usePointsText();const [error,setError]=useState("");
 const preview=useMutation({mutationFn:async (v:{query:Parameters<typeof api.previewPointsEarning>[0];formVersion:number})=>({result:await api.previewPointsEarning(v.query),formVersion:v.formVersion})});
 const [formVersion,setFormVersion]=useState(0);
 return <div className="space-y-5"><form className="space-y-4" onChange={()=>{setFormVersion(v=>v+1);preview.reset();setError("");}} onSubmit={e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);setError("");
  try{preview.mutate({formVersion,query:{typeCode:type.typeCode,currency:type.currency,categoryNo:String(f.get("categoryNo"))||null,cashMinor:fiatToMinor(String(f.get("amount")),type.scale)}});}catch(err){setError((err as Error).message);}
 }}>
 <label className="flex min-w-0 flex-col gap-2 txt-label">{t("商品品类","Category")}<Select className="w-full min-w-0" name="categoryNo"><option value="">{t("默认规则","Default rule")}</option>{context.categories.map(c=><option key={c.categoryNo} value={c.categoryNo}>{c.path}</option>)}</Select></label>
 <label className="flex w-56 max-w-full min-w-0 flex-col gap-2 txt-label">{t("商品现金实付","Cash paid for goods")} · {type.currency}<Input name="amount" type="number" min="0" step={10**-type.scale} defaultValue="100" required/></label>
 <Button type="submit" disabled={preview.isPending}>{t("计算预计积分","Calculate points")}</Button>
 </form>
 {(error||preview.error)&&<Notice tone="warning">{error||preview.error?.message}</Notice>}
 {preview.data?.formVersion===formVersion&&<PreviewResult context={context} result={preview.data.result}/>}
 </div>;
}
function PreviewResult({context,result}:{context:EarningContext;result:Awaited<ReturnType<typeof api.previewPointsEarning>>}) {
 const {t}=usePointsText();
 return <dl className="grid grid-cols-[auto_1fr] gap-3 txt-body">
  <dt>{t("积分名称","Points name")}</dt><dd>{result.type?.name}</dd>
  <dt>{t("积分代码","Points code")}</dt><dd>{result.type?.typeCode}</dd>
  <dt>{t("币种","Currency")}</dt><dd>{result.type?.currency}</dd>
  <dt>{t("兑换比例","Exchange rate")}</dt><dd>{result.type?.ratePoints} {t("积分","points")} / {result.type?.rateMinor} {t("最小货币单位","minor currency units")}</dd>
  <dt>{t("命中范围","Matched scope")}</dt><dd>{result.matched.scope==="DEFAULT"?t("默认规则","Default rule"):context.categories.find(c=>c.categoryNo===result.matched.scope)?.path??result.matched.scope}</dd>
  <dt>{t("生效修订","Published revision")}</dt><dd>{result.matched.revision}</dd>
  <dt>{t("赠送比例","Reward rate")}</dt><dd>{(result.matched.rule.value/100).toFixed(2)}%</dd>
  <dt>{t("预计赠送积分","Estimated points")}</dt><dd>{result.points}</dd>
 </dl>;
}
