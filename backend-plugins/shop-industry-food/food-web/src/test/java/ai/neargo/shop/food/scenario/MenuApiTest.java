package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.MenuCompositionResultVO;
import ai.neargo.shop.food.dto.ReplaceMenuCompositionReq;
import ai.neargo.shop.food.policy.MenuCompositionDefinitions;
import ai.neargo.shop.food.dto.ReplaceMenuContextsReq;
import ai.neargo.shop.food.dto.MenuContextsResultVO;
import ai.neargo.shop.food.dto.DisableMenuReq;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.MenuScheduleResultVO;
import ai.neargo.shop.food.policy.MenuScheduleDefinitions;
import ai.neargo.shop.food.service.MenuService;
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

class MenuApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/menus";
    private static final String BODY = "{\"name\":\"早餐\",\"menuType\":\"TIME\","
            + "\"timezone\":\"Asia/Shanghai\",\"priority\":10,\"exclusiveGroup\":\"MEAL\"}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    @Test void fourEndpointsPreserveExistingShapesAndTrustedActorScope() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "create")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.configNo").value("M1"));
            mvc.perform(get(ROOT + "/M1").header("Authorization", auth))
                    .andExpect(jsonPath("$.data.config.menuType").value("TIME"));
            mvc.perform(get(ROOT).header("Authorization", auth).param("keyword", "早餐").param("status", "DRAFT"))
                    .andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(put("/biz/x/food/menus/M1").header("Authorization", auth).header("Idempotency-Key", "edit")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("{", "{\"version\":0,")))
                    .andExpect(jsonPath("$.data.menu.version").value(1));
            assertThat(c.getBean(AtomicInteger.class)).hasValue(4);
        });
    }
    @Test void permissionStoreUnknownFieldsAndNumericCoercionAreRejectedBeforeBusinessCalls() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(ROOT).header("Authorization", token(c, "cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get(ROOT.replace("/A/", "/B/")).header("Authorization", auth))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get(ROOT).header("Authorization", auth).param("entityNo", "OTHER"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(ROOT).header("Authorization", auth).param("status", "DRAFT", "DISABLED"))
                    .andExpect(jsonPath("$.code").value(10400));
            for (String body : List.of(BODY.replace("{", "{\"entityNo\":\"OTHER\","),
                    BODY.replace("\"早餐\"", "123"), BODY.replace("10", "1.5"), BODY.replace("10", "\"10\""))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(post(ROOT).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(AtomicInteger.class)).hasValue(0);
        });
    }
    @Test void scheduleSectionUsesExistingDefinitionRouteAndRejectsForeignFieldsAndMalformedTokens() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            String body = "{\"section\":\"SCHEDULE\",\"version\":0,\"weekSchedules\":["
                    + "{\"weekday\":1,\"startTime\":\"22:00\",\"endTime\":\"02:00\",\"endDayOffset\":1}],"
                    + "\"exceptions\":[]}";
            mvc.perform(put("/biz/x/food/menus/M1/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "schedule").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.data.schedule.weekSchedules[0].endDayOffset").value(1));
            for (String bad : List.of(body.replace("\"weekday\":1", "\"weekday\":1.5"),
                    body.replace("{", "{\"categories\":[],"), body.replace("\"exceptions\":[]",
                            "\"exceptions\":null"))) {
                mvc.perform(put("/biz/x/food/menus/M1/definition").header("Authorization", auth)
                        .header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON).content(bad))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    @Test void compositionUsesDefinitionRouteAndRejectsMixedSectionsAndCoercedFlags() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            String body = "{\"section\":\"COMPOSITION\",\"version\":0,\"categories\":[]}";
            mvc.perform(put("/biz/x/food/menus/M1/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "composition").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.data.composition.categories").isEmpty());
            for (String bad : List.of(body.replace("COMPOSITION", "UNKNOWN"),
                    body.replace("\"version\":0", "\"version\":\"0\""),
                    body.replace("\"categories\":[]", "\"categories\":[],\"exceptions\":[]"))) {
                mvc.perform(put("/biz/x/food/menus/M1/definition").header("Authorization", auth)
                        .header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON).content(bad))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    private String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean AtomicInteger menuCalls() { return new AtomicInteger(); }
        @Bean MenuService menuService(AtomicInteger calls) {
            return new MenuService() {
                private MenuVO view(String entity, String store, long version) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A"); calls.incrementAndGet();
                    return new MenuVO("M1", store, "早餐", "TIME", "Asia/Shanghai", 10, "MEAL", "DRAFT",
                            version, version);
                }
                @Override public CreateMenuResultVO createMenu(String entity, String store, String actor,
                        CreateMenuReq req, String key) {
                    assertThat(actor).isEqualTo("staff"); view(entity, store, 0);
                    return new CreateMenuResultVO("M1", 0L, "DRAFT");
                }
                @Override public MenuResultVO updateMenu(String entity, String store, String menu, String actor,
                        UpdateMenuReq req, String key) {
                    assertThat(actor).isEqualTo("staff"); assertThat(menu).isEqualTo("M1");
                    return new MenuResultVO(view(entity, store, 1));
                }
                @Override public MenuScheduleResultVO replaceSchedule(String entity, String store, String menu,
                        String actor, ReplaceMenuScheduleReq req, String key) {
                    assertThat(actor).isEqualTo("staff"); assertThat(menu).isEqualTo("M1");
                    var rules = MenuScheduleDefinitions.normalize(req);
                    return new MenuScheduleResultVO(view(entity, store, 1), rules);
                }
                @Override public MenuCompositionResultVO replaceComposition(String entity, String store,
                        String menu, String actor, ReplaceMenuCompositionReq req, String key) {
                    assertThat(actor).isEqualTo("staff"); assertThat(menu).isEqualTo("M1");
                    return new MenuCompositionResultVO(view(entity, store, 1),
                            MenuCompositionDefinitions.normalize(req));
                }
                @Override public MenuContextsResultVO replaceContexts(String e, String st, String menu,
                        String actor, ReplaceMenuContextsReq req, String key) {
                    return new MenuContextsResultVO(view(e, st, 1), req.contexts());
                }
                @Override public MenuResultVO disableMenu(String e, String st, String menu, String actor,
                        DisableMenuReq req, String key) { throw new UnsupportedOperationException(); }
                @Override public MenuConfigResultVO getMenu(String entity, String store, String menu) {
                    assertThat(menu).isEqualTo("M1"); return new MenuConfigResultVO(view(entity, store, 0));
                }
                @Override public PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) {
                    assertThat(query.keyword()).isEqualTo("早餐"); assertThat(query.status()).isEqualTo("DRAFT");
                    return PageData.of(List.of(view(entity, store, 0)), 1, 1, 20);
                }
            };
        }
    }
}
