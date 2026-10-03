package ai.neargo.shop.spi.platform;

/**
 * 平台开关的读口。
 *
 * <p><b>放在 spi 里是因为读它的人跨域</b>：商品域要判「上架拦不拦」，
 * 商家域要判「摆货架拦不拦」，而开关本身属于平台域。让那两个域直接依赖
 * 平台域的 Service，域边界就没了 —— 那正是这套 port 存在的理由。
 */
public interface PlatformSwitchPort {

    /**
     * 布尔开关。**取不到时返回 {@code def}，不抛** ——
     * 配置读失败让整个上架流程 500 是不成比例的：开关是策略，不是数据。
     */
    boolean bool(String key, boolean def);
}
