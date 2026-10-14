package ai.neargo.shop.spi.fulfillment;

import java.util.Optional;

/**
 * marketing → fulfillment：邻里自提点（团粒度临时点，ADR-005 / C-GB-06）。
 *
 * <p>团的生命周期归 marketing 管，但「货送到谁家、签收了没有」是履约的事。
 * 这个 Port 是两者的接缝。
 *
 * <p><b>接口里没有任何费用参数，这是刻意的</b>：邻里自提零报酬 ——
 * 一旦承接的邻居能收钱，他就是团长，ADR-004 消掉的合规问题会原样回来。
 * 把「不能收钱」做进签名，比写在文档里可靠。
 */
public interface GroupPickupPort {

    /**
     * 开团勾「送到我家」时建点。<b>承接人只能是发起人本人</b> ——
     * 允许指定他人即等于开放团长招募。
     *
     * @return 新建的自提点单号
     */
    String createForGroup(String groupNo, String ownerUserNo, String name,
                          String address, String timeSlot);

    Optional<GroupPickup> findByGroup(String groupNo);

    /**
     * 批次签收：发起人确认「这一车货我收到了」。
     *
     * <p>签收必须在逐单核销**之前** —— 货还没到发起人手里就核销，
     * 等于替商家确认了一件没发生的事，出了少件就说不清是谁的责任。
     *
     * @return false 表示该团没有邻里自提点，或签收人不是发起人
     */
    boolean receive(String groupNo, String operatorUserNo);

    /**
     * @param receivedAt 批次签收时间；null 表示货还没到，此时不允许核销
     */
    record GroupPickup(String pickupNo, String groupNo, String ownerUserNo, String name,
                       String address, String timeSlot, Long receivedAt) {
    }
}
