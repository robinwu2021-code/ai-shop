import type { MasterData, MasterDataIntentIndustry } from "@shared/types";

/** 行业的兜底档。只有选它时才问「你自己写一行」 */
export const INDUSTRY_OTHER = "OTHER";

/**
 * 报名表上该给哪些行业 —— **意向口径**，不是进件口径。
 *
 * <p>`master.industries` 按 `sys_industry.enabled` 过滤过，那把尺量的是
 * 「平台能不能接这类商家」；一期只开了零售与生活服务两档。拿它渲染报名表的话，
 * 想开餐饮的人只能选「线下零售」—— 而意向表的价值恰恰在于收集平台还接不了的那些，
 * 那条信息在提交的一刻就丢了。
 *
 * <p><b>老后端（没有 `intentIndustries`）时退回进件口径并全标成已开放</b>，
 * 不是退回空：空列表等于这一格选不了，比少几个选项糟得多。
 */
export function industryOptions(master: MasterData | null): MasterDataIntentIndustry[] {
  if (master?.intentIndustries?.length) return master.intentIndustries;
  return (master?.industries ?? []).map((i) => ({
    industry: i.industry,
    name: i.name,
    open: true,
  }));
}

/**
 * 选中的这一档平台还没开放吗。
 *
 * <p><b>没选、或这个码不在表里时返回 false</b> —— 那两种情况下提示都不该出现：
 * 前者人还没选，后者是我们自己认不出这个码，冲着用户说「还没开放」是在猜。
 */
export function industryNotOpen(
  options: MasterDataIntentIndustry[],
  code: string | undefined | null,
): boolean {
  if (!code) return false;
  return options.some((i) => i.industry === code && !i.open);
}

/** 这个码在意向口径里叫什么。认不出来就把码本身给出去，不给空 —— 空格子看着像没填 */
export function industryName(options: MasterDataIntentIndustry[], code: string | undefined): string {
  return options.find((i) => i.industry === code)?.name || code || "";
}
