package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.server.api.InternalServiceCalendarController;
import ai.neargo.shop.food.server.api.InternalStoreOperationPolicyController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalPolicyApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                    "shop.services.internal-token=internal-test", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);
    private static final String BODY = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"at\":\"2026-10-20T12:00:00Z\"}";

    @Test void trustedReadUsesPlainTypedResponse() {
        runner.run(context -> mvc(context).perform(post(StoreOperationInternalApi.POLICY_PATH)
                        .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policyNo").value("P1"))
                .andExpect(jsonPath("$.data").doesNotExist()));
    }
    @Test void businessFailureAndInvalidScopeHaveStableStatus() {
        runner.run(context -> {
            mvc(context).perform(post(StoreOperationInternalApi.POLICY_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                            .content(BODY.replace("\"E\"","\"FOREIGN\""))).andExpect(status().isNotFound());
            mvc(context).perform(post(StoreOperationInternalApi.POLICY_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeNo\":\"A\"}")).andExpect(status().isBadRequest());
        });
    }
    @Test void missingOrIncorrectServiceCredentialIsRejected() {
        runner.run(context -> {
            mvc(context).perform(post(StoreOperationInternalApi.POLICY_PATH).contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)).andExpect(status().isUnauthorized());
            mvc(context).perform(post(StoreOperationInternalApi.POLICY_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"wrong").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                    .andExpect(status().isUnauthorized());
        });
    }
    @Test void trustedPublicationValidatesNestedRequestAndActor() {
        String command = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"staff\",\"idempotencyKey\":\"original\","
                + "\"policy\":{\"timezone\":\"Asia/Shanghai\",\"cutoverMinute\":240,"
                + "\"effectiveFrom\":\"2026-10-22T12:00:00Z\",\"payPolicy\":\"POSTPAY\","
                + "\"capabilities\":[],\"version\":7}}";
        runner.run(context -> {
            mvc(context).perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                            .content(command)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(8));
            mvc(context).perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                            .content(command.replace("\"version\":7", "\"version\":-1")))
                    .andExpect(status().isBadRequest());
        });
    }
    @Test void calendarValidatesTrustedCommandAndDate() {
        String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"staff\",\"localDate\":\"2026-10-20\","
                + "\"idempotencyKey\":\"calendar-original\","
                + "\"calendar\":{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}}";
        runner.run(context -> {
            mvc(context).perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
            mvc(context).perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                            .header(InternalHttp.TOKEN_HEADER,"internal-test").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                            .contentType(MediaType.APPLICATION_JSON).content(body.replace("2026-10-20","bad-date")))
                    .andExpect(status().isBadRequest());
            mvc(context).perform(post(ServiceCalendarInternalApi.SAVE_PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(body)).andExpect(status().isUnauthorized());
        });
    }
    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({InternalStoreOperationPolicyController.class, InternalServiceCalendarController.class,
            FoodInternalSecurityConfiguration.class})
    static class Host {
        @Bean StoreOperationService service() {
            return new StoreOperationService() {
                public StoreOperationPolicyResultVO getPolicy(String entity, String store, GetPolicyQuery query) {
                if (!"E".equals(entity)) throw BizException.of(ErrorCode.NOT_FOUND);
                return new StoreOperationPolicyResultVO("P1","Asia/Shanghai",240,"PREPAY",List.of(),1);
                }
                public OperationVO saveCalendar(String entity, String store, LocalDate day, String actor,
                        SaveCalendarReq req, String key) {
                    assertThat(entity).isEqualTo("E");
                    assertThat(store).isEqualTo("A");
                    assertThat(actor).isEqualTo("staff");
                    assertThat(key).isEqualTo("calendar-original");
                    return new OperationVO("CA1", "SUCCEEDED", req.version()+1, "CAL1");
                }
                public OperationVO createPolicy(String entity, String store, String actor,
                        CreatePolicyReq req, String key) {
                    if (!"E".equals(entity) || !"A".equals(store)) throw new AssertionError("Untrusted scope");
                    return new OperationVO("OP1", "SUCCEEDED", req.version() + 1, "P2");
                }
            };
        }
    }
}
