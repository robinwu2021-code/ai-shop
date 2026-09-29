// 询价草稿：批量查勾上的几十行料号要带到询价页，放不进 URL（小程序页面参数有长度上限）。
// 只在内存里 —— 它是两页之间的一次交接，不是要保留的东西；冷启动丢了就从批量查再来一次。
import type { ElecRfqLineReq } from "@shared/types";

/** 询价页上的一行：数量可以还没填（批量查里那行没写数量） */
export type DraftLine = Omit<ElecRfqLineReq, "qty"> & { qty?: number | null };

let pending: DraftLine[] = [];

export function setRfqDraft(lines: DraftLine[]): void {
  pending = lines;
}

/** 取一次就清掉：返回上一页再进来不该还是上一次的那批 */
export function takeRfqDraft(): DraftLine[] {
  const out = pending;
  pending = [];
  return out;
}
