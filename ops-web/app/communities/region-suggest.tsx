"use client";

// 按提报单上的地址与坐标推断该挂哪个街道，点一下就填进选择器。
//
// 存在的理由：裁决那一屏原本要从 31 个省一路点到街道，而单子上明明写着
// 「广东省深圳市龙华区福城街道福庆路1号」，坐标也在。让人把机器已经知道的事再点四次，
// 既慢又容易错 —— 330106003 与 330106004 只差一位，挂错的后果是这个社区
// 在任何「按区覆盖」里都出不来，而界面上它看起来完全正常。
//
// **只给建议，不自动填**：地址可能写错，坐标可能是商家站在别处点的。
// 两条线索各出一条候选、标明依据，由运营判 —— 这正是裁决这一步存在的意义。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function RegionSuggest({
  c, address, latE6, lngE6, onPick,
}: {
  c: Copy;
  address?: string;
  latE6?: number | null;
  lngE6?: number | null;
  /** 选中一条候选：把街道码交给上层，选择器会按它回显整条链路 */
  onPick: (regionCode: string) => void;
}) {
  const q = useQuery({
    queryKey: ["region-resolve", address ?? "", latE6 ?? "", lngE6 ?? ""],
    queryFn: () => api.resolveRegion({ address, latE6, lngE6 }),
    // 两样都没有就别问了 —— 后端也只会返回空
    enabled: Boolean(address) || (latE6 != null && lngE6 != null),
  });

  // 推不出来就整块不出现：一个永远空着的「建议」区块比没有更让人分心
  if (!q.data?.length) return null;

  return (
    <div className="mb-3 rounded-md border border-border bg-muted/40 p-2">
      <div className="txt-caption text-muted-foreground mb-1">{c.regionSuggestTitle}</div>
      {q.data.map((s) => (
        <div key={s.source + s.region.regionCode} className="flex items-center gap-2 py-1">
          <Badge>{s.source === "ADDRESS" ? c.regionSuggestAddress : c.regionSuggestCoords}</Badge>
          <div className="min-w-0 flex-1">
            <div className="truncate">{s.path}</div>
            {/* 依据要写出来：运营得能判断这条建议靠不靠谱，而不是盲从 */}
            <div className="txt-caption text-muted-foreground truncate">{s.detail}</div>
          </div>
          <Button size="sm" variant="outline" onClick={() => onPick(s.region.regionCode)}>
            {c.regionSuggestUse}
          </Button>
        </div>
      ))}
    </div>
  );
}
