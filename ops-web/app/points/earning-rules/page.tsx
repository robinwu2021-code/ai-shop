"use client";
import { TabHeader } from "@/components/ui/tab-header";
import {useCan} from "@/lib/use-can";
import {usePointsText} from "@/lib/points-i18n";
import {Notice} from "@/components/ui/notice";
import {PointsEarningRulesPanel} from "../earning-rules-panel";
export default function PointsEarningRulesPage(){
 const can=useCan();const {t}=usePointsText();
 if(!can("finance:settle:read"))return <Notice tone="warning">{t("没有积分运营查看权限。","You do not have permission to view Points Operations.")}</Notice>;
 return <main className="space-y-3"><TabHeader tabs={[{ key: "earning-rules", label: t("赠送规则", "Reward rules") }]} value="earning-rules" onChange={() => {}} desc={t("配置与发布各积分类型的赠送规则", "Configure and publish reward rules for each points type")} /><PointsEarningRulesPanel canEdit={can("finance:settle:execute")} /></main>;
}
