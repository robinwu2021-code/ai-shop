"use client";

import { useCan } from "@/lib/use-can";
import { Notice } from "@/components/ui/notice";
import { usePointsText } from "@/lib/points-i18n";
import { PointsStoreAccessPage } from "../store-access-tab";

export default function PointsStoreAccessRoute() {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice tone="warning">{t("没有积分运营查看权限。", "You do not have permission to view Points Operations.")}</Notice>;
  return <PointsStoreAccessPage canEdit={can("finance:settle:execute")} />;
}
