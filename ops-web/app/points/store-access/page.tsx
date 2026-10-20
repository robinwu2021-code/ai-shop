"use client";

import { useCan } from "@/lib/use-can";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { usePointsText } from "@/lib/points-i18n";
import { PointsStoreAccessPage } from "../store-access-tab";

export default function PointsStoreAccessRoute() {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice tone="warning">{t("没有积分运营查看权限。", "You do not have permission to view Points Operations.")}</Notice>;
  return <><TabHeader tabs={[{ key: "store-access", label: t("门店协议与接入", "Store agreements & access") }]} value="store-access" onChange={() => {}} desc={t("查看协议、积分接入策略和未结义务", "Review agreements, points integration and obligations")} /><PointsStoreAccessPage canEdit={can("finance:settle:execute")} /></>;
}
