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

    /**
     * 把社区挂到某个行政区划下（ADR-013 阶段一）。
     *
     * <p>挂了之后「按区/按街道覆盖」才能命中它。**建议挂到街道级** ——
     * 挂区县也能用，但那样「按街道覆盖」就退化成了「按区覆盖」。
     *
     * @param regionCode {@code sys_region.region_code}；传空表示清空归属
     * @throws ai.neargo.shop.common.BizException 区划码不存在 —— 挂到一个不存在的码上，
     *         症状是这个社区在任何按区的覆盖里都出不来，而界面上它明明填着值
     */
    CommunityVO setRegion(String communityNo, String regionCode, String operatorNo);

    List<PickupVO> pickups(String communityNo, String type, String status);

    /**
     * 建自提点。
     *
     * <p><b>此前全平台没有任何创建路径</b>：运营端只有列表/停启/费率，商家不能申请、
     * 邻居不能报名 —— 社区自提这条核心履约方式，生产环境根本无法录入一个点，
     * 能跑通只因为开发种子建了两个。与本轮反复撞到的「有能力没有消费方」正好相反：
     * <b>有消费方没有录入</b>。
     *
     * <p>三类的必填项完全不同，见 {@link CreatePickupCommand}。
     */
    PickupVO createPickup(CreatePickupCommand cmd, String operatorNo);

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
    /**
     * @param regionCode 所属区划码，空 = 尚未归属
     * @param regionPath 从省到自身的中文路径（如「浙江省 / 杭州市 / 西湖区 / 北山街道」）。
     *                   **后端拼好给端上**：光给一个 330106001 的话，端上要么显示一串数字，
     *                   要么自己按码长切片再逐级查 —— 那等于把国标编码规则复制到端上
     */
    record CommunityVO(String communityNo, String name, String city, String grid, boolean opened,
                       int fenceRadius, int pickupCount, long createdAt,
                       String regionCode, String regionPath) {
    }

    /**
     * @param type        STORE / NEIGHBOR / <b>PLATFORM</b> —— 三类的报酬与脱敏规则完全不同
     * @param feeMode     NONE / PER_ITEM / RATE。目前只有 PLATFORM 有值（B9 口径未定）
     * @param address     NEIGHBOR 点成团前只到楼栋，付款后才给完整门牌
     * @param storeNo     承接<b>门店</b>（V16 起 owner_ref 存 store_no，此前是 entity_no）；
     *                    只在 STORE 类型下有值 —— 这一列本来就是多态的
     */
    /**
     * @param type     STORE / NEIGHBOR / PLATFORM
     * @param ownerRef STORE 传<b>门店号</b>（V16 起）、NEIGHBOR 传用户号、PLATFORM 传空。
     *                 这一列是多态的，传错的后果是「这个点属于谁」永久错位，
     *                 而它决定核销权限与履约服务费给谁
     * @param serviceFeeRate 履约服务费费率，万分比。<b>NEIGHBOR 必须为 0</b> ——
     *                 给了报酬他就变成团长了（ADR-005 §4）
     */
    record CreatePickupCommand(String communityNo, String name, String type, String ownerRef,
                               String address, String openHours, String arrivalDesc,
                               Integer serviceFeeRate, Long serviceFeePerItemMinor) {
    }

    record PickupVO(String pickupNo, String name, String type, String status, String communityNo,
                    String communityName, String storeNo, String address, String openHours,
                    String arriveTime, int serviceFeeRate, long serviceFeePerItemMinor,
                    String feeMode, int acceptCount30d, long createdAt) {
    }
}
