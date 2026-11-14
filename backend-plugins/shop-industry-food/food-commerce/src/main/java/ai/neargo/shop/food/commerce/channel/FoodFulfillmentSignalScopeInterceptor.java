package ai.neargo.shop.food.commerce.channel;

import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.spi.DiningChannelFulfillmentSignalInternalApi;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/** Only the exact original signal route receives the existing signed SYSTEM body credentials. */
public final class FoodFulfillmentSignalScopeInterceptor implements ClientHttpRequestInterceptor {
    private final byte[] key;
    private final Clock clock;

    public FoodFulfillmentSignalScopeInterceptor(String key, Clock clock) {
        this.key = FoodScopeCredentials.key(key);
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!"POST".equals(request.getMethod().name())
                || request.getURI().getRawQuery() != null
                || !DiningChannelFulfillmentSignalInternalApi.PATH.equals(
                        request.getURI().getRawPath()))
            throw new IllegalStateException("Unexpected original FOOD signal route");
        var claims =
                new FoodScopeCredentials.Claims(
                        FoodScopeCredentials.Realm.SYSTEM,
                        null,
                        List.of(),
                        null,
                        clock.instant().getEpochSecond() + FoodScopeCredentials.TTL_SECONDS,
                        FoodScopeCredentials.digest(
                                "POST", DiningChannelFulfillmentSignalInternalApi.PATH, body));
        FoodScopeCredentials.requireBodyMatches(claims, FoodScopeBodyScanner.scan(body));
        request.getHeaders()
                .set(FoodScopeCredentials.HEADER, FoodScopeCredentials.sign(claims, key));
        return execution.execute(request, body);
    }
}
