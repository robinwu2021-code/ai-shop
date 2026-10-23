package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RemoteReservationServiceTest {
    @Test void queryTravelsViaOriginalInternalClient() throws Exception {
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(ReservationInternalApi.LIST_PATH,ex -> {
            count.incrementAndGet();assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains("\"entityNo\":\"E\"","\"storeNo\":\"A\"","2026-10-21T12:00:00Z");
            byte[] response="{\"records\":[],\"total\":0,\"page\":1,\"size\":20}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type","application/json");ex.sendResponseHeaders(200,response.length);
            ex.getResponseBody().write(response);ex.close();
        });server.start();
        try {
            var http=new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class,FoodWebAutoConfiguration.class))
                    .withBean(InternalHttp.class,() -> http).withPropertyValues("shop.food.enabled=true","shop.food.deployment=standalone",
                            "spring.profiles.active=api").run(c -> assertThat(c.getBean(ReservationService.class).listReservations("E","A",
                                    new ListReservationsQuery(null,null,Instant.parse("2026-10-21T12:00:00Z"),Instant.parse("2026-10-21T14:00:00Z"),null)).total()).isZero());
            assertThat(count.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
