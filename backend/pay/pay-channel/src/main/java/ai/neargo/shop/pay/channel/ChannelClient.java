package ai.neargo.shop.pay.channel;

import java.util.Map;

/**
 * 通道 HTTP 调用与**签名**。
 *
 * <p><b>为什么单独抽一层</b>：签名要用商户私钥。把它和业务逻辑放在一起的话，
 * 任何一次「打个日志看看参数」都可能把私钥或签名串写进日志 ——
 * 而日志会被采集、会被转发、会被留存。这一层的实现类是唯一碰密钥的地方，
 * 它<b>不接受也不返回任何密钥</b>，只从注入的配置读。
 *
 * <p>两家的签名机制不同（微信 APIv3 用商户证书 RSA-SHA256 + Wechatpay-Serial 头，
 * 支付宝用 RSA2 对参数排序后签名），所以是两个实现，不共用。
 */
public interface ChannelClient {

    /**
     * 发起一次已签名的调用。
     *
     * @param api  接口坐标：微信是路径（{@code /v3/...}），支付宝是接口名（{@code alipay.xxx}）
     * @param body 业务参数。<b>不要放密钥</b> —— 实现类自己从配置取
     * @return 通道返回的原始字段。调用方自己取 {@code result} / {@code code} 判成败
     * @throws ChannelException 网络失败、签名失败、验签失败
     */
    Map<String, Object> post(String api, Map<String, Object> body) throws ChannelException;

    /**
     * 发起一次已签名的 <b>GET</b> 调用。
     *
     * <p><b>为什么必须与 {@link #post} 分开</b>：微信 APIv3 的待签串第一行是
     * HTTP 方法，且 GET 的待签 body 是<b>空串</b>。用 POST 去调查单接口，
     * 签名与方法都是错的，通道返回 405/签名错 —— 而那种失败看起来像凭据配错，
     * 会让人去查一个没问题的地方。
     *
     * @param api 完整路径，<b>含 query</b>（query 参与签名）
     */
    default Map<String, Object> get(String api) throws ChannelException {
        throw new ChannelException("该通道的 ChannelClient 未实现 GET：" + api, false);
    }

    /** 通道调用失败。{@code retryable} 决定调用方是重试还是转人工。 */
    class ChannelException extends RuntimeException {

        private final boolean retryable;

        public ChannelException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        /**
         * 网络超时、限流 → true；参数错、余额不足、未授权 → false。
         *
         * <p>不区分的话，不可重试的失败会一直占着重试队列，
         * <b>而真正该人工介入的单没人看</b>。
         */
        public boolean isRetryable() {
            return retryable;
        }
    }
}
