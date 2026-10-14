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
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { Community, Region } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { RegionChooser } from "./region-chooser";
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
  /** 选择器当前落在哪一级。null = 还没选到任何一级，此时不能保存 */
  const [leaf, setLeaf] = useState<Region | null>(null);

  // 抽屉换一条记录时重置选择 —— 不重置的话，上一条选到的街道会带到下一条上，
  // 而运营看不出这个「已选」不是他为这条记录选的
  useEffect(() => setLeaf(null), [community]);

  const save = useMutation({
    mutationFn: (regionCode: string) =>
      api.setCommunityRegion(community!.communityNo, regionCode),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["communities"] });
      notify.success(c.regionSaved);
      onClose();
    },
  });

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
              <RegionChooser c={c} value={community.regionCode} onChange={setLeaf} />

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
