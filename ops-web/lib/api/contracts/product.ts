// 覆盖范围：类目（P-3.1）、商品池（P-3.2）、库存与预售（P-3.3）。
import type { Category, Page, Sku } from "@/lib/types";
import type { CategoryQ, SkuQ } from "../query";

export interface ProductApi {
  /** 类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快）。 */
  listCategories(q?: CategoryQ): Promise<Category[]>;
  saveCategory(v: Pick<Category, "categoryNo" | "name" | "parentNo" | "template" | "qualifications"> & { i18nEn?: string }): Promise<Category>;
  /** 有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝。 */
  archiveCategory(categoryNo: string): Promise<Category>;
  unarchiveCategory(categoryNo: string): Promise<Category>;

  listSkus(q?: SkuQ): Promise<Page<Sku>>;
  /**
   * 商品审核（P-3.2.2）。通过前校验三条：zh 文案齐全、**每个市场都有价格**（B6）、
   * 商家持有该类目要求的资质。驳回必须带原因。
   */
  auditSku(skuNo: string, pass: boolean, reason?: string): Promise<Sku>;
  /** 强制下架（P-3.2.3）：必须带原因，原样进商家 B 端。 */
  forceOffSku(skuNo: string, reason: string): Promise<Sku>;
  /** 预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货。 */
  setSkuPresale(skuNo: string, presaleQuota: number, cutoffAt: string): Promise<Sku>;
  /** 超卖告警（P-3.3.3）：已售 > 预售额度。只读，处置要人判断是补货还是退单。 */
  listOversellSkus(): Promise<Sku[]>;
}
