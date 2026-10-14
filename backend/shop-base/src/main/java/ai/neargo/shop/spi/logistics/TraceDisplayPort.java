package ai.neargo.shop.spi.logistics;

import java.util.Optional;

/**
 * 展示渠道的**单一入口**（trade/core 只认这个，不直连 channel —— ArchUnit 守跨域只走 Port）。
 *
 * <p>实现是路由：按端的优先级链逐个问 {@link TraceDisplay#supports}，
 * 第一个能备出载荷的胜出。调用方不知道、也不需要知道背后是微信插件还是自建。
 */
public interface TraceDisplayPort {

    /**
     * 给这一单挑一个展示渠道并备好载荷。全链都备不出时返回 empty（端上整块不显示）。
     *
     * @param surface 在哪个端上呈现
     * @param ctx     这一单的全部上下文
     */
    Optional<TraceDisplay.DisplayPayload> decide(TraceDisplay.Surface surface, TraceDisplay.ShipmentCtx ctx);

    /** 最近一次 {@link #decide} 失败的原因，给运营排查用（落 display_fail_reason）。没有失败时为 null */
    default String lastFailReason() {
        return null;
    }
}
