package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.PostpayReceiptInternalApi;
import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 餐饮独立部署时，food-server 经这里确认后付实收（TDD-餐饮柜台后付结账）。只认内部令牌；金额与版本由核心自己重读核对，
 * 调用方传来的只用于比对。
 */
@RestController
public class InternalPostpayReceiptEndpoint {
    private final PostpayReceiptPort receipts;
    private final String token;

    public InternalPostpayReceiptEndpoint(PostpayReceiptPort receipts,
            @Value("${shop.services.internal-token:}") String token) {
        this.receipts = receipts;
        this.token = token;
    }

    @PostMapping(PostpayReceiptInternalApi.ROOT + "/confirm")
    public ResponseEntity<?> confirm(@RequestBody PostpayReceiptPort.Confirm command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        // 带摘要回业务码：收银台要分得清「还差钱」「多收了非现金」「账单变了」
        return InternalServiceResponses.invokeBound(token, given, () -> PostpayReceiptPort.digest(command),
                () -> receipts.confirm(command));
    }
}
