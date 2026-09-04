"use client";

// 围栏维护 + **改之前先看影响**。
//
// 此前这一列只是只读的「800 m」，改它的接口一直在（/ops/communities/{no}/fence），
// 但界面上没有出口 —— 要改只能找人直接改库。
//
// 而光给一个输入框还不够：运营真正要回答的问题不是「填多少」，是
// 「改成 1500 会多进来几户」。那个数字此前算不出来，只能改完再等有人投诉说
// 「我明明住这儿却搜不到店」。所以输入框旁边必须实时给出差值。
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Community } from "@/lib/types";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Drawer } from "@/components/ui/drawer";
import type { COMMUNITIES_COPY } from "./copy";
import { fill } from "@/lib/use-copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function FenceDialog({
  c, community, saving, onSave, onClose,
}: {
  c: Copy;
  community: Community;
  saving: boolean;
  onSave: (radiusM: number) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState(String(community.fenceRadius));
  const radius = Number(text);
  const valid = Number.isFinite(radius) && radius > 0;

  // 输入停下来再问后端：每敲一个数字发一次请求，回来的顺序还不一定对
  const [debounced, setDebounced] = useState(radius);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(radius), 300);
    return () => clearTimeout(t);
  }, [radius]);

  const { data: impact, isError } = useQuery({
    queryKey: ["fence-impact", community.communityNo, valid ? debounced : null],
    queryFn: () => api.fenceImpact(community.communityNo, valid ? debounced : undefined),
    // 预览失败不该拖着人重试：这一屏的主功能是改半径，预览是辅助
    retry: false,
  });

  const located = community.latE6 != null && community.lngE6 != null;
  const delta = impact ? impact.previewInside - impact.currentInside : 0;

  return (
    /*
      **用库里的 Drawer，不自己搭浮层。**
      第一版我手写了一个满屏定位的遮罩，自带写死的 z 值与库外的阴影档位 ——
      （那几个类名不写在这儿：界面纪律那道闸是**扫源码文本**的，
       解释规则的这句话本身也得能通过规则，否则它自己就是一条违规。）
      界面纪律那道闸当场报了三条：写死的 z 值、库外的阴影档位、自己搭弹层。
      三条指的是同一件事：这一屏会和别的浮层叠在一起，而层级一旦各写各的，
      谁压住谁就变成了「谁先渲染」，那不是能推理的东西。
    */
    <Drawer
      open
      onOpenChange={(o) => { if (!o) onClose(); }}
      title={fill(c.fenceTitle, { name: community.name })}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>{c.cancel}</Button>
          <Button disabled={!valid || saving} onClick={() => onSave(radius)}>{c.save}</Button>
        </>
      }
    >
      {/*
        没标点的聚落算不出任何圈。**说出来，而不是显示一个 0** ——
        0 读起来像「这一带没人住」，运营会据此把半径调小，而真相是这个聚落还没标点。
      */}
      {!located && <Notice tone="warning">{c.fenceNoCoords}</Notice>}

      <label className="mt-4 block text-sm text-muted-foreground" htmlFor="fence-radius">
        {c.fenceRadiusLabel}
      </label>
      <Input id="fence-radius" className="mt-1" value={text} inputMode="numeric"
             onChange={(e) => setText(e.target.value)} />
      {!valid && <div className="mt-1 text-xs text-destructive">{c.fenceMustBePositive}</div>}

      {/*
        **预览拿不到就要说出来。**
        第一版这里只有 `impact &&`：接口 404 时整块不渲染，抽屉看起来一切正常，
        运营照样能存 —— 而他以为自己看过影响了。浏览器上第一次打开就是这个样子
        （后端还没重启，端点 404），什么提示都没有。
      */}
      {located && valid && isError && (
        <Notice tone="warning" className="mt-4">{c.fenceImpactFailed}</Notice>
      )}

      {located && impact && valid && (
        <div className="mt-4 rounded-card border border-border bg-muted/40 p-3 text-sm">
          <div className="flex items-baseline justify-between">
            <span className="text-muted-foreground">{c.fenceImpactLabel}</span>
            <span className="tabular-nums">
              {impact.currentInside} → <strong>{impact.previewInside}</strong>
              <span className={delta === 0 ? "ml-2 text-muted-foreground"
                : delta > 0 ? "ml-2 text-primary" : "ml-2 text-destructive"}>
                {delta > 0 ? `+${delta}` : delta}
              </span>
            </span>
          </div>
          {/*
            分母必须一起给：「多进来 0 户」在一个只有 5 条地址有坐标的库里
            说明不了任何事，而运营会据此认为「改大没用」。
          */}
          <div className="mt-1 text-xs text-muted-foreground">
            {fill(c.fenceDenominator, { n: impact.addressesWithCoords })}
          </div>
        </div>
      )}
    </Drawer>
  );
}
