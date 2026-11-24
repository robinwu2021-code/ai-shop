package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** 餐饮独立部署（food-server）调宿主核心确认后付实收；内部令牌鉴权，见 {@link PostpayReceiptPort}。 */
public interface PostpayReceiptInternalApi {
    String ROOT = "/internal/trade/postpay-receipts";

    @PostExchange(ROOT + "/confirm")
    PostpayReceiptPort.Receipt confirm(@RequestBody PostpayReceiptPort.Confirm command);
}
