package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Only explicit original-owner routes receive SYSTEM scope; the original digest/signature algorithm
 * is reused unchanged.
 */
public final class CatalogWorkerScopeInterceptor implements ClientHttpRequestInterceptor {
    private final byte[] key;
    private final Clock clock;

    public CatalogWorkerScopeInterceptor(String configuredKey, Clock clock) {
        this.key = FoodScopeCredentials.key(configuredKey);
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String path = request.getURI().getRawPath();
        if (!"POST".equals(request.getMethod().name())
                || request.getURI().getRawQuery() != null
                || !List.of(
                                "/internal/food/commerce/store-availability/capture",
                                "/internal/food/commerce/catalog/capture",
                                "/internal/food/commerce/sources/withdrawal",
                                "/internal/food/commerce/sources/disposition",
                                "/internal/food/commerce/sources/validate",
                                "/internal/food/commerce/sources/current",
                                "/internal/food/channel-orders/prepare",
                                "/internal/food/channel-orders/get",
                                "/internal/food/channel-orders/confirm",
                                "/internal/food/channel-orders/cancel",
                                "/internal/food/channel-source/capture",
                                "/internal/food/channel-source/collection",
                                "/internal/food/channel-source/acceptance",
                                "/internal/food/channel-source/withdrawal",
                                "/internal/food/channel-source/demand",
                                "/internal/food/channel-fulfillment/capture")
                        .contains(path))
            throw new IllegalStateException("Unexpected catalog worker route");
        var claims =
                new FoodScopeCredentials.Claims(
                        FoodScopeCredentials.Realm.SYSTEM,
                        null,
                        List.of(),
                        null,
                        clock.instant().getEpochSecond() + FoodScopeCredentials.TTL_SECONDS,
                        FoodScopeCredentials.digest(request.getMethod().name(), path, body));
        FoodScopeCredentials.requireBodyMatches(claims, FoodScopeBodyScanner.scan(body));
        request.getHeaders()
                .set(FoodScopeCredentials.HEADER, FoodScopeCredentials.sign(claims, key));
        return execution.execute(request, body);
    }
}
