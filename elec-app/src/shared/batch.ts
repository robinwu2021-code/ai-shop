// 上传预览在「上传 · 列映射」与「上架前确认」两页之间交接。后端没有「按批次号取预览」的读接口，
// 所以放内存：丢了（冷启动）就回上传页重传一次 —— 预览时一行库存都没动，重传没有代价。
import type { ElecBatchPreview } from "@shared/types";

let current: ElecBatchPreview | null = null;
let pickedName = "";

/** @param fileName 端上选中的文件名。后端的 fileName 是上传临时路径的名字，认不出是哪张表 */
export function setBatch(p: ElecBatchPreview, fileName?: string): void {
  current = p;
  if (fileName !== undefined) pickedName = fileName;
}

export function batchFileName(): string {
  return pickedName;
}

export function getBatch(batchNo: string): ElecBatchPreview | null {
  return current && current.batchNo === batchNo ? current : null;
}
