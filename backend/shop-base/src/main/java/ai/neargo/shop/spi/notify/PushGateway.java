package ai.neargo.shop.spi.notify;

/**
 * 单一供应商的推送 gateway（设计：多渠道推送与运营端触达配置 · 需求 2）。
 *
 * <p><b>这是通道层的内部契约，不是领域 SPI</b>。领域仍只经 {@link PushPort} 语义
 * （{@code push(clientId,…)}）说「发什么」；{@code PushRouter} 按 {@link #provider()}
 * 把一条推送分发到对应实现。加一家供应商 = 加一个 {@code PushGateway} 实现 + 一个条件开关，
 * 不碰领域、不碰路由主逻辑。
 *
 * <p>继承 {@link PushPort} 只为复用 {@code push(...)} 与 {@code PushException} 语义 ——
 * {@code PushPort} 契约本身一个字不改。
 */
public interface PushGateway extends PushPort {

    /** {@link PushProvider} 之一。路由按它选 gateway。 */
    String provider();

    /**
     * 是否为桩（不真发，能顶任何供应商）。
     *
     * <p>桩模式下只有一个桩 gateway 在场，路由把所有 provider 的推送都交给它 ——
     * 本地/测试默认桩，不会因为设备 provider 是 FCM 就发不出（也发不出，只是记下来）。
     */
    default boolean stub() {
        return false;
    }
}
