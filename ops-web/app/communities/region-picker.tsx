"use client";

// 社区的行政区划归属（ADR-013 阶段一）—— **已接真后端** `/ops/regions/**`。
//
// 挂上之后「按区/按街道覆盖」才能命中这个社区：商家勾一个「西湖区」，
// 要能展开成该区下的全部社区。
//
// **逐级选，不加载整棵树**：四级共 44703 行、1.6 MB。挑一个街道只需沿
// 「省 → 市 → 区 → 街道」走四次、每次几十条；一次性拉全国的话，
// 每开一次抽屉都要传一遍，而其中 99.9% 用不到。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { Community, Region } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import type { CommunityCopy } from "./copy";

export function RegionPicker({
  c, community, canWrite, onClose,
}: {
  c: CommunityCopy;
  community: Community | null;
  canWrite: boolean;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  /** 已选中的各级：[省, 市, 区, 街道] 的前缀。空数组 = 还没开始选 */
  const [chain, setChain] = useState<Region[]>([]);

  // 打开时按已有归属回显整条链路 —— 端上不自己按码长切片，那是国标编码规则，
  // 不该复制到端上（后端 /ops/regions/path 就是干这个的）
  useEffect(() => {
    if (!community) {
      setChain([]);
      return;
    }
    if (!community.regionCode) {
      setChain([]);
      return;
    }
    api.regionPath(community.regionCode).then(setChain).catch(() => setChain([]));
  }, [community]);

  /** 下一级的父码：链尾的码；链空则取顶层（省） */
  const parent = chain.length ? chain[chain.length - 1]!.regionCode : undefined;
  const leaf = chain.length ? chain[chain.length - 1]! : null;

  const options = useQuery({
    queryKey: ["regions", parent ?? "ROOT"],
    // 运营维护面给全量：停用的区划也要看得见，否则再也开不回来
    queryFn: () => api.listRegions(parent, false),
    // 已经选到叶子（街道）就不用再查下一层了
    enabled: !!community && (!leaf || leaf.hasChild),
  });

  const save = useMutation({
    mutationFn: (regionCode: string) =>
      api.setCommunityRegion(community!.communityNo, regionCode),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["communities"] });
      notify.success(c.regionSaved);
      onClose();
    },
  });

  const pick = (r: Region) => setChain((prev) => [...prev, r]);
  const backTo = (i: number) => setChain((prev) => prev.slice(0, i));

  return (
    <Drawer open={!!community} onOpenChange={(o) => !o && onClose()} title={community?.name ?? ""}>
      {community && (
        <>
          <DrawerSection title={c.regionSectionNow}>
            <FieldGrid>
              <Field label={c.colCommunity}>{community.name}</Field>
              <Field label={c.colRegion}>
                {community.regionPath ?? <span className="text-muted-foreground">{c.regionUnset}</span>}
              </Field>
            </FieldGrid>
            {/* 未归属不是「配错了」，是还没配 —— 但它的后果要说清楚 */}
            {!community.regionCode && <Notice className="mt-3">{c.regionUnsetNote}</Notice>}
          </DrawerSection>

          {canWrite && (
            <DrawerSection title={c.regionSectionPick}>
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

              <div className="mt-4 flex gap-2">
                <Button disabled={!leaf || save.isPending} onClick={() => save.mutate(leaf!.regionCode)}>
                  {c.regionSave}
                </Button>
                {community.regionCode && (
                  <Button variant="outline" disabled={save.isPending} onClick={() => save.mutate("")}>
                    {c.regionClear}
                  </Button>
                )}
              </div>
              <p className="txt-caption text-muted-foreground mt-2">{c.regionSaveHint}</p>
            </DrawerSection>
          )}
        </>
      )}
    </Drawer>
  );
}
