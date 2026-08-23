"use client";

// 疑似重复的聚落，两两成对给运营处理。
//
// **为什么是现在**：商家在选择器里点一条地图地点就直接建档（/biz/communities/from-map），
// 建档时的三道查重只在**当场**比一次 —— 而改名、补坐标、误挂到隔壁街道，
// 都会让两条事后才撞上。撞上不报错：商家甲选了 A、乙选了 B，
// 买家进 B 搜不到甲的货，甲乙都以为自己上架了。
//
// 界面上刻意**不自动合并**：同一条街道里真有「一期」「二期」这种正当的两条，
// 而合并会改一批商家的可见范围，错了要一条条捞回来 —— 这一步必须由人拍板。
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { CommunityDuplicate } from "@/lib/types";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function DuplicatesPanel({ c, canMerge }: { c: Copy; canMerge: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["community-duplicates"],
    queryFn: () => api.duplicateCommunities(50),
  });

  const merge = useMutation({
    mutationFn: ({ fromNo, intoNo }: { fromNo: string; intoNo: string }) =>
      api.mergeCommunities(fromNo, intoNo),
    onSuccess: () => {
      notify.success(c.dupMerged);
      // 合并同时改了聚落列表与这份队列，两份都要重拉
      void qc.invalidateQueries({ queryKey: ["community-duplicates"] });
      void qc.invalidateQueries({ queryKey: ["communities"] });
    },
    onError: (e: Error) => notify.error(e.message),
  });

  // 一组也没有就整块不出现：一个永远空着的「疑似重复」区块只是噪音
  if (!q.data?.length) return null;

  return (
    <div className="mb-4 rounded-md border border-border">
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        <span className="font-medium">{c.dupTitle.replace("{n}", String(q.data.length))}</span>
      </div>
      <div className="px-3 py-2">
        <Notice tone="warning" className="mb-2">{c.dupHint}</Notice>
        {q.data.map((d) => (
          <Row key={d.left.communityNo + d.right.communityNo} d={d} c={c} canMerge={canMerge}
               busy={merge.isPending} onMerge={(fromNo, intoNo) => merge.mutate({ fromNo, intoNo })} />
        ))}
      </div>
    </div>
  );
}

function Row({ d, c, canMerge, busy, onMerge }: {
  d: CommunityDuplicate;
  c: Copy;
  canMerge: boolean;
  busy: boolean;
  onMerge: (fromNo: string, intoNo: string) => void;
}) {
  const reason = d.reason === "SAME_NAME"
    ? c.dupReasonSameName
    : c.dupReasonNearby.replace("{n}", String(d.distanceM ?? "?"));
  return (
    <div className="flex flex-wrap items-center gap-2 border-t border-border py-2 first:border-t-0">
      <Badge>{reason}</Badge>
      <Side name={d.left.name} sub={d.left.regionPath ?? d.left.communityNo} />
      <span className="text-muted-foreground">·</span>
      <Side name={d.right.name} sub={d.right.regionPath ?? d.right.communityNo} />
      {canMerge && (
        <div className="ml-auto flex gap-2">
          {/* 保留哪一条由运营挑：名字更规范的那个留下，被并的名字会进 alias 继续参与查重 */}
          <Button size="sm" variant="outline" disabled={busy}
                  onClick={() => onMerge(d.right.communityNo, d.left.communityNo)}>
            {c.dupKeepLeft}
          </Button>
          <Button size="sm" variant="outline" disabled={busy}
                  onClick={() => onMerge(d.left.communityNo, d.right.communityNo)}>
            {c.dupKeepRight}
          </Button>
        </div>
      )}
    </div>
  );
}

function Side({ name, sub }: { name: string; sub: string }) {
  return (
    <div className="min-w-0">
      <div className="truncate">{name}</div>
      {/* 区划路径要写出来：光两个名字判断不了它们是不是同一个地方 */}
      <div className="txt-caption truncate text-muted-foreground">{sub}</div>
    </div>
  );
}
