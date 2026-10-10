// 受众项 ↔ 界面文字。选人面板（biz-audience-picker）、活动、发券、发消息四处共用。
//
// 受众存的是号（标签号 / 人群号），界面要的是名字。名字只在这里拼一次 ——
// 三个页面各拼一遍的话，同一个受众在活动详情里叫「沉睡 · 爱囤货」、在发券页叫「2 项」。
import { api } from "@/api";
import type { AudienceItem, MemberSegment, MemberTag } from "@shared/types";

type T = (k: string, a?: Record<string, unknown>) => string;

export interface AudienceNames {
  tags: MemberTag[];
  segments: MemberSegment[];
}

/** 「沉睡 · 爱囤货 · 南门店老客」。空 = 所有人（活动）/ 未选 */
export function audienceLabel(items: AudienceItem[], names: AudienceNames, t: T): string {
  if (!items.length) return "";
  return items.map((it) => {
    switch (it.type) {
      case "ALL": return t("audience.allMembers");
      case "NON_MEMBER": return t("audience.nonMember");
      case "LEVEL": return t(`members.level.${it.value}`);
      case "SOURCE": return t(`members.source.${it.value}`);
      case "TAG": return names.tags.find((x) => x.tagNo === it.value)?.name ?? t("audience.gone");
      case "SEGMENT": return names.segments.find((x) => x.segmentNo === it.value)?.name ?? t("audience.gone");
      default: return it.value;
    }
  }).join(" · ");
}

/** 回显已有受众时用：自己去取标签与人群的名字 */
export async function loadAudienceLabel(items: AudienceItem[], t: T): Promise<string> {
  if (!items.some((i) => i.type === "TAG" || i.type === "SEGMENT")) {
    return audienceLabel(items, { tags: [], segments: [] }, t);
  }
  const [tags, segments] = await Promise.all([api.mMemberTags(), api.mMemberSegments()]);
  return audienceLabel(items, { tags, segments }, t);
}

export const itemKey = (it: AudienceItem) => `${it.type}:${it.value}`;

/*
 * 「对这批人」→ 发券 / 发消息：中间要先去券列表挑一张券，隔着两跳。
 * 用查询串一路传受众，每一跳都要记得转交；漏一跳就静默丢成「全部会员」。
 * 所以放一份待带入的受众在这里，目标页读到就预选、读完即清；十分钟没人取就作废 ——
 * 商家中途去干别的，回头从别处点进发放页时不该还带着上一次的人。
 */
const PENDING_TTL = 10 * 60_000;
let pending: { items: AudienceItem[]; label: string; at: number } | null = null;

export function setPendingAudience(items: AudienceItem[], label: string) {
  pending = { items: items.map((x) => ({ ...x })), label, at: Date.now() };
}

export function takePendingAudience(): { items: AudienceItem[]; label: string } | null {
  const p = pending;
  pending = null;
  return p && Date.now() - p.at < PENDING_TTL ? { items: p.items, label: p.label } : null;
}
