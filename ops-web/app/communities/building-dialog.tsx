"use client";

// 建一栋楼。**只有运营能建** —— 归属是声明的，让商家自己挑父级会挑错，而挑错不报错：
// 一栋楼挂到隔壁小区下面，它就跟着隔壁小区的经营范围走，而两边的商品池不同。
//
// 这一屏刻意只有四个字段。**没有「街道」那一栏**：街道从所属聚落继承，
// 让运营再填一次就会有不一致的那一天，而「楼挂的街道和它所在小区不是同一个」
// 这种数据错的症状是它在「按街道覆盖」里悄悄归到了别人那儿，没有任何人会发现。
import { useState } from "react";
import type { Community } from "@/lib/types";
import { Input, Select } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Drawer } from "@/components/ui/drawer";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function BuildingDialog({
  c, candidates, saving, onSave, onClose,
}: {
  c: Copy;
  /** 可当父级的聚落。**已经是楼栋的不在里面** —— 归属只做两层 */
  candidates: Community[];
  saving: boolean;
  onSave: (draft: { name: string; address?: string; parentNo: string }) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [parentNo, setParentNo] = useState("");
  const parent = candidates.find((x) => x.communityNo === parentNo);
  const valid = name.trim().length > 0 && !!parent;

  return (
    // 浮层走库里的 Drawer（同 FenceDialog 里那段说明）：自己搭遮罩会让层级各写各的
    <Drawer
      open
      onOpenChange={(o) => { if (!o) onClose(); }}
      title={c.buildingTitle}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>{c.cancel}</Button>
          <Button disabled={!valid || saving}
                  onClick={() => onSave({ name: name.trim(), address: address.trim() || undefined, parentNo })}>
            {c.save}
          </Button>
        </>
      }
    >
      <label className="block text-sm text-muted-foreground" htmlFor="b-name">{c.buildingName}</label>
      <Input id="b-name" className="mt-1" value={name} placeholder={c.buildingNamePlaceholder}
             onChange={(e) => setName(e.target.value)} />

      <label className="mt-3 block text-sm text-muted-foreground" htmlFor="b-parent">{c.buildingParent}</label>
      <Select id="b-parent" className="mt-1" value={parentNo} onChange={(e) => setParentNo(e.target.value)}>
        <option value="">{c.buildingParentEmpty}</option>
        {candidates.map((x) => (
          <option key={x.communityNo} value={x.communityNo}>
            {x.name}{x.regionPath ? ` · ${x.regionPath.split(" / ").pop()}` : ""}
          </option>
        ))}
      </Select>
      <div className="mt-1 text-xs text-muted-foreground">{c.buildingTwoLevels}</div>
      {/*
        继承来的街道要**当场显示出来**：不显示的话运营无从确认自己挑对了父级，
        而挑错父级的后果（这栋楼跟着别人的经营范围走）在任何界面上都看不见。
      */}
      {parent && (
        <div className="mt-1 text-xs text-muted-foreground">
          {c.buildingParentHint}
          {parent.regionPath ? ` · ${parent.regionPath}` : ""}
        </div>
      )}

      <label className="mt-3 block text-sm text-muted-foreground" htmlFor="b-addr">{c.buildingAddress}</label>
      <Input id="b-addr" className="mt-1" value={address} onChange={(e) => setAddress(e.target.value)} />

      <Notice tone="info" className="mt-4">{c.buildingFenceHint}</Notice>
    </Drawer>
  );
}
