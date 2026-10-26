package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Realm;
import ai.neargo.shop.food.scope.FoodScopeTestSigning;
import ai.neargo.shop.food.server.api.InternalCartController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** TDD-餐饮系统-内部调用签名凭证 AC1 / AC3 / AC4 / AC7 / AC8：经真实安全链打到一个内部 Controller。 */
class FoodScopeVerificationApiTest {
    /** 购物车读取合同只收这三个字段（严格解析）；操作人不符的规则在 FoodScopeCredentialsTest 里覆盖。 */
    private static final String BODY = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"cartNo\":\"C\"}";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire");

    private static MockHttpServletRequestBuilder get() {
        return post(CartInternalApi.GET_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext) c)
                .addFilters(c.getBean(FilterChainProxy.class)).build();
    }

    @Test void ac1TokenAloneIsNotEnoughAndForeignKeyIsRejected() {
        runner.withPropertyValues(FoodScopeTestSigning.KEY_PROPERTY).run(c -> {
            var mvc = mvc(c);
            mvc.perform(get()).andExpect(status().isUnauthorized());
            mvc.perform(get().with(FoodScopeTestSigning.sign(Realm.SYSTEM, null, List.of(), null,
                    "another-key-another-key-another-key"))).andExpect(status().isUnauthorized());
            mvc.perform(get().with(FoodScopeTestSigning.system())).andExpect(status().isOk());
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    @Test void ac3SignedRequestWhoseBodyChangesAfterSigningIsRejected() {
        runner.withPropertyValues(FoodScopeTestSigning.KEY_PROPERTY).run(c -> {
            var mvc = mvc(c);
            // 先签原请求体，再把门店换掉：摘要对不上
            mvc.perform(get().with(FoodScopeTestSigning.staff("E", List.of("A"), "U"))
                    .with(request -> { request.setContent(BODY.replace("\"A\"", "\"B\"").getBytes()); return request; }))
                    .andExpect(status().isUnauthorized());
            assertThat(c.getBean(AtomicInteger.class)).hasValue(0);
        });
    }

    @Test void ac4StaffClaimsMustCoverEveryEntityAndStoreInTheBody() {
        runner.withPropertyValues(FoodScopeTestSigning.KEY_PROPERTY).run(c -> {
            var mvc = mvc(c);
            mvc.perform(get().with(FoodScopeTestSigning.staff("E", List.of("A"), "U"))).andExpect(status().isOk());
            mvc.perform(get().with(FoodScopeTestSigning.staff("OTHER", List.of("A"), "U"))).andExpect(status().isForbidden());
            mvc.perform(get().with(FoodScopeTestSigning.staff("E", List.of("B"), "U"))).andExpect(status().isForbidden());
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    @Test void ac8ConsumerMustBeTheBuyerInTheBody() {
        runner.withPropertyValues(FoodScopeTestSigning.KEY_PROPERTY).run(c -> {
            var mvc = mvc(c);
            String withBuyer = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"cartNo\":\"C\",\"buyerNo\":\"B1\"}";
            mvc.perform(post(CartInternalApi.GET_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                    .contentType(MediaType.APPLICATION_JSON).content(withBuyer)
                    .with(FoodScopeTestSigning.consumer("B2"))).andExpect(status().isForbidden());
            assertThat(c.getBean(AtomicInteger.class)).hasValue(0);
        });
    }

    @Test void ac7NoKeyOrShortKeyRejectsEverything() {
        runner.run(c -> mvc(c).perform(get().with(FoodScopeTestSigning.system())).andExpect(status().isUnauthorized()));
        runner.withPropertyValues("shop.food.scope-key=short").run(c ->
                mvc(c).perform(get().with(FoodScopeTestSigning.system())).andExpect(status().isUnauthorized()));
    }

    @Test void ac6PreviousKeyIsAcceptedDuringRotation() {
        runner.withPropertyValues("shop.food.scope-key=new-key-new-key-new-key-new-key-01",
                "shop.food.scope-key-previous=" + FoodScopeTestSigning.KEY).run(c ->
                mvc(c).perform(get().with(FoodScopeTestSigning.system())).andExpect(status().isOk()));
        assertThat(FoodScopeCredentials.key(FoodScopeTestSigning.KEY)).isNotNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalCartController.class})
    static class Host {
        @Bean AtomicInteger cartCalls() { return new AtomicInteger(); }
        @Bean CheckCartService carts(AtomicInteger calls) {
            return (CheckCartService) Proxy.newProxyInstance(CheckCartService.class.getClassLoader(),
                    new Class<?>[]{CheckCartService.class}, (p, m, a) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "hashCode" -> System.identityHashCode(p);
                                case "equals" -> p == a[0];
                                default -> "cart-fixture";
                            };
                        }
                        calls.incrementAndGet();
                        return new CartResultVO(new CartVO("C", "A", "CHECK", null, null, "OPEN", "POS",
                                "DINE_IN", null, 0L, List.of()));
                    });
        }
    }
}
