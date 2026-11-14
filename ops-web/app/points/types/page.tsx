"use client";
import { TabHeader } from "@/components/ui/tab-header";

import { useCan } from "@/lib/use-can";
import { Notice } from "@/components/ui/notice";
import { usePointsText } from "@/lib/points-i18n";
import { PointsTypesTab } from "../types-tab";

export default function PointsTypesPage() {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice tone="warning">{t("没有积分运营查看权限。", "You do not have permission to view Points Operations.")}</Notice>;
  return <main className="space-y-3"><TabHeader tabs={[{ key: "types", label: t("积分设置", "Points settings") }]} value="types" onChange={() => {}} desc={t("配置积分类型、规则与协议", "Configure points types, rules and agreements")} /><PointsTypesTab canEdit={can("finance:settle:execute")} /></main>;
}
