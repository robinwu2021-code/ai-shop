package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.scope.FoodScopeCredentials;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class RetailOrderScopeInterceptorTest {
    final Instant now = Instant.parse("2026-10-29T04:00:00Z");
    final String key = "retail-original-owner-key-12345678901234567890";
    final RetailOrderScopeInterceptor interceptor =
            new RetailOrderScopeInterceptor(key, Clock.fixed(now, ZoneOffset.UTC));
    final byte[] body =
            "{\"reference\":{\"entityNo\":\"E\",\"storeNo\":\"S\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    HttpRequest request(String path) {
        var request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://original-platform" + path));
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        return request;
    }

    @Test
    void onlyRetailOriginalPathsReceiveTheExactBodySignature() throws Exception {
        var request = request("/internal/retail/channel-orders/confirm");
        interceptor.intercept(request, body, mock(ClientHttpRequestExecution.class));
        var claims =
                FoodScopeCredentials.verify(
                        request.getHeaders().getFirst(FoodScopeCredentials.HEADER),
                        List.of(FoodScopeCredentials.key(key)),
                        FoodScopeCredentials.digest("POST", request.getURI().getRawPath(), body),
                        now.getEpochSecond());
        assertThat(claims.realm()).isEqualTo(FoodScopeCredentials.Realm.SYSTEM);
    }

    @Test
    void foodOtherPathsAndQueriesNeverReceiveRetailOwnerScopeOrExecute() {
        for (var path :
                List.of(
                        "/internal/food/channel-orders/get",
                        "/internal/retail/channel-orders/get?storeNo=OTHER",
                        "/internal/retail/catalog/capture")) {
            var execution = mock(ClientHttpRequestExecution.class);
            assertThatThrownBy(() -> interceptor.intercept(request(path), body, execution))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(execution);
        }
    }
}
