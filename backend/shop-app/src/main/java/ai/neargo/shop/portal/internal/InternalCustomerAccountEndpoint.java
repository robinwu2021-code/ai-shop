package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort.Query;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The original internal service token is checked before account existence is read; no contact data
 * is returned.
 */
@RestController
public final class InternalCustomerAccountEndpoint {
    private final CustomerAccountQueryPort accounts;
    private final String token;

    public InternalCustomerAccountEndpoint(
            CustomerAccountQueryPort accounts,
            @Value("${shop.services.internal-token:}") String token) {
        this.accounts = accounts;
        this.token = token;
    }

    @PostMapping("/internal/customers/account-existence")
    public ResponseEntity<?> find(
            @RequestBody Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> accounts.find(query));
    }
}
