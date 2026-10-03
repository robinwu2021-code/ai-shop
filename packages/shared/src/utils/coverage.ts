// 「这家店的货，买家看不看得见」——**判据只有这一份**。
//
// 后端那一份在 `MerchantPortImpl.reachableCommunities`：范围为空的含义由履约能力决定，
// 而「为空」算的是**纳入项**，不是行数。端上此前有两处各写各的（工作台的开张告警、
// 经营范围页的拦截），且两处都把排除项算成了范围 ——
// 于是一个「我上门送，就是不送 3 幢」的商家，在自提模式下：
//   后端  includes 为空 + 只自提 → 谁也看不到他
//   工作台 serviceAreas.length > 0 → 一切正常，没有任何告警
// 说的和做的正好相反，而两边都不报错。
import type { ServiceArea } from "../types/region";
import { FULFILLMENT_REACH } from "./constants";

/**
 * 纳入项。**排除项不算范围** —— 它是从范围里挖掉的洞。
 *
 * 待审的（PENDING）算不算：**算**。它已经写进库了，只是还没生效；
 * 把它当成「没有」会让商家在等审期间看到一条「你还没选范围」的告警，
 * 而他明明选了 —— 那条告警他消不掉，也无从消起。
 */
export function includedAreas(areas?: ServiceArea[] | null): ServiceArea[] {
  return (areas ?? []).filter((a) => a.mode !== "EXCLUDE");
}

/**
 * 按履约能力判「买家看不看得见这家店」。**与后端 `reachableCommunities` 同一条规则**：
 *
 * - 只自提：没框纳入项 = 没有落点 → 谁也看不到
 * - 开了自送/快递：没框 = 不限 → 全平台可见（再减去排除项）
 *
 * 两者反过来都会出事：把自提的空当成「不限」，一家没配范围的菜摊会突然铺满全平台；
 * 把自送的空当成「谁也看不到」，存量上门商家在迁移当天集体从 C 端消失。
 */
export function visibleToBuyers(reach: string | null | undefined, areas?: ServiceArea[] | null): boolean {
  return reach !== FULFILLMENT_REACH.PICKUP || includedAreas(areas).length > 0;
}
