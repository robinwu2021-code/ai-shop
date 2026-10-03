package ai.neargo.shop.product.stats;

/**
 * 商品域平台统计（M4）。
 *
 * <p><b>此前这个域一个统计数字都没有</b>（缺口清单里商品③ 是 0/5），而商品是这个平台的主体。
 * 骨架（类目、规格库、标准品）画得最全，却答不出「画的这些骨架有多少真的被用上了」。
 *
 * <p>四个数，每一个都对应一个能做的事：
 * <ul>
 *   <li><b>类目使用率</b> —— 73 个类目只有 14 个被用过。没被用过的那些要么该收起来，
 *       要么说明商家找不到它们。</li>
 *   <li><b>条码 / 货号覆盖率</b> —— 209 个 SKU 里 1 个有条码。
 *       <b>扫码功能的天花板就是这个数</b>：覆盖率不上去，扫码入库永远是个摆设。</li>
 *   <li><b>规格库使用率</b> —— 规格库只增不减，而「哪些维度从没挂上过类目」
 *       是清理它的唯一依据。</li>
 *   <li><b>审核通过率与吞吐</b> —— 驳回多说明规则没讲清楚，吞吐低说明人手不够，
 *       两者要分开看。</li>
 * </ul>
 */
public interface ProductStatsService {

    Stats stats(int auditDays);

    /**
     * @param categories       类目总数
     * @param categoriesUsed   至少被一个商品用过的类目数
     * @param skus             SKU 总数
     * @param skusWithBarcode  填了条码的
     * @param skusWithCode     填了商家货号的
     * @param specDims         规格维度总数
     * @param specDimsBound    至少挂到一个类目上的维度数
     * @param auditApproved    审核通过的商品数（累计状态，不是期间）
     * @param auditRejected    审核驳回的
     * @param auditPending     待审的
     * @param auditActions     最近 N 天的审核动作数 —— <b>吞吐</b>，与上面三个累计数不是一回事
     * @param auditDays        上面那个 N
     */
    record Stats(long categories, long categoriesUsed,
                 long skus, long skusWithBarcode, long skusWithCode,
                 long specDims, long specDimsBound,
                 long auditApproved, long auditRejected, long auditPending,
                 long auditActions, int auditDays) {
    }
}
