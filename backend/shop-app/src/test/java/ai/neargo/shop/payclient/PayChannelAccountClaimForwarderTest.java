package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PayChannelAccountClaimForwarderTest {
    private static final String BASE=PayChannelAccountClaimForwarder.BASE;
    @Test void exactClaimsRoutesAndFoundationPolicyAgreeWithoutExpandingLegacyRelay() {
        var routes=Map.of("GET",List.of(BASE,BASE+"/by-request/R",BASE+"/C",BASE+"/C/files",BASE+"/C/files/"+"a".repeat(32),BASE+"/C/commands/R"),
                "POST",List.of(BASE,BASE+"/C/files",BASE+"/C/submit"));
        var policy=new CommerceRoutePolicy();
        routes.forEach((method,paths)->paths.forEach(path->{
            assertTrue(PayChannelAccountClaimForwarder.allowed(method,path));
            assertFalse(BizOnboardingForwarder.allowed(method,path),"Old relay stays closed to N2");
            assertTrue(policy.match(method,path).foundation());
            assertEquals("biz:finance",policy.match(method,path).capability());
        }));
        for(String path:List.of(BASE+"/C/resolve",BASE+"/C/activate",BASE+"/C/files/not-a-file",BASE+"/C/../X",BASE+"/%43",BASE+"/C\\files",BASE+"#fragment","https://other.test"+BASE,"//other.test"+BASE))
            assertFalse(PayChannelAccountClaimForwarder.allowed("POST",path),path);
        assertFalse(PayChannelAccountClaimForwarder.allowed("PUT",BASE+"/C"));
        assertTrue(PayChannelAccountClaimForwarder.allowed("GET",BASE+"?state=PENDING_VERIFY"));
    }
    @Test void originalBytesAndPrivateHeadersRelayWithoutInternalCredentialsRedirectOrRetry() throws Exception {
        var requestHeaders=new AtomicReference<com.sun.net.httpserver.Headers>();var received=new AtomicReference<byte[]>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] bytes={0,-1,2,0,19};
        server.createContext("/",exchange->{
            requestHeaders.set(exchange.getRequestHeaders());received.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type","image/png");exchange.getResponseHeaders().set("Cache-Control","private, no-store");
            exchange.getResponseHeaders().set("Content-Disposition","inline; filename=proof.png");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Set-Cookie","never=forward");exchange.getResponseHeaders().set("Location","https://other.test");
            exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var forwarder=new PayChannelAccountClaimForwarder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            var response=forwarder.forward("POST",BASE,Map.of("Authorization","Bearer btk_original","X-Merchant-No","PM", "X-Store-No","S",
                    "X-Internal-Token","forged","X-Internal-Caller","forged","X-Subject-Type","forged","Cookie","secret"),bytes);
            assertEquals(503,response.status());assertArrayEquals(bytes,response.body());assertArrayEquals(bytes,received.get());
            assertEquals("Bearer btk_original",requestHeaders.get().getFirst("Authorization"));
            for(String name:List.of("X-Internal-Token","X-Internal-Caller","X-Subject-Type","Cookie"))assertNull(requestHeaders.get().getFirst(name));
            assertEquals(List.of("private, no-store"),response.headers().get("cache-control"));
            assertFalse(response.headers().containsKey("set-cookie"));assertFalse(response.headers().containsKey("location"));
        } finally {server.stop(0);}
    }
    @Test void featureIsOffUnlessExplicitlyEnabled() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("api");
            context.register(ai.neargo.shop.config.PayChannelAccountClaimConfiguration.class,
                    ai.neargo.shop.portal.biz.BizChannelAccountClaimController.class);
            context.refresh();
            assertTrue(context.getBeansOfType(PayChannelAccountClaimForwarder.class).isEmpty());
            assertTrue(context.getBeansOfType(ai.neargo.shop.portal.biz.BizChannelAccountClaimController.class).isEmpty());
        }
    }
    @Test void fixedOriginAndTransportLimitsFailClosed() {
        for(String origin:List.of("http://remote.test","https://user@pay.test","https://pay.test/path","https://pay.test?q=1"))
            assertThrows(IllegalArgumentException.class,()->new PayChannelAccountClaimForwarder(URI.create(origin)));
        var forwarder=new PayChannelAccountClaimForwarder(URI.create("http://127.0.0.1:1"));
        assertThrows(IllegalArgumentException.class,()->forwarder.forward("POST",BASE,Map.of(),new byte[1024*1024+1]));
        assertThrows(IllegalArgumentException.class,()->forwarder.forward("POST",BASE+"/C/files",Map.of(),new byte[6*1024*1024+1]));
    }
}
