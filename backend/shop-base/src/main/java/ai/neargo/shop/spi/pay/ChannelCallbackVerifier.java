package ai.neargo.shop.spi.pay;

import java.util.Map;

/**
 * 回调验签。**一个通道一个实现**，与网关同一条路数（{@code payChannel()} 认领）。
 *
 * <p><b>为什么单独抽一层、而不是塞进 PayGateway</b>：验签是**入站**的事，
 * 网关是**出站**的事。两者用的密钥不同（出站用我方私钥签，入站用通道公钥验），
 * 而把「验别人的签名」和「用自己的私钥签名」放在同一个类里，
 * 最容易出的错是拿错那一把 —— 而拿错的表现是<b>验签恒过</b>。
 *
 * <p><b>验签失败一律当作「这条回调不存在」</b>：不回执任何原因、不落任何库。
 * 回调端点是公网可达的，把失败原因回给对方等于免费给攻击者一个调试器。
 */
public interface ChannelCallbackVerifier {

    /** 这个实现对应哪个通道，与 {@code sys_pay_channel.pay_channel} 同值。 */
    String payChannel();

    /**
     * 验签并解出业务报文。
     *
     * @param headers 原始请求头（微信 V3 的签名信息全在头里：
     *                {@code Wechatpay-Signature/Timestamp/Nonce/Serial}）
     * @param rawBody <b>原始报文字节的字符串</b>。不能是「解析成对象再序列化回来」的结果 ——
     *                字段顺序或空白差一个字符签名就对不上，而那种失败看起来像「通道验签有问题」
     * @return 验签通过返回解出的业务字段；<b>不通过返回 null</b>
     */
    Map<String, Object> verify(Map<String, String> headers, String rawBody);

    /**
     * 回给通道的「收到了」。**各家形状不同**：微信要 JSON
     * {@code {"code":"SUCCESS"}}，支付宝要纯文本 {@code success}。
     *
     * <p>回错了的后果不是报错，是<b>通道认为我方没收到，于是一直重推</b> ——
     * 而我方每次都处理成功，日志里看不出任何异常。
     */
    String ackOk();

    /** 回给通道的「没处理成」。通道会按自己的节奏重推 —— 这正是我们要的。 */
    String ackFail();
}
