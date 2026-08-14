package ai.neargo.shop.spi.product;

import ai.neargo.shop.event.DomainEvent;

/** 商品域对外发布的事件。载荷自带消费方所需字段，消费方不回查主表（同 OrderEvents）。 */
public final class ProductEvents {

    private ProductEvents() {
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
