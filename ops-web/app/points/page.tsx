"use client";
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Notice } from "@/components/ui/notice";
import { usePointsText } from "@/lib/points-i18n";
import { pointsLegacyRedirect } from "@/lib/points-ops-routes";
function PointsRedirect() {
  const { t } = usePointsText();
  const router = useRouter(); const search = useSearchParams(); const tab = search.get("tab");
  useEffect(() => { router.replace(pointsLegacyRedirect(tab) ?? "/points/types"); }, [router, tab]);
  return <Notice>{t("正在打开积分设置…", "Opening points settings…")}</Notice>;
}
export default function PointsPage() {
  const { t } = usePointsText();
  return <Suspense fallback={<Notice>{t("正在打开积分设置…", "Opening points settings…")}</Notice>}><PointsRedirect /></Suspense>;
}
