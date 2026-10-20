"use client";
import {useCan} from "@/lib/use-can";
import {usePointsText} from "@/lib/points-i18n";
import {Notice} from "@/components/ui/notice";
import {PointsEarningRulesPanel} from "../earning-rules-panel";
export default function PointsEarningRulesPage(){
 const can=useCan();const {t}=usePointsText();
 if(!can("finance:settle:read"))return <Notice tone="warning">{t("没有积分运营查看权限。","You do not have permission to view Points Operations.")}</Notice>;
 return <PointsEarningRulesPanel canEdit={can("finance:settle:execute")}/>;
}
