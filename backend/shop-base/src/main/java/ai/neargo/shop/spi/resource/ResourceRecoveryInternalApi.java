package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Token-authenticated owner reads shared by retail, food and independently deployed service industries. */
public interface ResourceRecoveryInternalApi {
    String ACCESS_PATH = "/internal/merchant/recovery/operator-access";
    String COMMAND_PATH = "/internal/merchant/recovery/commands";
    @PostExchange(ACCESS_PATH)
    Boolean operatorAccess(@RequestBody MerchantOperatorAccessPort.Query query);
    @PostExchange(COMMAND_PATH)
    ResourceCommandFactsPort.Lookup command(@RequestBody ResourceSourceAuthorityPort.Request request);
}
