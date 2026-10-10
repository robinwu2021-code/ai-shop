package ai.neargo.shop.invbridge;

/**
 * 清掉「来源 SKU 已经没了」的空壳物料。
 *
 * <h2>为什么它在 shop-app</h2>
 * 判据要<b>同时读两边</b>：「这个 skuNo 还在不在」在平台的 {@code prd_sku}，
 * 「这件物料有没有库存」在进销存库。进销存域读不到 {@code prd_*} ——
 * 这条边界正是它能独立交付的原因。和 {@link InventoryHealthService}、
 * {@link InventoryBackfillService} 同一个理由、同一个包。
 *
 * <h2>它治的是一个查得到的事实</h2>
 * 2026-09-18 线上：{@code prd_goods} 全库只剩 1 条，而 {@code inv_item} 有 211 条 ——
 * 其中 209 条是 8-27 那批种子，引用的 {@code SK0001…} 在 {@code prd_sku} 里早已不存在。
 * 店主看到的症状是<b>「商品库一件都没有，进货页却列出两百多件」</b>：
 * 两个数来自两个库，而挑货那一条读的是物料，不是商品。
 *
 * <h2>它同时是存量补登</h2>
 * {@code SkuRetired} 那条信号是<b>向前的</b> —— 只在退休发生的那一刻触发。
 * 线上已经躺着的那些（旧 SKU 早被软删、旧物料带着库存留下）永远等不到那条事件，
 * 于是一个标记都不会有。这把跑批每天扫一遍，<b>把它们补上标记</b>，
 * 空的顺手归档 —— 两条路（事件与跑批）对同一件物料给出的答案必须一致。
 *
 * <h2>为什么不做成级联删除</h2>
 * 删 SKU 时顺手删物料是错的：物料上可能还挂着库存和历史流水，
 * 而且那会让进销存反过来依赖商品的生命周期。这里做的是
 * <b>只归档、不删除，且有库存的一律不动</b>（判据在
 * {@code InventoryAclService.retireItemIfEmpty}）。
 *
 * <h2>为什么是任务不是接口</h2>
 * 它是一次性的清理动作，不需要人盯着看结果，也不该给任何 B 端入口 ——
 * 和 {@code inv-recon} 一样挂到独立调度器上，运营要的话可以手动触发一次。
 */
public interface InventoryOrphanSweepService {

    /**
     * @param dryRun 只算不写。<b>先跑一次 dryRun</b> —— 报告里的 {@code retired}
     *               是「会归档几件」，跟真跑一遍是同一个数
     * @param limit  一次最多查多少条 {@code AISHOP} 引用。扫不完会在报告里说出来，
     *               <b>不静默截断</b>
     * @param entityNo 只扫这一家；{@code null} = 全平台（任务走的是这一条）。
     *                 收窄不是为了性能，是为了<b>让它能被测</b>：共享库里跑全平台，
     *                 会把别的用例刚建的物料一并归档，而报错永远不指向真因
     */
    Report sweep(boolean dryRun, int limit, String entityNo);

    /**
     * @param scanned  查了多少条引用
     * @param orphans  其中来源 SKU 已不存在的有几条
     * @param retired  真的归档了几件（{@code dryRun} 时是「会归档几件」）
     * @param kept     孤儿但<b>还有库存</b>、因此留着的有几件。<b>留着不等于不管</b>：
     *                 它们会被标上退休记号，在挑货弹层与库存列表里认得出来，
     *                 并由健康度的 {@code RETIRED_WITH_STOCK} 点名。
     *                 这个数不是噪声：它是需要人去盘掉或报损掉的坏账，
     *                 归档掉它们账就永远平不了
     * @param complete 有没有翻到底。{@code false} 时上面几个数只是「看过的那些」
     */
    record Report(int scanned, int orphans, int retired, int kept, boolean complete) {

        public String detail() {
            return "扫描引用 %d 条，孤儿 %d 条：归档 %d 件，有库存留下 %d 件%s"
                    .formatted(scanned, orphans, retired, kept, complete ? "" : "（未扫完）");
        }
    }
}
