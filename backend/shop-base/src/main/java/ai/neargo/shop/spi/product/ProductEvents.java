package ai.neargo.shop.spi.product;

import ai.neargo.shop.event.DomainEvent;

/** 商品域对外发布的事件。载荷自带消费方所需字段，消费方不回查主表（同 OrderEvents）。 */
public final class ProductEvents {

    private ProductEvents() {
    }

    /**
     * SKU 建成或改过。消费方：进销存（把这个 SKU 放上账 —— 建物料与外部引用）。
     *
     * <p><b>为什么必须有这个事件</b>：商品域一个字都不认识进销存
     *（`shop-core/product` 对进销存 ACL 的引用数为 0，这是「进销存可独立交付」的前提），
     * 而两个域只在 `sku_no` 这一点连着。在 2026-08-28 之前接这一点的<b>只有搬运跑批</b> ——
     * 于是建 SKU 不会建账，那个 SKU 在库存里根本不存在，看不到、盘不着、进不了货，
     * <b>而任何地方都不会报错</b>。
     *
     * <p><b>载荷自带消费方所需字段，消费方不回查主表</b>（同 OrderEvents）——
     * 回查就意味着进销存要认识 `prd_sku`，那正是这个事件要避免的事。
     *
     * @param title 商品标题。进销存那边的物料名用它，<b>不是 goodsNo</b> ——
     *              曾经传的是货号，商家在库存清单上看到的是一列 `G0001`
     * @param specText 规格文案，如「10斤装」
     */
    public record SkuUpserted(String skuNo, String entityNo, String goodsNo, String title,
                              String specText, String barcode, String merchantSkuCode,
                              String saleUnit) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "SKU";
        }

        @Override
        public String aggregateId() {
            return skuNo;
        }

        @Override
        public String eventType() {
            return "SKU_UPSERTED";
        }
    }

    /**
     * 商品的上架状态变了。消费方：inventory（把它投影到物料上）。
     *
     * <p><b>为什么不塞进 {@link SkuUpserted}</b>：商家点「下架」时 SKU 内容一个字都没变，
     * 那条事件根本不会发。上架状态是**独立的动作**，需要独立的信号。
     *
     * <p><b>发布点只有一个</b>：{@code MerchantGoodsServiceImpl.syncPool} ——
     * 上下架有十个入口（手动上下架、平台强制下架、审核通过、店级开关、批量……），
     * 逐个发必漏一个，而漏掉的那个会让物料上的标记停在上一个状态，
     * <b>比没有标记更坏</b>：界面上明明写着「已下架」，商家却在店里看得到它。
     * syncPool 是那十处的唯一汇聚点，且它的语义正是「这件货整体还卖不卖变了」。
     *
     * @param skuNos 这件商品名下的 SKU。<b>载荷自带，消费方不回查主表</b> ——
     *               进销存那边认的是 skuNo，而它读不到 {@code prd_sku}
     */
    public record GoodsOnSaleChanged(String goodsNo, String entityNo, boolean onSale,
                                     java.util.List<String> skuNos) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "GOODS";
        }

        @Override
        public String aggregateId() {
            return goodsNo;
        }

        @Override
        public String eventType() {
            return "GOODS_ON_SALE_CHANGED";
        }
    }

    /**
     * 新评价发布。消费方：message（B 端提醒，B-N-3）。
     *
     * @param rating 1–5。消费方靠它区分「新评价」与「差评」（≤2 星）——
     *               差评要单独点名，混在普通评价里商家会当成例行夸奖划掉
     */
    public record ReviewCreated(String reviewNo, String entityNo, String goodsNo, int rating)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return "REVIEW";
        }

        @Override
        public String aggregateId() {
            return reviewNo;
        }

        @Override
        public String eventType() {
            return "REVIEW_CREATED";
        }
    }
}
