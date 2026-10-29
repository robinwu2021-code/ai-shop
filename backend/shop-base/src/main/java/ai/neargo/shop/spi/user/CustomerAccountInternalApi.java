package ai.neargo.shop.spi.user;

import ai.neargo.shop.spi.user.CustomerAccountQueryPort.Query;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort.Result;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Private account lookup preserves original merchant/store scope and returns no personal
 * information.
 */
public interface CustomerAccountInternalApi {
    @PostExchange("/internal/customers/account-existence")
    Result find(@RequestBody Query query);
}
