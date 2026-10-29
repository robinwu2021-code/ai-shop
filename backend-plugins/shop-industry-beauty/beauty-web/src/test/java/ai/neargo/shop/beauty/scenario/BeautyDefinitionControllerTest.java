package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import ai.neargo.shop.common.GlobalExceptionHandler;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.common.PageData;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real platform login, permissions and scope filters protect the independent web artifact. */
class BeautyDefinitionControllerTest {
    private static final String PATH = "/biz/x/beauty/service-definitions";
    private static final String BODY = "{\"skuNo\":\"SKU\",\"expectedRevision\":0,\"duration\":{\"minutes\":60,"
            + "\"bufferBeforeMin\":5,\"bufferAfterMin\":10},\"requirements\":[{\"roleCode\":\"TECH\","
            + "\"resourceType\":\"STAFF\",\"skillGoodsNo\":\"G\",\"qty\":1,\"sequenceNo\":0,"
            + "\"offsetMin\":0,\"durationMin\":60,\"selectionRule\":\"ANY_QUALIFIED\"}]}";

    private WebApplicationContextRunner runner() {
        Class<?> config = assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration"));
        assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController"));
        var service = (ServiceDefinitionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServiceDefinitionService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) { return "definition-contract"; }
                    String trusted = args[0] + ":" + args[1] + ":" + args[2];
                    if (method.getName().equals("listDefinitions")) {
                        return PageData.of(List.of(Map.of("definitionNo", trusted)), 1, 1, 20);
                    }
                    return new ServiceDefinitionVersionVO(trusted, "V", 1L, "HASH");
                });
        return new WebApplicationContextRunner().withUserConfiguration(BeautyPolicyControllerTest.Host.class)
                .withConfiguration(AutoConfigurations.of(config)).withBean(ServiceDefinitionService.class,
                        () -> service)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded",
                        "spring.profiles.active=api");
    }

    @Test
    void derivesActorAndStoreForReadAndPublish() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
            String read = mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(read).contains("E:A:staff");
            String write = mvc.perform(post(PATH + "/versions").header("Authorization", "Bearer " + token)
                    .header("X-Actor-No", "spoof").header("Idempotency-Key", "K")
                    .contentType("application/json").content(BODY)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(write).contains("E:A:staff").doesNotContain("spoof");
        });
    }

    @Test
    void rejectsRawInvalidStoreBeforeDefaultFallbackCanHideIt() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token).header("X-Store-No", "FOREIGN"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/versions").header("Authorization", "Bearer " + token)
                    .header("X-Store-No", "B").header("Idempotency-Key", "K")
                    .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        });
    }

    @Test
    void requiresLoginPermissionAndDurableKey() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("clerk", "clerk")));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            String staff = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
            mvc.perform(post(PATH + "/versions").header("Authorization", "Bearer " + staff)
                    .contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        });
    }
    @Test
    void invalidPaginationUsesTheRealPlatformBadRequestEnvelope() {
        runner().withBean(GlobalExceptionHandler.class).run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token).param("size", "101"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token).param("page", "0"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token).param("skuNo", "X".repeat(33)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token).param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].definitionNo").value("E:A:staff"));
        });
    }

}
