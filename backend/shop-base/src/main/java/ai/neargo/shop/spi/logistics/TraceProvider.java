package ai.neargo.shop.spi.logistics;

import java.util.Optional;

/**
 * 一个物流轨迹 provider（TDD-圆通物流直连 §4.1）。**可并存**：多个一起注册，由
 * {@link LogisticsTracePort} 的实现（路由）按承运商挑。
 *
 * <p>这与寄件（{@code ExpressPickupPort}）的「@ConditionalOnProperty 二选一」不同 ——
 * 那是同一时刻只活一个，这里是全部活着、按承运商路由。要并存就得这样。
 */
public interface TraceProvider {

    /** 唯一名字：{@code yto} / {@code kuaidi100} / {@code stub}。路由表按它指 */
    String name();

    /** 这个承运商我查不查得了。圆通直连只认 YTO；聚合器/桩认全部 */
    boolean covers(String carrier);

    /** 配齐凭据、能用。缺凭据时 false（**不抛**，让路由回落到别的或空）—— 并存里一个没配不该拖垮全局 */
    boolean available();

    /** 查一个运单的轨迹。查不到/不可用返回 empty（调用方据此显示「暂无轨迹」，不白屏） */
    Optional<TraceResult> trace(String carrier, String waybillNo);

    /**
     * 带收件人手机号查（TDD-快递100轨迹查询 AC2）：顺丰、中通等承运商的查询要校验手机号。
     * 不需要手机号的 provider 不用覆写 —— 默认退回不带手机号的那个。
     */
    default Optional<TraceResult> trace(String carrier, String waybillNo, String phone) {
        return trace(carrier, waybillNo);
    }
}
