package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real TCP verifies wire/credentials/failure semantics; returned owner facts are controlled fixtures. */
class MerchantFactsTransportTest {
    @Test
    void threeRemoteFactsUsePlatformCredentialsAndNeverHideFailuresAsEmptyAvailability() throws Exception {
        Class<?> api = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.spi.resource.MerchantFactsInternalApi"));
        var status = new AtomicInteger(200);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/merchant/facts/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("facts-key");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("entityNo", "E", "storeNo", "S").doesNotContain("actorNo");
            calls.incrementAndGet();
            String body = exchange.getRequestURI().getPath().endsWith("resources")
                    ? "[{\"resourceNo\":\"R\",\"resourceType\":\"ROOM\",\"storeNo\":\"S\","
                        + "\"name\":\"room\",\"capacity\":2,\"openOccupied\":false}]" : "[]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "facts-key");
            Object client = http.client("PLATFORM", api, Duration.ofSeconds(2));
            var people = (PersonnelFactsPort) remote("RemotePersonnelFactsPort", api, client);
            var resources = (ResourceFactsPort) remote("RemoteResourceFactsPort", api, client);
            var schedules = (ScheduleWindowsPort) remote("RemoteScheduleWindowsPort", api, client);
            Instant at = Instant.parse("2026-10-22T00:00:00Z");
            var pq = new PersonnelFactsPort.Query("MAIN", "E", "S", Set.of("P"), at);
            var rq = new ResourceFactsPort.Query("MAIN", "E", "S", Set.of("R"), at);
            var sq = new ScheduleWindowsPort.Query("MAIN", "E", "S", Set.of("R"), at, at.plusSeconds(3600));
            assertThat(people.getBatch(pq)).isEqualTo(List.of());
            assertThat(resources.getBatch(rq).getFirst().capacity()).isEqualTo(2);
            assertThat(schedules.getBatch(sq)).isEqualTo(List.of());
            status.set(503);
            assertThatThrownBy(() -> people.getBatch(pq)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> resources.getBatch(rq)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> schedules.getBatch(sq)).isInstanceOf(RuntimeException.class);
            status.set(409);
            assertThatThrownBy(() -> schedules.getBatch(sq)).isInstanceOf(BizException.class);
            assertThat(calls.get()).isGreaterThanOrEqualTo(7);
        } finally { server.stop(0); }
    }
    private Object remote(String name, Class<?> api, Object client) throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.port." + name));
        return type.getConstructor(api).newInstance(client);
    }
}
