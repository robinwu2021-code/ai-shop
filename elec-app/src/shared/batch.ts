// 上传预览在「上传 · 列映射」与「上架前确认」两页之间交接：放内存省一次请求。
// 丢了（冷启动、从上传记录进来）确认页就按批次号去后端取（/elec/b/stock/batch/{no}）。
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
