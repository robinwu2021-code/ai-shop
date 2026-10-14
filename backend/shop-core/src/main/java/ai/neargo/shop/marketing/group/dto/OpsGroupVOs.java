package ai.neargo.shop.marketing.group.dto;

/**
 * 拼团与报价的**运营治理视图**（{@code GET /ops/groups} / {@code /ops/quotes}）。
 *
 * <p><b>为什么不复用 {@code GroupVOs.GroupBuyVO} / {@code QuoteVO}</b>：
 * 那两个是 C 端视图，带 {@code joined}（我参没参团）、{@code isOwner}、
 * {@code chosen}（我选没选这条报价）—— <b>买家视角</b>的字段。
 *
 * <p>最危险的一处是 {@code joined}：C 端 VO 里它是 {@code boolean}「我参没参团」，
 * 而 ops-web 的同名字段是 {@code number}「已参团人数」。
 * <b>同名、不同义、还不同类型</b> —— JSON 里发过去一个 {@code false}，
 * 前端拿它当人数用，页面上就是一个不报错的错数字。
 * 光改字段名对不齐这种，必须分开建 VO。
 *
 * <p>字段名对齐 ops-web 的 {@code GroupCampaign} / {@code Quote}，
 * 与 {@code OpsCouponVO} 同一条原则：这个 VO 只有一个消费方，跟着它走。
 */
public final class OpsGroupVOs {

    private OpsGroupVOs() {
    }

    /**
     * @param skuTitle    商品标题快照
     * @param originPrice 原价（分）
     * @param groupPrice  团购价（分）
     * @param minCount    起团人数
     * @param joined      <b>已参团人数</b>（不是「我参没参团」）
     * @param endAt       成团截止（毫秒）
     */
    public record OpsGroupVO(String groupNo,
                             String merchantNo,
                             String merchantName,
                             String skuTitle,
                             long originPrice,
                             long groupPrice,
                             int minCount,
                             int joined,
                             String status,
                             long endAt,
                             long createdAt) {
    }

    /**
     * @param demandNo     所报的需求单（后端列名是 {@code request_no}）
     * @param demandTitle  需求标题快照。<b>取不到时给空串而不是编一个</b> ——
     *                     运营在这一列上认单，一个猜出来的标题会让他认错单
     * @param price        单价（分）
     * @param validTo      报价有效期（毫秒）
     * @param priceChanges 改价次数（ADR-003：不禁止改价，但每次都公示）
     * @param breached     是否已判毁约。由 {@code status == BREACH} 推出，
     *                     不再让端上按状态串自己判 —— 两处各判一次迟早分岔
     */
    public record OpsQuoteVO(String quoteNo,
                             String demandNo,
                             String demandTitle,
                             String merchantNo,
                             String merchantName,
                             long price,
                             int minQty,
                             long validTo,
                             int priceChanges,
                             boolean breached,
                             String status,
                             long createdAt) {
    }
}
