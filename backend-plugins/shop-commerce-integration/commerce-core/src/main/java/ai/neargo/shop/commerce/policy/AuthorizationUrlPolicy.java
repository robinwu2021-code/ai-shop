package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只允许已知公开OAuth参数及登记的HTTPS来源/回调；新平台参数须经过合同扩展，不默认放行。 */
public final class AuthorizationUrlPolicy {
    private AuthorizationUrlPolicy() {}

    public static void validate(String url, String state, ChannelAuthorizationGateway gateway) {
        var query = validatedQuery(url, gateway);
        if (!state.equals(query.get("state")))
            throw new IllegalArgumentException("Unbound authorization URL");
    }

    /**
     * Recovery receives no plaintext state from local storage; the original URL must prove the
     * saved state digest.
     */
    public static void validateStateHash(
            String url, String stateHash, ChannelAuthorizationGateway gateway) {
        var query = validatedQuery(url, gateway);
        var state = query.get("state");
        if (state == null
                || !state.matches("[A-Za-z0-9_-]{43}")
                || stateHash == null
                || !stateHash.equals(ChannelIdentity.hash(state)))
            throw new IllegalArgumentException("Unbound recovered authorization URL");
    }

    private static Map<String, String> validatedQuery(
            String url, ChannelAuthorizationGateway gateway) {
        if (url == null || url.length() > 8192)
            throw new IllegalArgumentException("Invalid authorization URL");
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || !gateway.authorizationOrigins().contains("https://" + uri.getRawAuthority())) {
            throw new IllegalArgumentException("Unapproved authorization origin");
        }
        Map<String, String> query = new HashMap<>();
        if (uri.getRawQuery() == null)
            throw new IllegalArgumentException("Missing authorization state");
        for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            String name =
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value =
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (query.putIfAbsent(name, value) != null
                    || !Set.of(
                                    "state",
                                    "redirect_uri",
                                    "client_id",
                                    "response_type",
                                    "scope",
                                    "code_challenge",
                                    "code_challenge_method",
                                    "access_type",
                                    "prompt",
                                    "include_granted_scopes",
                                    "login_hint",
                                    "locale",
                                    "ui_locales",
                                    "app_id",
                                    "app_key",
                                    "client_key")
                            .contains(name)) {
                throw new IllegalArgumentException("Unsafe authorization query");
            }
        }
        if (!gateway.callbackUris().contains(query.get("redirect_uri"))) {
            throw new IllegalArgumentException("Unbound authorization URL");
        }
        return query;
    }
}
