"use client";

/**
 * ⚠️ 全站唯一的客户端组件。加第二个之前先看 TDD-hxmall-site §3.3 —— 由 lib/constraints.test.ts 拦着。
 *
 * 色值 **import 自产品真源**，不在这里复制。演示的就是 App 里能选的那几套皮肤，
 * 复制一份的话官网迟早展示出一套产品里并不存在的颜色。
 */
import { useState } from "react";
import { SKINS, SKIN_HEX, type SkinId } from "@shared/design/tokens";


/**
 * 皮肤的**营销说法**（色值不在这里，只有叫法与适配的业态）。
 * `Record<SkinId, …>` 是有意的：产品加一套皮肤而这里没跟上，`tsc` 直接红，
 * 不会出现官网少展示一套的情况。
 */
const LABEL: Record<SkinId, string> = {
  brand: "虹选红 · 平台默认",
  fresh: "清新绿 · 生鲜果蔬",
  promo: "促销橙 · 折扣与活动",
  blue: "时尚蓝 · 3C 与家电",
  mono: "极简黑 · 服饰与设计",
  crimson: "正红 · 烟酒与礼品",
  amber: "琥珀 · 烘焙与咖啡",
  teal: "青碧 · 药房与个护",
  violet: "紫罗兰 · 花店与文创",
};

const CN_NUM = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"];

/**
 * ⚠️ **标题与说明不在这里**，在 content/capabilities/index.md 的「店铺配色」小节。
 *
 * 上一版这里自带 SectionHead，于是 md 里写的标题不生效，页面上露出来的是组件里
 * 残留的旧文案 —— 全站文案都改过一轮了，只有这一句还在用早先的口吻，
 * 而它不在 content/ 下，改文案的人根本找不到它。
 */
export function SkinShowcase() {
  const [active, setActive] = useState<SkinId>(SKINS[0]!.id);
  const demo = SKIN_HEX[active].light;
  const count = CN_NUM[SKINS.length] ?? String(SKINS.length);

  return (
    <>
      <div className="mt-2 grid items-center gap-[clamp(30px,5vw,64px)] lg:grid-cols-2">
        <div>
          <div className="flex flex-wrap gap-3" role="group" aria-label="商家配色">
            {SKINS.map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => setActive(s.id)}
                aria-pressed={active === s.id}
                aria-label={LABEL[s.id]}
                style={{ background: SKIN_HEX[s.id].light }}
                /* 内圈描边不是装饰：极简黑 #18181B 压在墨底 #17181a 上对比 1.0，没有它这颗色卡等于消失 */
                className={`size-[46px] cursor-pointer rounded-full border-2 shadow-[inset_0_0_0_1px_rgba(255,255,255,.28)] transition-transform hover:scale-110 ${
                  active === s.id ? "border-white" : "border-transparent"
                }`}
              />
            ))}
          </div>

          <p className="mt-4.5 text-sm text-white/66">
            当前：<b className="text-white">{LABEL[active]}</b>
          </p>
          <p className="mt-5 max-w-[56ch] text-[15px] text-white/66">
            这{count}套配色不是官网上画的示意 —— 它们就是 App 里能选的那几套，色值同一份真源，
            每一套的文字对比度都过 WCAG AA。
          </p>
        </div>

        {/* 演示机：主色由一个自定义属性驱动，和产品里换肤的做法一样 */}
        <div
          style={{ "--demo": demo } as React.CSSProperties}
          aria-hidden
          className="mx-auto w-full max-w-[320px] rounded-[26px] bg-white p-4 text-ink shadow-[0_24px_60px_rgba(0,0,0,.35)]"
        >
          <div className="mx-auto mt-0.5 mb-3.5 h-[5px] w-11 rounded-full bg-[#e3e5e9]" />
          <div className="flex items-center gap-2.5">
            <span className="grid size-9.5 place-items-center rounded-xl bg-[var(--demo)] text-[15px] font-bold text-white">
              花
            </span>
            <span>
              <b className="text-[15px]">转角花店</b>
              <small className="block text-[11.5px] font-medium text-muted">
                步行 3 分钟 · 今天 21:00 打烊
              </small>
            </span>
          </div>
          <div className="mt-3.5 grid h-[74px] place-items-center rounded-xl bg-[color-mix(in_srgb,var(--demo)_12%,#fff)] text-[13px] font-semibold text-[var(--demo)]">
            今日到货 · 满 39 免配送
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2.5">
            {[
              { name: "单支洋桔梗", price: "¥ 8.00" },
              { name: "周末花束", price: "¥ 68.00" },
            ].map((it) => (
              <div key={it.name} className="rounded-xl border border-[#edeef1] p-2.5">
                <div className="h-13 rounded-lg bg-[#f2f3f5]" />
                <span className="mt-2 block text-[11.5px] text-muted">{it.name}</span>
                <b className="mt-0.5 block text-[13px] text-[var(--demo)]">{it.price}</b>
              </div>
            ))}
          </div>
          <div className="mt-3.5 grid h-10.5 place-items-center rounded-full bg-[var(--demo)] text-sm font-semibold text-white">
            下单 · 楼下自提
          </div>
        </div>
      </div>
    </>
  );
}
