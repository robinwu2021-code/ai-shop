package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import ai.neargo.shop.trade.service.PostpayReceiptService;
import org.springframework.stereotype.Component;

/** 嵌入部署：餐饮直接调核心。独立部署时 food-server 用远程实现经内部接口调宿主。 */
@Component
public class PostpayReceiptPortImpl implements PostpayReceiptPort {
    private final PostpayReceiptService service;

    public PostpayReceiptPortImpl(PostpayReceiptService service) {
        this.service = service;
    }

    @Override
    public Receipt confirm(Confirm command) {
        return service.confirm(command);
    }
}
