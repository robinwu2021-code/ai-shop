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
 * Only explicitly allowed private RETAIL owner operations receive an original SYSTEM body signature
 * with independent retail keys.
 */
public final class RetailOrderScopeInterceptor implements ClientHttpRequestInterceptor {
    private final byte[] key;
    private final Clock clock;

    public RetailOrderScopeInterceptor(String configuredKey, Clock clock) {
        key = FoodScopeCredentials.key(configuredKey);
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
                                "/internal/retail/commerce/store-availability/capture",
                                "/internal/retail/channel-orders/prepare",
                                "/internal/retail/channel-orders/get",
                                "/internal/retail/channel-orders/confirm",
                                "/internal/retail/channel-orders/cancel",
                                "/internal/retail/channel-source/capture",
                                "/internal/retail/channel-source/collection",
                                "/internal/retail/channel-source/acceptance",
                                "/internal/retail/channel-source/withdrawal",
                                "/internal/retail/channel-source/demand")
                        .contains(path))
            throw new IllegalStateException("Unexpected original retail owner route");
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
