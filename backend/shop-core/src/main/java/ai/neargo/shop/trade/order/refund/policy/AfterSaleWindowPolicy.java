package ai.neargo.shop.trade.order.refund.policy;

/** 售后期：订单完成之后多久内还能申请。 */
public interface AfterSaleWindowPolicy {

    /** 截止时刻（毫秒）；未完成（completedAt 为空）返回 null = 不限 */
    Long deadlineAt(Long completedAt, String reasonCode);

    default boolean isOpen(Long completedAt, String reasonCode, long now) {
        Long d = deadlineAt(completedAt, reasonCode);
        return d == null || now <= d;
    }
}
