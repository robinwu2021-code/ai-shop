"use client";

// 行政区划的逐级选择（ADR-013）。**两处共用**：社区归属、商家提报的裁决。
//
// 抽出来的理由不是"复用"这个词本身：提报那一屏原本是个裸输入框，
// 要运营手敲国标码 —— 而 330106003 与 330106004 只差一位，填错的后果是
// 这个社区在任何「按区覆盖」里都出不来（后端会拦下不存在的码，但存在却填错的那种拦不住）。
// 联调时我自己就填错过一次。
//
// **逐级选，不加载整棵树**：四级共 44703 行、1.6 MB。挑一个街道只需沿
// 「省 → 市 → 区 → 街道」走四次、每次几十条。
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Region } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function RegionChooser({
  c, value, onChange,
}: {
  c: Copy;
  /** 已选中的区划码。空 = 从省级开始选 */
  value?: string;
  /** 选到某一级时回调。**中间层级也会回调** —— 「整个西湖区」是合法归属，不必选到街道 */
  onChange: (r: Region | null) => void;
}) {
  /** 已选中的各级：[省, 市, 区, 街道] 的前缀。空数组 = 还没开始选 */
  const [chain, setChain] = useState<Region[]>([]);

  // 按已有的码回显整条链路 —— 端上不自己按码长切片，那是国标编码规则，
  // 不该复制到端上（后端 /ops/regions/path 就是干这个的）
  useEffect(() => {
    if (!value) {
      setChain([]);
      return;
    }
    api.regionPath(value).then(setChain).catch(() => setChain([]));
  }, [value]);

  const parent = chain.length ? chain[chain.length - 1]!.regionCode : undefined;
  const leaf = chain.length ? chain[chain.length - 1]! : null;

  const options = useQuery({
    queryKey: ["regions", parent ?? "ROOT"],
    // 运营维护面给全量：停用的区划也要看得见，否则再也开不回来
    queryFn: () => api.listRegions(parent, false),
    // 已经选到叶子（街道）就不用再查下一层
    enabled: !leaf || leaf.hasChild,
  });

  const pick = (r: Region) => {
    setChain((prev) => [...prev, r]);
    onChange(r);
  };
  const backTo = (i: number) => {
    setChain((prev) => {
      const next = prev.slice(0, i);
      onChange(next.length ? next[next.length - 1]! : null);
      return next;
    });
  };

  return (
    <>
      {/* 面包屑：点任意一级回退到那一级重选 */}
      <div className="flex flex-wrap items-center gap-1 mb-3">
        <button type="button" className="txt-caption underline" onClick={() => backTo(0)}>
          {c.regionRoot}
        </button>
        {chain.map((r, i) => (
          <span key={r.regionCode} className="txt-caption">
            <span className="mx-1 text-muted-foreground">/</span>
            <button type="button" className="underline" onClick={() => backTo(i + 1)}>
              {r.name}
            </button>
          </span>
        ))}
      </div>

      {leaf && !leaf.hasChild ? (
        <Notice className="mb-3">{c.regionLeafReached}</Notice>
      ) : (
        <div className="flex flex-wrap gap-2">
          {options.data?.map((r) => (
            <Button key={r.regionCode} size="sm" variant="outline" onClick={() => pick(r)}>
              {r.name}
              {/* 停用的也列出来但标出来 —— 看得见才开得回来 */}
              {!r.enabled && <Badge className="ml-1">{c.regionDisabled}</Badge>}
            </Button>
          ))}
          {options.isLoading && <span className="txt-caption text-muted-foreground">…</span>}
          {!options.isLoading && !options.data?.length && (
            <span className="txt-caption text-muted-foreground">{c.regionNoChild}</span>
          )}
        </div>
      )}
    </>
  );
}
