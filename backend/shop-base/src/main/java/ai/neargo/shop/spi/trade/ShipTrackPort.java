package ai.neargo.shop.spi.trade;

import java.util.Optional;

/**
 * 看件令牌的签发/验签（TDD-收件人物流触达与分享裂变 §4）。
 *
 * <p><b>为什么是 spi 端口</b>：令牌由 trade 域的 {@code ShipTrackToken} 实现（它懂子单号这个 trade 概念），
 * 而签发它的是 message 域的发货通知编排（{@code ShipRecipientNotify}）。
 * message 直接注入 trade 的类会把两个域焊在一起（ArchitectureTest 的域间规则拦的就是这个），
 * 所以跨这条缝走端口。trade 自己用时直接用实现即可（同域）。
 */
public interface ShipTrackPort {

    /** 签发一张看指定子单的票据（含过期），放进发货短信的短链里 */
    String sign(String subOrderNo);

    /** 验票，返回子单号；伪造/过期/格式不对时为空 */
    Optional<String> verify(String token);
}
