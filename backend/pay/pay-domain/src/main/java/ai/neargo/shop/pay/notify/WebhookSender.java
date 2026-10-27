package ai.neargo.shop.pay.notify;

import java.time.Duration;
import java.util.Map;

/** 把一条答复 POST 给接入方。只管发与收，不管重试与记账（那在 {@code BizNotifyService}） */
public interface WebhookSender {

    /** @return 对方的 HTTP 状态与响应体；连不上 / 超时抛异常 */
    Response post(String url, Map<String, String> headers, String body, Duration timeout) throws Exception;

    record Response(int status, String body) {
    }
}
