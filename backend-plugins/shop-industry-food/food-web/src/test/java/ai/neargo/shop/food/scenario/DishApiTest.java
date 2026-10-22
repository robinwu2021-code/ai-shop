package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.common.PageData;
import java.util.List;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.service.DishService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DishApiTest {
    private static final String BODY = """
            {"dishKind":"SINGLE","prepSeconds":60,"presentation":{"displayName":"奶茶"},
             "measurement":{"mode":"COUNT","unit":"EA","scale":0}}
            """;
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void usesGoodsPermissionTrustedScopeActorKeyAndOriginalEnvelope() {
        runner.run(c -> {
            mvc(c).perform(put("/biz/x/food/stores/A/dishes/G").header("Authorization", token(c, "staff"))
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.dish.goodsNo").value("G"));
            assertThat(c.getBean(Calls.class).count).hasValue(1);
        });
    }

    @Test void deniesMissingPermissionHeaderStoreMismatchAndForgedFieldsBeforeCallingService() {
        runner.run(c -> {
            var mvc = mvc(c);
            var auth = token(c, "staff");
            mvc.perform(put("/biz/x/food/stores/A/dishes/G").header("Authorization", token(c, "cashier"))
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(put("/biz/x/food/stores/B/dishes/G").header("Authorization", auth)
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10403));
            for (String payload : new String[] {BODY.replace("\"dishKind\"", "\"entityNo\":\"OTHER\",\"dishKind\""),
                    BODY.replace("\"mode\"", "\"stock\":100,\"mode\""),
                    BODY.replace("\"displayName\"", "\"price\":1,\"displayName\""),
                    BODY.replace("60", "-1")}) {
                mvc.perform(put("/biz/x/food/stores/A/dishes/G").header("Authorization", auth)
                        .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(put("/biz/x/food/stores/A/dishes/G").header("Authorization", auth)
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }

    @Test void queriesUseOriginalScopeAndDenyUnknownFiltersWithoutWriting() {
        runner.run(c -> {
            var mvc=mvc(c); var auth=token(c,"staff");
            mvc.perform(get("/biz/x/food/stores/A/dishes").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(get("/biz/x/food/stores/A/dishes/G").header("Authorization",auth))
                    .andExpect(jsonPath("$.data.coreCatalog.cover").value("cover"));
            for(String path:List.of("/biz/x/food/stores/A/dishes","/biz/x/food/stores/A/dishes/G")) {
                mvc.perform(get(path).header("Authorization",token(c,"cashier"))).andExpect(jsonPath("$.code").value(70006));
                mvc.perform(get(path).header("Authorization",auth).param("entityNo","OTHER")).andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(get("/biz/x/food/stores/A/dishes").header("Authorization",auth).param("size","101"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/biz/x/food/stores/B/dishes").header("Authorization",auth))
                    .andExpect(jsonPath("$.code").value(10403));
            assertThat(c.getBean(Calls.class).count).hasValue(2);
        });
    }

    private String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    static class Calls { final AtomicInteger count = new AtomicInteger(); }
    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean Calls dishCalls() { return new Calls(); }
        @Bean DishService dishService(Calls calls) {
            return new DishService() {
            @Override public PageData<DishCatalogItemVO> listDishes(String entity,String store,ListDishesQuery query) {
                assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                calls.count.incrementAndGet(); return PageData.of(List.of(),0,1,20);
            }
            @Override public DishDetailResultVO getDish(String entity,String store,String goods) {
                assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A"); assertThat(goods).isEqualTo("G");
                calls.count.incrementAndGet();
                return new DishDetailResultVO(null,List.of(),goods,new GoodsFact(goods,entity,store,"基础名","cover",false,"PENDING",0,List.of()));
            }
            @Override public DishResultVO saveDish(String entity,String store,String goods,String actor,SaveDishReq req,String key) {
                assertThat(entity).isEqualTo("E");
                assertThat(store).isEqualTo("A");
                assertThat(goods).isEqualTo("G");
                assertThat(actor).isEqualTo("staff");
                assertThat(key).isEqualTo("original");
                calls.count.incrementAndGet();
                return new DishResultVO(new DishVO(goods, req.dishKind(), req.prepSeconds(), req.presentation(),
                        req.measurement(), 1, 0));
            }
            };
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class Security extends FoodPolicyApiTest.PolicySecurity {
        @Override @Bean
        SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, requested) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("A", "B"), "A", false, Map.of(), Map.of(
                            "A", "cashier".equals(actor) ? Set.of() : Set.of("biz:goods"), "B", Set.of("biz:goods"))));
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
