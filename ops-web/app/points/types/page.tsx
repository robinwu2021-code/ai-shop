"use client";

import { useCan } from "@/lib/use-can";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { usePointsText } from "@/lib/points-i18n";
import { PointsTypesTab } from "../types-tab";

export default function PointsTypesPage() {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice tone="warning">{t("没有积分运营查看权限。", "You do not have permission to view Points Operations.")}</Notice>;
  return <><TabHeader tabs={[{ key: "types", label: t("积分类型与规则", "Points types & rules") }]} value="types" onChange={() => {}} desc={t("配置积分类型、兑换规则和条款", "Configure points types, conversion rules and terms")} /><PointsTypesTab canEdit={can("finance:settle:execute")} /></>;
}
