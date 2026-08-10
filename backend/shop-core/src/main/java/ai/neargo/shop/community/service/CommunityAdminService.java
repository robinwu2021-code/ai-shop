package ai.neargo.shop.community.service;

import java.util.List;

/** 社区与自提点治理（P-2.1 / P-2.2）。 */
public interface CommunityAdminService {

    List<CommunityVO> communities(String keyword, boolean showClosed);

    /**
     * 开城开关（P-2.1.2）。
     *
     * <p>关掉后 C 端不再展示该社区，<b>已有订单不受影响</b> ——
     * 关城是停止获客，不是中止履约；把在途订单一起停掉，受损的是已经付过钱的买家。
     */
    CommunityVO setOpened(String communityNo, boolean opened, String operatorNo);

    /**
     * 覆盖围栏半径（米）。
     *
     * <p>必须大于 0：0 意味着这个社区覆盖不到任何地址，而界面上看起来只是「还没配」——
     * 一个数字就能让整个社区静默失效。
     */
    CommunityVO setFence(String communityNo, int fenceRadius, String operatorNo);

    List<PickupVO> pickups(String communityNo, String type, String status);

    /**
     * 自提点状态。ACTIVE ⇄ SUSPENDED，ACTIVE → MIGRATING → SUSPENDED。
     *
     * <p>{@code MIGRATING}（迁移中）= <b>不再接新单，存量单仍在本点核销完</b>。
     * 没有这个中间态的话，换点只能「直接停用」，而那些已经送到旧点的货就没人能核销了。
     */
    PickupVO setPickupStatus(String pickupNo, String status, String operatorNo);

    /**
     * 履约服务费费率（万分比）。
     *
     * <p><b>NEIGHBOR 必须为 0</b> —— 邻里自提是零报酬的（ADR-005），
     * 给了报酬承接的邻居就变成团长，那是另一套责任与税务关系。库上还有 CHECK 兜底。
     */
    PickupVO setPickupServiceFee(String pickupNo, int serviceFeeRate, String operatorNo);

    /**
     * 高频承接的邻里自提点（P-2.2.5）。
     *
     * <p>邻里自提本该是「偶尔帮邻居代收」，一个月接几十次就说明它已经职业化了 ——
     * 那是无照经营的风险，也是平台该发现并转成常驻点的信号。
     */
    List<PickupVO> riskyNeighborPickups(int minAcceptCount);

    /**
     * @param opened       开城开关。关掉后 C 端不再展示，已有订单不受影响
     * @param pickupCount  本社区的自提点数量。列表直接给，避免逐行再查一次
     */
    record CommunityVO(String communityNo, String name, String city, String grid, boolean opened,
                       int fenceRadius, int pickupCount, long createdAt) {
    }

    /**
     * @param type        STORE / NEIGHBOR / <b>PLATFORM</b> —— 三类的报酬与脱敏规则完全不同
     * @param feeMode     NONE / PER_ITEM / RATE。目前只有 PLATFORM 有值（B9 口径未定）
     * @param address     NEIGHBOR 点成团前只到楼栋，付款后才给完整门牌
     */
    record PickupVO(String pickupNo, String name, String type, String status, String communityNo,
                    String communityName, String merchantNo, String address, String openHours,
                    String arriveTime, int serviceFeeRate, long serviceFeePerItemMinor,
                    String feeMode, int acceptCount30d, long createdAt) {
    }
}
