package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade/fulfillment → user：买家的**展示信息**。
 *
 * <p>只给昵称与手机号后四位。不给完整手机号是刻意的：调用方（核销台、分拣单）
 * 需要的是「认出这个人」，而不是联系他 —— 需要联系时走平台的客服通道，
 * 而不是把号码散到每个自提点的手机上（M11/B12）。
 */
public interface UserQueryPort {

    Optional<UserBrief> find(String userNo);

    /**
     * @param phoneTail 手机号后四位。**完整号码永远不出这个 Port**（B12）——
     *                  商家侧的顾客列表、履约台都只需要「认得出是谁」，不需要能打过去
     * @param avatar    头像 URL。顾客列表要用；它不是敏感信息，但也只在这里出现一次
     */
    record UserBrief(String userNo, String nickname, String phoneTail, String avatar) {
    }

    /**
     * 下单时取一次收货地址，用于**在子订单上做快照**（V69）。
     *
     * <p><b>这是 {@link UserBrief} 那条规则的例外，且只在这一处</b>：
     * 它返回完整手机号。理由是快照 —— 存的是「下单那一刻这张单要送给谁」，
     * 而不是「这个用户现在的联系方式」。两者会分叉，分叉时该以前者为准。
     *
     * <p>下发给商家时仍然要脱敏，脱敏口径在 {@code MerchantOrderServiceImpl}：
     * 自送单给完整（送不到就得打电话），其余给后四位。
     * <b>Port 的职责是取数，不是决定谁能看到多少</b> —— 两件事混在一起时，
     * 「换个调用方就漏了」这种错会变得很难看出来。
     *
     * @return 地址不存在或不属于这个人时为空
     */
    Optional<Receiver> receiverOf(String userNo, String addressId);

    /**
     * @param phone   <b>完整手机号</b>，仅用于写进订单快照
     * @param address 省市区 + 详细，拼好的一行
     */
    /** @param latE6 收货点坐标（gcj02，E6）。手填的地址没有坐标，为 null —— 自送范围判定据此放行 */
    record Receiver(String name, String phone, String address, Integer latE6, Integer lngE6) {

        /** 不带坐标的老形状 */
        public Receiver(String name, String phone, String address) {
            this(name, phone, address, null, null);
        }
    }

    /**
     * 用户绑定的社区。
     *
     * <p>下单时把它固化到主单上 —— <b>运营按社区做数据域隔离</b>，
     * 而订单上没有社区的话，平台端按社区筛出来永远是空的。
     *
     * <p>固化而不是每次现查用户当前绑定：用户搬家换社区之后，
     * 历史订单仍然属于当时那个社区，否则昨天的单会跳到新社区的报表里。
     *
     * @return 没绑过社区时为空
     */
    java.util.Optional<String> communityOf(String userNo);

    /**
     * 收货地址的坐标健康度。**只给聚合数，不给明细** ——
     * 地址是个人信息，运营看总数就够判断「分母有多脏」，没有理由逐条看。
     *
     * <p>没坐标的地址推不出任何聚落：它们既不算进任何一个片区，
     * 也不该被静默丢掉。位置分布那张表（O11）必须把它们单列一格，
     * 否则会把「缺数据」说成「缺需求」。
     */
    AddressCoordHealth addressCoordHealth();

    record AddressCoordHealth(int total, int withCoords) {
    }
}
