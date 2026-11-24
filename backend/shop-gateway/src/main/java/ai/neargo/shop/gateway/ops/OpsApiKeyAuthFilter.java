package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.security.GatewayExchange;
import ai.neargo.shop.platform.OpsApiKeyService;
import ai.neargo.shop.platform.OpsApiKeyService.Access;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;

final class OpsApiKeyAuthFilter extends OperatorTokenAuthFilter {

    record Credential(String keyNo, Access access) {}

    private final OpsApiKeyService keys;

    OpsApiKeyAuthFilter(TokenStore sessions, ObjectProvider<LiveIdentityResolver> identities,
                        OpsApiKeyService keys) {
        super(sessions, identities);
        this.keys = keys;
    }

    @Override
    protected String clientCode(HttpServletRequest request) {
        return isApiKey(bearer(request)) ? "OPS_API_KEY" : super.clientCode(request);
    }

    @Override
    protected Authentication resolveAuthentication(HttpServletRequest request, String token) {
        if (!isApiKey(token)) {
            return super.resolveAuthentication(request, token);
        }
        try {
            return keys.authenticate(token).map(key -> {
                GatewayExchange.markLocalOnly(request);
                return authentication(currentIdentity(key.user()), new Credential(key.keyNo(), key.access()));
            }).orElse(null);
        } catch (DataAccessException e) {
            throw new AuthenticationStorageUnavailable(e);
        }
    }

    private static boolean isApiKey(String token) {
        return token != null && token.startsWith(OpsApiKeyService.PREFIX);
    }
}
