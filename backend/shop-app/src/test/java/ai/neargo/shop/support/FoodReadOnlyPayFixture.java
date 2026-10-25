package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs only the real PAY negative-capability implementations over loopback HTTP. This is not a full
 * PAY application/database, and exposes no money mutation endpoint.
 */
public final class FoodReadOnlyPayFixture implements AutoCloseable {
    private final HttpServer server;
    private final ConfigServiceLocator locator;
    private final Map<String, String> previous;
    private final CopyOnWriteArrayList<String> unexpected = new CopyOnWriteArrayList<>();

    public FoodReadOnlyPayFixture(ConfigServiceLocator locator, ObjectMapper json, String token)
            throws Exception {
        this.locator = locator;
        previous = new LinkedHashMap<>(locator.getTargets());
        var settings = new PayEntitlementProperties();
        var coupons = new EntitlementCapabilityPortImpl(settings);
        var stored = new StoredValueCapabilityPortImpl(settings);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        String path = exchange.getRequestURI().getPath();
                        if (!token.equals(
                                exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))) {
                            unexpected.add("unauthorized");
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        if (!"POST".equals(exchange.getRequestMethod())
                                || !Set.of(
                                                PayInternalPaths.ENT_PRICING_CAPABILITIES,
                                                PayInternalPaths.SV_PRICING_CAPABILITIES)
                                        .contains(path)) {
                            unexpected.add(path);
                            exchange.sendResponseHeaders(503, -1);
                            return;
                        }
                        var input =
                                json.readValue(
                                        exchange.getRequestBody().readAllBytes(),
                                        PayCapabilityRequests.Pricing.class);
                        Object output =
                                path.equals(PayInternalPaths.ENT_PRICING_CAPABILITIES)
                                        ? coupons.pricing(input.scope())
                                        : stored.pricing(input.scope());
                        byte[] bytes = json.writeValueAsBytes(output);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        locator.getTargets().put("PAY", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        locator.getTargets().clear();
        locator.getTargets().putAll(previous);
        assertThat(unexpected)
                .as("Only the two negative-capability read endpoints are permitted")
                .isEmpty();
    }
}
