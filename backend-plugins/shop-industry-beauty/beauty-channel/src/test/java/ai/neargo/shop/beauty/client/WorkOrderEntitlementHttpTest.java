package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Binding;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.State;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class WorkOrderEntitlementHttpTest {
    @Test
    void reservationBindsCurrentHumanAndMachineRecoveryUsesOnlyTheOriginalCoordinates() throws Exception {
        var json = JsonMapper.builder().build();
        var proof = new InternalBindings("entitlement-wire");
        var lookup = new Lookup("MAIN", "E", "S", "OP");
        var origin = new Origin("WO", "TASK", 1, 0, "MEMBER", "SKU", 2);
        var wrong = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/beauty/entitlement-uses/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("entitlement-wire");
            byte[] request = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            var replyLookup = wrong.get() ? new Lookup("MAIN", "E", "S", "OTHER") : lookup;
            byte[] response;
            if (ServiceWorkOrderUseInternalApi.RESERVE.equals(path)) {
                var call = json.readValue(request, new TypeReference<InternalCall<Lookup>>() {});
                assertThat(call.binding().endpoint()).isEqualTo(ServiceWorkOrderUseInternalApi.ENDPOINT);
                assertThat(call.binding().identity().actorNo()).isEqualTo("ACTOR");
                assertThat(call.binding().targetNo()).isEqualTo("OP");
                assertThat(call.request()).isEqualTo(lookup);
                response = json.writeValueAsBytes(proof.reply(call,
                        new Binding(replyLookup, "CLAIM", origin, "a".repeat(64), State.RESERVED, null, null)));
            } else {
                assertThat(json.readValue(request, Lookup.class)).isEqualTo(lookup);
                response = json.writeValueAsBytes(new Binding(replyLookup, "CLAIM", origin, "a".repeat(64),
                        State.BOUND, "BIND", "USAGE"));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "entitlement-wire");
            var client = new RemoteWorkOrderEntitlementUses(http.client("BEAUTY",
                    ServiceWorkOrderUseInternalApi.class, Duration.ofSeconds(2)), proof);
            DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"), () -> {
                var context = new EntitlementContext("MAIN", "ACTOR", "request", "0".repeat(64));
                assertThat(client.reserve(context, lookup).state()).isEqualTo(State.RESERVED);
                wrong.set(true);
                assertThatThrownBy(() -> client.reserve(context, lookup)).isInstanceOf(RuntimeException.class);
                wrong.set(false);
                return null;
            });
            assertThat(client.reconcile(lookup).usageNo()).isEqualTo("USAGE");
            assertThat(client.inspect(lookup).usageNo()).isEqualTo("USAGE");
            wrong.set(true);
            assertThatThrownBy(() -> client.inspect(lookup)).isInstanceOf(RuntimeException.class);
        } finally {
            server.stop(0);
        }
    }
}
