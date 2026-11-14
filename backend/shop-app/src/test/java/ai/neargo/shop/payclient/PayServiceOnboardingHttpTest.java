package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceInterceptor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class PayServiceOnboardingHttpTest {
    @Test void queryUsesCanonicalSourceHeadersAndPreservesChannelFacts() throws Exception {
        var path = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var caller = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getRawQuery());
            caller.set(ex.getRequestHeaders().getFirst("X-Internal-Caller"));
            token.set(ex.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = ("{\"status\":\"APPROVED\",\"merchantRef\":\"PM1\",\"linkStatus\":\"VERIFIED\",\"profileStatus\":\"APPROVED\","
                    + "\"reviewReason\":null,\"onboardings\":[{\"onboardingNo\":\"OB1\",\"payChannel\":\"NEWEBPAY\",\"status\":\"CHANNEL_REVIEW\","
                    + "\"reviewStatus\":\"APPROVED\",\"submitOutcome\":\"UNKNOWN\",\"resultCode\":\"AMR10014\",\"handoffStatus\":\"PENDING\","
                    + "\"ezpayBindingStatus\":\"PENDING\",\"reason\":null,\"updatedAt\":100}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var env = new MockEnvironment().withProperty("shop.pay.internal-caller", "AI_SHOP_UME")
                    .withProperty("shop.pay.internal-caller-credentials.AI_SHOP_UME", "test-credential");
            var beans = new StaticListableBeanFactory(Map.of("caller", new PayClientConfig().payCallerHeaders(env)));
            var http = new InternalHttp(svc -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "test-token",
                    beans.getBeanProvider(InternalServiceInterceptor.class));
            var result = new PayClientConfig().paySourceLinkApi(http).onboardings("UME", "M1");
            assertThat(path.get()).isEqualTo("/internal/pay/v2/source-links/onboardings");
            assertThat(query.get()).contains("sourceScope=UME", "sourceEntityRef=M1");
            assertThat(caller.get()).isEqualTo("AI_SHOP_UME");
            assertThat(token.get()).isEqualTo("test-token");
            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.onboardings()).singleElement().satisfies(row -> {
                assertThat(row.status()).isEqualTo("CHANNEL_REVIEW");
                assertThat(row.resultCode()).isEqualTo("AMR10014");
                assertThat(row.handoffStatus()).isEqualTo("PENDING");
            });
        } finally {
            server.stop(0);
        }
    }
}
