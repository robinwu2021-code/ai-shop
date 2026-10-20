"use client";
import {useState} from "react";
import {useMutation} from "@tanstack/react-query";
import {api} from "@/lib/api";
import {fiatToMinor,type EarningContext} from "@/lib/points-earning";
import {usePointsText} from "@/lib/points-i18n";
import {Input,Select} from "@/components/ui/input";
import {Button} from "@/components/ui/button";
import {Notice} from "@/components/ui/notice";
export function EarningPreview({context}:{context:EarningContext}) {
 const {t}=usePointsText();const [error,setError]=useState("");
 const preview=useMutation({mutationFn:api.previewPointsEarning});
 const type=context.type!;
 return <div className="space-y-5"><form className="space-y-4" onChange={()=>{preview.reset();setError("");}} onSubmit={e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);setError("");
  try{preview.mutate({categoryNo:String(f.get("categoryNo"))||null,cashMinor:fiatToMinor(String(f.get("amount")),type.scale)});}catch(err){setError((err as Error).message);}
 }}>
 <label className="block space-y-2">{t("商品品类","Category")}<Select name="categoryNo"><option value="">{t("默认规则","Default rule")}</option>{context.categories.map(c=><option key={c.categoryNo} value={c.categoryNo}>{c.path}</option>)}</Select></label>
 <label className="block w-56 space-y-2">{t("商品现金实付","Cash paid for goods")} · {type.currency}<Input name="amount" type="number" min="0" step={10**-type.scale} defaultValue="100" required/></label>
 <Button type="submit" disabled={preview.isPending}>{t("计算预计积分","Calculate points")}</Button>
 </form>
 {(error||preview.error)&&<Notice tone="warning">{error||preview.error?.message}</Notice>}
 {preview.data&&<dl className="grid grid-cols-[auto_1fr] gap-3 txt-body">
  <dt>{t("命中范围","Matched scope")}</dt><dd>{preview.data.matched.scope==="DEFAULT"?t("默认规则","Default rule"):context.categories.find(c=>c.categoryNo===preview.data?.matched.scope)?.path??preview.data.matched.scope}</dd>
  <dt>{t("生效修订","Published revision")}</dt><dd>{preview.data.matched.revision}</dd>
  <dt>{t("赠送比例","Reward rate")}</dt><dd>{(preview.data.matched.rule.value/100).toFixed(2)}%</dd>
  <dt>{t("预计赠送积分","Estimated points")}</dt><dd>{preview.data.points}</dd>
 </dl>}
 </div>;
}
