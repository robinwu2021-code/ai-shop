"use client";

// 只读详情（抽屉内容）。由 ActionHost 的 drawer 操作渲染：
//   render: (ctx) => <DetailBody sections={...} actions={<DetailActions ctx={ctx} keys={["edit"]} />} />
//
// 详情抽屉**只读**；要改，就点底栏按钮 —— 抽屉操作在同一个抽屉里替换（ctx.switchTo），
// 弹框操作叠在上面（ctx.open）。这里不放任何表单控件。
import * as React from "react";
import { DrawerSection, FieldGrid, Field, DrawerFooter } from "./drawer";
import { Button } from "./button";
import type { ActionContext } from "./action-host";

export interface DetailField {
  label: string;
  value: React.ReactNode;
  /** 占满一行（长文本、地址、备注） */
  full?: boolean;
}

export interface DetailSection {
  title: string;
  desc?: string;
  fields?: DetailField[];
  /** 自定义内容（时间线、窄表）放这里，与 fields 二选一或并存 */
  content?: React.ReactNode;
}

export function DetailBody({ sections, actions }: { sections: DetailSection[]; actions?: React.ReactNode }) {
  return (
    <>
      {sections.map((s, i) => (
        <DrawerSection key={s.title} title={s.title} desc={s.desc} first={i === 0}>
          {s.fields && s.fields.length > 0 && (
            <FieldGrid>
              {s.fields.map((f) => (
                <Field key={f.label} label={f.label} className={f.full ? "col-span-2 mb-3" : "mb-3"}>
                  {f.value ?? "—"}
                </Field>
              ))}
            </FieldGrid>
          )}
          {s.content}
        </DrawerSection>
      ))}
      {actions && <DrawerFooter>{actions}</DrawerFooter>}
    </>
  );
}

/**
 * 详情底栏的操作按钮：按当前对象可用的操作过滤（权限码 + visible），抽屉类替换内容、弹框类叠上。
 * 只列你要放进详情的那几个 key，顺序即显示顺序。
 */
export function DetailActions<T>({ ctx, keys }: { ctx: ActionContext<T>; keys: string[] }) {
  const avail = new Map(ctx.available().map((a) => [a.key, a]));
  // 底栏顺序（规范-运营端-列表与抽屉 §4）：危险动作在前，主动作在最右 —— 声明顺序里危险项挪到最前
  const picked = keys.map((k) => avail.get(k)).filter((a): a is NonNullable<typeof a> => !!a);
  const items = [...picked.filter((a) => a.danger), ...picked.filter((a) => !a.danger)];
  if (!items.length) return null;
  return (
    <>
      {items.map((a) => (
        <Button
          key={a.key}
          variant={a.danger ? "destructive" : "outline"}
          onClick={() => (a.kind === "drawer" ? ctx.switchTo(a.key) : ctx.open(a.key))}
        >
          {a.label}
        </Button>
      ))}
    </>
  );
}
