// 覆盖范围：类目（P-3.1）、商品池（P-3.2）、库存与预售（P-3.3）。
import type { GoodsAudit, GoodsDetail, Category, Page, ProductGoods, Sku, SpecTemplate, SpuStd, Topic } from "@/lib/types";
import type { CategoryQ, SkuQ, SpecTemplateQ } from "../query";

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

  // ── 标准品库（TDD-标准品库）—— **已接真后端** `/ops/spu-std`

  /** 标准品列表。按 `refCount` 倒序 —— 被引用得多的是「别的店都在用」，对录入的人是有效信号 */
  listSpuStd(q?: { keyword?: string; categoryNo?: string; showArchived?: boolean; page?: number; size?: number }): Promise<Page<SpuStd>>;
  /**
   * 新建 / 更新。**每个规格选项必须填 code** —— 后端会拒，前端也先拦一道：
   * 没有 code 的标准品与商家手输没有区别，它唯一的作用是让人**以为**规格统一了。
   */
  saveSpuStd(v: Partial<SpuStd> & { title: string; categoryNo: string }): Promise<SpuStd>;
  /**
   * 归档。**不检查有没有商品在引用**（与类目归档相反）——
   * `stdNo` 是溯源不是外键：归档只是「以后别再从这条建品」，已经建出来的商品照常在售。
   */
  archiveSpuStd(stdNo: string): Promise<SpuStd>;
  unarchiveSpuStd(stdNo: string): Promise<SpuStd>;

  // ── 主题分类（陈列，批 E）—— **已接真后端** `/ops/topics`

  /** 专题列表。**默认带归档的** —— 看不见归档的，「上周那个专题去哪了」就没有答案 */
  listTopics(q?: { includeArchived?: boolean }): Promise<Topic[]>;
  /** 新建 / 改。`topicNo` 为空 = 新建；结束早于开始会被拒 */
  saveTopic(v: Partial<Topic> & { title: string }): Promise<Topic>;
  /** 归档 / 取消归档。**没有删除** —— 分享出去的海报与历史链接都还指着它 */
  setTopicArchived(topicNo: string, archived: boolean): Promise<Topic>;
  /** 专题里的商品，按专题内排序 */
  listTopicGoods(topicNo: string, q?: { page?: number; size?: number }): Promise<Page<ProductGoods>>;
  /**
   * 整份替换专题里的商品，顺序即展示顺序。
   *
   * <p><b>只收在架商品</b>：摆一件下架/待审的货进来，C 端点进去是空位，
   * 而运营在后台看到它明明在列表里。
   */
  setTopicGoods(topicNo: string, goodsNos: string[]): Promise<Page<ProductGoods>>;

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

  /** 商品详情：三语文案、SKU 矩阵、规格组、驳回原因，审核抽屉读的就是它。 */
  getGoodsDetail(goodsNo: string): Promise<GoodsDetail>;

  /**
   * 平台强制下架（P-3.2.3），goods 粒度 = **撤销过审**：商品回到 `REJECTED`。
   *
   * `reason` 必填且**原样进商家 B 端** —— 商家改完走既有的重新提审链路回来；
   * 不给理由的话他只能反复重提，而每一次重提都要占一次审核人力。
   *
   * 与下面的 {@link forceOffSku} 不是同一件事：那个是 sku 粒度的**压下架**（过审结论还在，
   * 商家自己点一下就能上回来），这个是撤销过审。
   */
  forceOffGoods(goodsNo: string, reason: string): Promise<GoodsDetail>;

  /**
   * 商品审核（P-3.2.2），sku 粒度入口。通过前校验三条：zh 文案齐全、
   * **每个市场都有价格**（B6）、商家持有该类目要求的资质。驳回必须带原因。
   *
   * <p>**已接真后端** `POST /ops/skus/{skuNo}/audit`。后端把它**解析到父商品**
   * 再审：审核判的是「这件商品能不能卖」—— 标题、图、类目、资质都挂在 goods 上，
   * SKU 只是规格与价格。给 SKU 单独一套审核态的话，同一件商品会被审好几遍，
   * 而三个规格审出三个不同结论时，这件商品到底能不能卖没有答案。
   */
  auditSku(skuNo: string, pass: boolean, reason?: string): Promise<Sku>;
  /**
   * 平台**压下架**（P-3.2.3）：必须带原因，原样进商家 B 端。
   * **已接真后端** `POST /ops/skus/{skuNo}/force-off`。
   *
   * 与 {@link forceOffGoods} 不是同一件事，差别在商家的下一步：
   * 那个是撤销过审（商品回 `REJECTED`，必须改完重新提审），
   * 这个只是下架（`OFF_SALE`，问题处理完商家自己点一下就能回来）。
   */
  forceOffSku(skuNo: string, reason: string): Promise<Sku>;
  /**
   * 预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货。
   * **已接真后端** `POST /ops/skus/{skuNo}/presale`。
   *
   * 额度不是给人看的数字：现货卖完后下单会**回落到它上面**继续成交。
   * 允许把额度调到已售之下（不拦）—— 调完这条 SKU 立刻出现在
   * {@link listOversellSkus} 里，有人认领才是重点。
   */
  setSkuPresale(skuNo: string, presaleQuota: number, cutoffAt: string, arriveAt?: string): Promise<Sku>;
  /** sku 粒度全量查询。**已接真后端** `GET /ops/skus`；`presaleOnly` 给「库存与预售」tab 用。 */
  listSkus(q?: SkuQ): Promise<Page<Sku>>;
  /**
   * 超卖告警（P-3.3.3）：已售 > 预售额度。**已接真后端** `GET /ops/skus/oversell`。
   * 只读，处置要人判断是补货还是退单 —— 自动关单会把还能补上的团也关掉。
   */
  listOversellSkus(): Promise<Sku[]>;

  // ── 规格模板（P-3.4 / E27）—— **已接真后端** `/ops/spec-templates/**`

  /**
   * 平台模板列表。只列 `scope=PLATFORM` 的 ——
   * 商家自存的模板归商家，平台端列出来就会有人去改，而改了那家店的历史规格就对不上了。
   */
  listSpecTemplates(q?: SpecTemplateQ): Promise<Page<SpecTemplate>>;
  /**
   * 新建或更新（`templateNo` 为空即新建）。
   *
   * 每个选项**必须带 `code`** —— 这是平台模板存在的唯一理由（B-4.5）：
   * 自由文本下三家店会写成「5 斤」「五斤」「2.5kg」，聚合、比价、搜索全部对不上。
   */
  saveSpecTemplate(
    v: Pick<SpecTemplate, "name" | "options"> & { templateNo?: string; categoryType?: string },
  ): Promise<SpecTemplate>;
  /** 归档：商家侧立刻不再下发。**不是删除** —— 历史商品还要靠 templateNo 解释它的 optionCode。 */
  archiveSpecTemplate(templateNo: string): Promise<SpecTemplate>;
  unarchiveSpecTemplate(templateNo: string): Promise<SpecTemplate>;
}
