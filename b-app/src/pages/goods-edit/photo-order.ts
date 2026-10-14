// 商品图拖动排序之后，**那两个字段各自变成什么**。
//
// 界面上商品图是一组（`photos` = 封面 + 轮播去重），存储上是 `cover` 与 `images` 两列。
// 拖动改的是这一组的顺序，而「排第一就是封面」是这套界面的约定（见 goods-edit 的 photos 注释）——
// 所以挪到第一位 = 换封面，这一步不该再要他多点一次。
//
// <p>单独一个文件是为了能单测：`useGoodsPhotos` 里有 `useI18n()`，在 node 里起不来。
import { moveItem } from "@ai-shop/ui/drag-sort";

/** 排序结果：两列各自的新值 */
export interface PhotoOrder {
  cover: string;
  images: string[];
}

/**
 * 把第 `from` 张挪到第 `to` 位。
 *
 * @param photos 界面上的那一组（已去重，第一张是封面）
 * @returns 新的 `cover` 与 `images`。**`images` 整份带上封面那一张** ——
 *          与现在的存储一致（上传时既写 cover 也进 images），`photos` 再去重成一组。
 *          越界或没挪动时原样返回，调用方不用自己判。
 */
export function reorderPhotos(photos: readonly string[], from: number, to: number): PhotoOrder {
  const next = moveItem([...photos], from, to);
  return { cover: next[0] ?? "", images: next };
}
