// 覆盖范围：类目（P-3.1）、商品池（P-3.2）、库存与预售（P-3.3）。
import type { GoodsAudit, Category, Page, ProductGoods, Sku } from "@/lib/types";
import type { CategoryQ, SkuQ } from "../query";

export interface ProductApi {
  // ── 商品审核（P-3.2）—— **已接真后端** `/ops/goods/**`，goods 粒度

  /** 待审队列。分页 */
  listGoodsAuditQueue(q?: { page?: number; size?: number }): Promise<Page<GoodsAudit>>;
  /**
   * 审核商品。
   *
   * @param reason 驳回**必填** —— 商家拿不到理由就只能反复重提，
   *   而每一次重提都要占一次审核人力
   */
  auditGoods(goodsNo: string, approved: boolean, reason?: string): Promise<GoodsAudit>;

  /** 类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快）。 */
  listCategories(q?: CategoryQ): Promise<Category[]>;
  saveCategory(v: Pick<Category, "categoryNo" | "name" | "parentNo" | "template" | "qualifications"> & { i18nEn?: string }): Promise<Category>;
  /** 有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝。 */
  archiveCategory(categoryNo: string): Promise<Category>;
  unarchiveCategory(categoryNo: string): Promise<Category>;

  /**
   * 商品池：按商家/类目/关键词/状态筛，goods 粒度（每行一个商品，SKU 嵌在 `skus[]` 里）。
   * 与 {@link listGoodsAuditQueue} 的区别：那个固定只给待审队列，这个是日常"这个商家/
   * 这个类目下有什么"的浏览查询，status 留空 = 不筛状态。**已接真后端** `GET /ops/goods`。
   */
  listGoods(q?: SkuQ): Promise<Page<ProductGoods>>;

  /**
   * 商品审核（P-3.2.2）。通过前校验三条：zh 文案齐全、**每个市场都有价格**（B6）、
   * 商家持有该类目要求的资质。驳回必须带原因。
   *
   * <p>⚠️ **真实后端没有 sku 粒度的审核**——只有 goods 粒度的
   * {@link auditGoods}（已接真后端）。这几个 sku 级动作
   * （{@link auditSku}/{@link forceOffSku}/{@link setSkuPresale}/{@link listSkus}/
   * {@link listOversellSkus}）目前只有 mock 实现，真实模式下调用会 404 ——
   * 保留是因为 mock 演示了完整的校验规则（B6、类目资质、状态机），
   * 接真后端需要先补 sku 级的审核/预售接口，属于独立的后续开发。
   */
  auditSku(skuNo: string, pass: boolean, reason?: string): Promise<Sku>;
  /** 强制下架（P-3.2.3）：必须带原因，原样进商家 B 端。同上，真实模式未接。 */
  forceOffSku(skuNo: string, reason: string): Promise<Sku>;
  /** 预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货。同上，真实模式未接。 */
  setSkuPresale(skuNo: string, presaleQuota: number, cutoffAt: string): Promise<Sku>;
  /** sku 粒度全量查询，只给「库存与预售」tab 用。同上，真实模式未接。 */
  listSkus(q?: SkuQ): Promise<Page<Sku>>;
  /** 超卖告警（P-3.3.3）：已售 > 预售额度。只读，处置要人判断是补货还是退单。同上，真实模式未接。 */
  listOversellSkus(): Promise<Sku[]>;
}
