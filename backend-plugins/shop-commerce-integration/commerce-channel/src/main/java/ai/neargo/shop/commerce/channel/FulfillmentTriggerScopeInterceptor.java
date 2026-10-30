package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerInternalApi;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * One original trigger route receives the existing SYSTEM body digest; no credentials are forwarded
 * to another endpoint.
 */
public final class FulfillmentTriggerScopeInterceptor implements ClientHttpRequestInterceptor {
    private final byte[] key;
    private final Clock clock;

    public FulfillmentTriggerScopeInterceptor(String key, Clock clock) {
        this.key = FoodScopeCredentials.key(key);
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!"POST".equals(request.getMethod().name())
                || request.getURI().getRawQuery() != null
                || !ChannelFulfillmentTriggerInternalApi.PATH.equals(request.getURI().getRawPath()))
            throw new IllegalStateException("Unexpected original fulfillment trigger route");
        var claims =
                new FoodScopeCredentials.Claims(
                        FoodScopeCredentials.Realm.SYSTEM,
                        null,
                        List.of(),
                        null,
                        clock.instant().getEpochSecond() + FoodScopeCredentials.TTL_SECONDS,
                        FoodScopeCredentials.digest(
                                "POST", ChannelFulfillmentTriggerInternalApi.PATH, body));
        FoodScopeCredentials.requireBodyMatches(claims, FoodScopeBodyScanner.scan(body));
        request.getHeaders()
                .set(FoodScopeCredentials.HEADER, FoodScopeCredentials.sign(claims, key));
        return execution.execute(request, body);
    }
}
