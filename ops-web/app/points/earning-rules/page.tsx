"use client";
import { TabHeader } from "@/components/ui/tab-header";
import {useCan} from "@/lib/use-can";
import {usePointsText} from "@/lib/points-i18n";
import {Notice} from "@/components/ui/notice";
import {PointsEarningRulesPanel} from "../earning-rules-panel";
export default function PointsEarningRulesPage(){
 const can=useCan();const {t}=usePointsText();
 if(!can("finance:settle:read"))return <Notice tone="warning">{t("没有积分运营查看权限。","You do not have permission to view Points Operations.")}</Notice>;
 return <PointsEarningRulesPanel header={<TabHeader tabs={[{key:"earning-rules",label:t("赠送规则","Reward rules")}]} value="earning-rules" onChange={()=>{}} desc={t("配置品类赠送比例与生效修订","Configure category reward rates and published revisions")}/>} canEdit={can("finance:settle:execute")}/>;
}
