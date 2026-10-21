"use client";

import { useCan } from "@/lib/use-can";
import { Notice } from "@/components/ui/notice";
import { usePointsText } from "@/lib/points-i18n";
import { PointsTypesTab } from "../types-tab";

export default function PointsTypesPage() {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice tone="warning">{t("没有积分运营查看权限。", "You do not have permission to view Points Operations.")}</Notice>;
  return <PointsTypesTab canEdit={can("finance:settle:execute")} />;
}
