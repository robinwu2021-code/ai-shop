package ai.neargo.shop.logistics.capability;

/**
 * 各能力接口的公共部分。
 *
 * <p>「覆盖哪些承运商」不在这里声明 —— 由 {@code lgs_carrier_code} 决定（有没有这家渠道的那一行），
 * 运营改编码不用发版。只有 stub 这种什么都覆盖的才用 {@link #coversAllCarriers()}。
 */
public interface ChannelCapability {

    /** 渠道名：kuaidi100 / yto / wx / stub … 与配置、路由链、{@code lgs_carrier_code.channel} 同一个名字 */
    String channel();

    /**
     * 现在能不能用。凭据没配就是不能用 —— 路由会跳过它，不抛。
     * 并存场景里一家没配不该拖垮别家。
     */
    default boolean available() {
        return true;
    }

    /** 覆盖全部承运商（只有 stub） */
    default boolean coversAllCarriers() {
        return false;
    }
}
