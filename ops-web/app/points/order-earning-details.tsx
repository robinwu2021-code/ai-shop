"use client";
import {useQuery} from "@tanstack/react-query";
import {api} from "@/lib/api";
import {usePointsText} from "@/lib/points-i18n";
import {DataTable} from "@/components/ui/data-table";
import {Notice} from "@/components/ui/notice";
import {Button} from "@/components/ui/button";
export function OrderEarningDetails({subOrderNo}:{subOrderNo:string}){
 const {t}=usePointsText();
 const query=useQuery({queryKey:["points-order-earning",subOrderNo],queryFn:()=>api.pointsOrderEarning(subOrderNo)});
 const types=useQuery({queryKey:["points-types"],queryFn:()=>api.pointsTypes()});
 const data=query.data;const ledger=data?.ledger;
 const pointType=types.data?.find(type=>type.typeCode===data?.typeCode);
 return <section className="space-y-3">
  <h3 className="txt-heading">{t("订单赠送明细","Order reward details")}</h3>
  {query.error&&<Notice tone="warning">{query.error.message}<Button onClick={()=>void query.refetch()}>{t("重试","Retry")}</Button></Notice>}
  {types.error&&<Notice tone="warning">{types.error.message}<Button onClick={()=>void types.refetch()}>{t("重试","Retry")}</Button></Notice>}
  {data&&<>
   <dl className="grid grid-cols-[auto_1fr] gap-3 txt-body"><dt>{t("订单号","Order number")}</dt><dd>{data.orderNo}</dd><dt>{t("积分名称","Points name")}</dt><dd>{pointType?.name??t("积分名称未加载","Points name unavailable")}</dd><dt>{t("积分代码","Points code")}</dt><dd>{data.typeCode}</dd>
    <dt>{t("原预计积分","Original estimated points")}</dt><dd>{ledger?.expectedPoints??"—"}</dd>
    <dt>{t("实发积分","Granted points")}</dt><dd>{ledger?.grantedPoints??"—"}</dd>
    <dt>{t("待入账积分","Pending points")}</dt><dd>{ledger?.pendingPoints??"—"}</dd>
    <dt>{t("累计转可用","Activated points")}</dt><dd>{ledger?.availablePoints??"—"}</dd>
    <dt>{t("已追回积分","Reclaimed points")}</dt><dd>{ledger?.reclaimedPoints??"—"}</dd>
    {ledger?.reason&&<><dt>{t("处理原因","Reason")}</dt><dd>{ledger.reason}</dd></>}
   </dl>
   {!ledger&&<Notice>{t("尚无发放记账结果，请核对订单事件处理记录。","No grant has been posted. Check the order event processing records.")}</Notice>}
   <DataTable error={query.error} onRetry={()=>void query.refetch()} empty={t("订单没有赠送商品明细。请核对下单时保存的赠送规则快照。","This order has no reward line items. Check the reward rule snapshot saved at checkout.")} rows={data.snapshot.lines} rowKey={r=>r.itemId} columns={[
    {header:t("商品代码","Product code"),cell:r=>r.goodsNo},
    {header:t("品类代码","Category code"),cell:r=>r.categoryNo??"—"},
    {header:t("商品现金实付","Cash paid for goods"),cell:r=>`${r.goodsCashMinor} ${t("最小货币单位","minor units")} (${data.currency})`,numeric:true},
    {header:t("赠送规则","Reward rule"),cell:r=>r.rule.mode==="RATIO"?`${(r.rule.value/100).toFixed(2)}%`:`${r.rule.value} ${t("积分/件","points/item")}`},
    {header:t("命中范围","Matched scope"),cell:r=>{const trace=data.snapshot.ruleTraces?.find(x=>x.itemId===r.itemId);return trace?.matchedScope==="DEFAULT"?t("默认","Default"):trace?.matchedScope??t("历史快照","Legacy snapshot");}},
    {header:t("规则修订","Rule revision"),cell:r=>data.snapshot.ruleTraces?.find(x=>x.itemId===r.itemId)?.revision??"—",numeric:true},
   ]}/>
  </>}
 </section>;
}
