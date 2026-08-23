"use client";

// 裁决时的落点核验图。**它解决的不是「选区划」**（那个已经由地址与坐标推断出来了），
// 而是两件文字替代不了的事：
//
//  1. **落点对不对** —— 运营看到的原本只有「30.316200, 120.152400」一串数字，
//     判不出它落在小区门口还是隔壁工地。
//  2. **是不是重复提报** —— 抽屉里一直写着「同一个小区常有两个叫法」，
//     但此前只能拿名字比对：两条名字不同、位置只差 50 米的，肉眼看不出来。
//     这里把**附近已开通的聚落**一起画出来，重的一眼就看见。
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { loadAMap } from "@/lib/amap";
import { Notice } from "@/components/ui/notice";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

/* eslint-disable @typescript-eslint/no-explicit-any -- 高德 JS API 没有类型包，只在这一层用 any */

export function ApplyMap({
  c, latE6, lngE6, name,
}: {
  c: Copy;
  latE6?: number | null;
  lngE6?: number | null;
  /** 提报的名字，标在图钉上 */
  name: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [err, setErr] = useState<"no-key" | "no-scode" | "load-failed" | null>(null);

  const near = useQuery({
    queryKey: ["communities-near", latE6, lngE6],
    queryFn: () => api.communitiesNear(latE6!, lngE6!, 2000),
    enabled: latE6 != null && lngE6 != null,
  });

  useEffect(() => {
    if (latE6 == null || lngE6 == null) return;
    let map: any;
    let cancelled = false;
    loadAMap().then((r) => {
      if (cancelled) return;
      if (!r.ok) return setErr(r.reason);
      const AMap = (r as any).AMap;
      if (!box.current) return;
      const center: [number, number] = [lngE6 / 1e6, latE6 / 1e6];
      map = new AMap.Map(box.current, { zoom: 16, center, resizeEnable: true });
      // 提报点用默认红钉；已有聚落用小圆点 —— 两者必须一眼分得开，否则查重反而更糊涂
      new AMap.Marker({ position: center, title: name, map });
      for (const n of near.data ?? []) {
        const pos: [number, number] = [n.lngE6 / 1e6, n.latE6 / 1e6];
        new AMap.CircleMarker({
          center: pos, radius: 7, strokeColor: "#1677ff", fillColor: "#1677ff", fillOpacity: 0.6, map,
        });
        new AMap.Text({ text: `${n.name} · ${n.distanceM}m`, position: pos, offset: new AMap.Pixel(10, -6), map });
      }
    });
    return () => {
      cancelled = true;
      map?.destroy?.();
    };
  }, [latE6, lngE6, name, near.data]);

  // 没坐标就整块不出现：画一张不知道中心在哪的地图没有意义
  if (latE6 == null || lngE6 == null) return null;

  if (err) {
    // 缺 key 时给的是「怎么补」，不是「地图挂了」—— 后者会让人去查网络
    return <Notice tone="warning" className="mb-3">{err === "no-scode" ? c.mapNoScode : c.mapNoKey}</Notice>;
  }

  return (
    <div className="mb-3">
      <div ref={box} className="h-56 w-full rounded-md border border-border" />
      <div className="txt-caption text-muted-foreground mt-1">
        {near.data?.length ? c.mapNearbyHint.replace("{n}", String(near.data.length)) : c.mapNoNearby}
      </div>
    </div>
  );
}
