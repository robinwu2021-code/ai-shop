package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.food.foodclient.FoodScopeSigningInterceptor;
import ai.neargo.shop.food.foodclient.RemoteFoodStockReplenishmentPort;
import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.ConnectionChange;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.LocalStockControlPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import java.math.BigDecimal;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

class FoodStockReplenishmentSignedHttpTest {
    @Test void actualNewDtoIsSignedOverItsWholeBodyAndVerifiedBeforeRemoteReceipt()throws Exception {
        byte[] key="replenishment-test-scope-key-32bytes".getBytes(StandardCharsets.UTF_8);
        var failure=new AtomicReference<Throwable>();var wire=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            byte[] body=e.getRequestBody().readAllBytes();wire.set(new String(body,StandardCharsets.UTF_8));
            try{
                var claims=FoodScopeCredentials.verify(e.getRequestHeaders().getFirst(FoodScopeCredentials.HEADER),List.of(key),
                        FoodScopeCredentials.digest(e.getRequestMethod(),e.getRequestURI().getPath(),body),System.currentTimeMillis()/1000);
                FoodScopeCredentials.requireBodyMatches(claims,FoodScopeBodyScanner.scan(body));
                byte[] response;
                if(e.getRequestURI().getPath().endsWith("/source")||e.getRequestURI().getPath().endsWith("/register/recover"))response="{\"result\":null}".getBytes(StandardCharsets.UTF_8);
                else if(e.getRequestURI().getPath().endsWith("/register/claimed"))response=JsonMapper.builder().build().writeValueAsBytes(
                        new FoodStockReplenishmentPort.View("FRP_SIGNED","INV","OWNER","OUT",1,"I","G",BigDecimal.TEN,
                                "RAW","G",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"PENDING"));
                else response="{\"enabled\":true,\"generation\":1,\"revision\":1,\"sourceSystem\":\"INV\",\"sourceOwner\":\"OWNER\",\"postingWatermark\":20}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);
            }catch(Throwable ex){failure.set(ex);e.sendResponseHeaders(403,-1);}finally{e.close();}
        });server.start();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(LoginUser.merchant("ACT","B"),"",List.of()));
        BizContext.set(new BizContext("E",Set.of(),Set.of(),Set.of("S"),"S",false,Map.of(),Map.of("S",Set.of(BizPerms.STOCK))));
        try{
            var client=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort())
                    .requestInterceptor(new FoodScopeSigningInterceptor(key,()->System.currentTimeMillis()/1000)).build();
            var api=HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(FoodStockReplenishmentInternalApi.class);
            var port=new RemoteFoodStockReplenishmentPort(api);
            var result=port.configure(new Scope("E","S"),"ACT","ORIGINAL",new ConnectionChange(true,0,"start"),new Checkpoint("INV","OWNER",20));
            assertThat(result.enabled()).isTrue();assertThat(failure.get()).isNull();
            assertThat(wire.get()).contains("ORIGINAL","actorNo","ACT","postingSequence");
            assertThat(port.findSource(new Scope("E","S"),"INV","OWNER","OUT",1)).isNull();
            assertThat(wire.get()).contains("system","OWNER","OUT");
            var fact=new Outbound("INV","OWNER","OUT",1,"I","G",BigDecimal.TEN,"S",21);
            var mapping=new FoodStockReplenishmentPort.Mapping("RAW","G",BigDecimal.ONE);
            var proof=new StockReplenishmentClaim("SRP","FOOD_LOCAL",StockReplenishmentClaim.binding(
                    new LocalStockControlPort.Scope("MAIN","E","S"),"FOOD_LOCAL",fact,"RAW","G",BigDecimal.ONE));
            assertThat(port.registerClaimed(new Scope("E","S"),"ACT","NEW",fact,mapping,proof).replenishmentNo()).isEqualTo("FRP_SIGNED");
            assertThat(wire.get()).contains("bindingHash","SRP","FOOD_LOCAL");assertThat(failure.get()).isNull();
            assertThat(port.recoverManualRegistration(new Scope("E","S"),"ACT","MANUAL","OUT",1,mapping,"MOV")).isNull();
            assertThat(wire.get()).contains("movementNo","MOV","MANUAL");assertThat(failure.get()).isNull();
        }finally{server.stop(0);BizContext.clear();SecurityContextHolder.clearContext();}
    }
}
