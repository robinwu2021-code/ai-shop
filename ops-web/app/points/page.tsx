"use client";
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { pointsLegacyRedirect } from "@/lib/points-ops-routes";
function PointsRedirect() {
  const router = useRouter(); const search = useSearchParams(); const tab = search.get("tab");
  useEffect(() => { router.replace(pointsLegacyRedirect(tab) ?? "/points/types"); }, [router, tab]);
  return null;
}
export default function PointsPage() { return <Suspense fallback={null}><PointsRedirect /></Suspense>; }
