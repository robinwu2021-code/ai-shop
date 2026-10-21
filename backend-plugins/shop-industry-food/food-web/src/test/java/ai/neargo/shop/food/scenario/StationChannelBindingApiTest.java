package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.service.StationService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Actual MVC/auth/serialization path verifies all management routes before their scoped service boundary. */
class StationChannelBindingApiTest {
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class,DishApiTest.Security.class,Host.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    private static final String STATIONS="/biz/x/food/stores/A/stations";
    private static final String BINDINGS="/biz/x/food/stores/A/menu-channel-bindings";
    @Test void allStationAndChannelRoutesPreserveTrustedStoreActorAndResponseEnvelope() {
        runner.run(c -> {
            var mvc=mvc(c); var auth=token(c,"staff");
            mvc.perform(post(STATIONS).header("Authorization",auth).header("Idempotency-Key","create")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kitchen\",\"stationType\":\"HOT\"}"))
                    .andExpect(jsonPath("$.data.configNo").value("STA"));
            mvc.perform(put(STATIONS+"/STA").header("Authorization",auth).header("Idempotency-Key","update")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"name\":\"Kitchen\",\"stationType\":\"HOT\",\"enabled\":true}"))
                    .andExpect(jsonPath("$.data.version").value(1));
            mvc.perform(get(STATIONS+"/STA").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.config.configNo").value("STA"));
            mvc.perform(get(STATIONS).header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(put("/biz/x/food/stores/A/sku-routes/SKU").header("Authorization",auth)
                    .header("Idempotency-Key","route").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"routes\":[{\"stationNo\":\"STA\",\"taskRole\":\"MAKE\"}]}"))
                    .andExpect(jsonPath("$.data.version").value(1));
            mvc.perform(get("/biz/x/food/stores/A/sku-routes/SKU").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.skuNo").value("SKU"));
            mvc.perform(post(BINDINGS).header("Authorization",auth).header("Idempotency-Key","binding")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"menuNo\":\"MENU\",\"provider\":\"KEETA\","
                            +"\"channelAccountRef\":\"PLAN\",\"externalStoreRef\":\"PLAN\"}"))
                    .andExpect(jsonPath("$.data.bindingNo").value("BIND"));
            mvc.perform(get(BINDINGS).header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/biz/x/food/menu-channel-bindings/BIND").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.bindingNo").value("BIND"));
            mvc.perform(put("/biz/x/food/menu-channel-bindings/BIND").header("Authorization",auth)
                    .header("Idempotency-Key","update-binding").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"status\":\"DISABLED\",\"externalStoreRef\":\"PLAN\"}"))
                    .andExpect(jsonPath("$.data.version").value(1));
            assertThat(c.getBean(Calls.class).count).hasValue(10);
        });
    }
    @Test void foreignStorePermissionUnknownQueryAndForgedFieldsAreRejectedBeforeService() {
        runner.run(c -> {
            var mvc=mvc(c); var auth=token(c,"staff");
            for(String path:List.of(STATIONS,BINDINGS)) {
                mvc.perform(get(path).header("Authorization",token(c,"cashier")))
                        .andExpect(jsonPath("$.code").value(70006));
                mvc.perform(get(path.replace("/A/","/B/")).header("Authorization",auth))
                        .andExpect(jsonPath("$.code").value(10403));
                mvc.perform(get(path).header("Authorization",auth).param("entityNo","OTHER"))
                        .andExpect(jsonPath("$.code").value(10400));
                mvc.perform(get(path).header("Authorization",auth).param("page","1","2"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(post(STATIONS).header("Authorization",auth).header("Idempotency-Key","forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Kitchen\",\"stationType\":\"HOT\",\"entityNo\":\"OTHER\"}"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(put("/biz/x/food/menu-channel-bindings/BIND").header("Authorization",auth)
                    .header("Idempotency-Key","coerced").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0.5,\"status\":\"ACTIVE\",\"externalStoreRef\":\"PLAN\"}"))
                    .andExpect(jsonPath("$.code").value(10400));
            for(String body:List.of("{\"name\":12,\"stationType\":\"HOT\"}",
                    "{\"name\":\"Kitchen\",\"stationType\":12}")) {
                mvc.perform(post(STATIONS).header("Authorization",auth).header("Idempotency-Key","bad-text")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            for(String body:List.of("{\"version\":0,\"routes\":[{\"stationNo\":12,\"taskRole\":\"MAKE\"}]}",
                    "{\"version\":0,\"routes\":[{\"stationNo\":\"STA\",\"taskRole\":12}]}")) {
                mvc.perform(put("/biz/x/food/stores/A/sku-routes/SKU").header("Authorization",auth)
                        .header("Idempotency-Key","bad-route-text").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            for(String token:List.of("\"true\"","1")) {
                mvc.perform(put(STATIONS+"/STA").header("Authorization",auth).header("Idempotency-Key","bad-enabled")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"Kitchen\","
                                +"\"stationType\":\"HOT\",\"enabled\":"+token+"}"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }
    private String token(WebApplicationContext c,String actor) {
        return "Bearer "+c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    static class Calls { final AtomicInteger count=new AtomicInteger(); }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean Calls stationChannelCalls() { return new Calls(); }
        @Bean StationService stations(Calls calls) {
            return mock(StationService.class,i -> {
                String method=i.getMethod().getName(); if(method.equals("toString")) return "station-api-fixture";
                assertThat((String)i.getArgument(0)).isEqualTo("E");
                assertThat((String)i.getArgument(1)).isEqualTo("A"); calls.count.incrementAndGet();
                var station=new StationVO("STA",0,"DRAFT","A","Kitchen","HOT",true,null);
                return switch(method) {
                    case "createStation" -> { assertThat((String)i.getArgument(2)).isEqualTo("staff");
                        yield new StationMutationVO("STA",0,"DRAFT"); }
                    case "updateStation" -> { assertThat((String)i.getArgument(3)).isEqualTo("staff");
                        yield new StationMutationVO("STA",1,"DRAFT"); }
                    case "getStation" -> new GetStationVO(station);
                    case "listStations" -> PageData.of(List.of(station),1,1,20);
                    case "replaceRoutes" -> { assertThat((String)i.getArgument(3)).isEqualTo("staff");
                        yield new OperationVO("OP","SUCCEEDED",1,"SKU"); }
                    case "getRoutes" -> new StationRoutesVO("SKU",1,List.of());
                    default -> throw new AssertionError(method);
                };
            });
        }
        @Bean MenuChannelBindingService channels(Calls calls) {
            return mock(MenuChannelBindingService.class,i -> {
                String method=i.getMethod().getName(); if(method.equals("toString")) return "channel-api-fixture";
                assertThat((String)i.getArgument(0)).isEqualTo("E");
                assertThat((String)i.getArgument(1)).isEqualTo("A"); calls.count.incrementAndGet();
                long version="updateBinding".equals(method)?1:0;
                var v=new MenuChannelBindingVO("BIND","MENU","A","KEETA","PLAN","PLAN","DRAFT",version);
                if(method.equals("createBinding")) assertThat((String)i.getArgument(2)).isEqualTo("staff");
                if(method.equals("updateBinding")) assertThat((String)i.getArgument(3)).isEqualTo("staff");
                return method.equals("listBindings")?PageData.of(List.of(v),1,1,20):v;
            });
        }
    }
}
