package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.wechat.WechatV3Client;
import java.util.Map;

/**
 * 微信 APIv3 的旧 {@link ChannelClient} 外形，给还没迁到能力接口的进件（{@code WechatApplymentGateway}）用。
 *
 * <p>W2 S5 起它<b>不再自己发 HTTP</b>：签名、验应答在 {@code WechatV3Protocol}，发送与主备切换在 {@link WechatV3Client}，
 * 原文落库在记录型客户端 —— 进件请求从此也在 {@code pay_raw_payload} 里有一份。这里只把传输层的分类译回旧异常：
 * 渠道可能已处理（超时、5xx / 429、连不上）→ 可重试；这个请求本身不行（4xx、验签不过、不是 JSON）→ 不可重试。
 * 异常信息里不带签名串与报文主体。
 */
public class WechatChannelClient implements ChannelClient {

    private final WechatV3Client client;

    public WechatChannelClient(WechatV3Client client) {
        this.client = client;
    }

    @Override
    public Map<String, Object> post(String api, Map<String, Object> body) throws ChannelException {
        try {
            return client.post(null, api, body == null ? Map.of() : body);
        } catch (ChannelTransportException e) {
            throw translate(e);
        }
    }

    /** GET 的待签 body 是<b>空串</b>，query 参与签名（{@code api} 含 query） */
    @Override
    public Map<String, Object> get(String api) throws ChannelException {
        try {
            return client.get(null, api);
        } catch (ChannelTransportException e) {
            throw translate(e);
        }
    }

    private static ChannelException translate(ChannelTransportException e) {
        boolean retryable = switch (e.kind()) {
            case CONNECT_FAILED, TIMEOUT, SERVER_ERROR -> true;
            case CLIENT_ERROR, BAD_SIGNATURE, UNPARSEABLE -> false;
        };
        return new ChannelException(e.getMessage(), retryable);
    }
}
