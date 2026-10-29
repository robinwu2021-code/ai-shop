package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.portal.internal.InternalResourceRecoveryController;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.svc.InternalHttp;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Credentials are checked before delegated identity facts; these controlled ports do not prove owner behavior. */
class ResourceRecoveryControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"operator-access", "commands"})
    void actualGlobalAdviceNeverTurnsOwnerDatabaseFailureIntoHttpSuccess(String endpoint) throws Exception {
        MerchantOperatorAccessPort access = query -> {
            throw new DataAccessResourceFailureException("private database details");
        };
        ResourceCommandFactsPort commands = request -> {
            throw new DataAccessResourceFailureException("private database details");
        };
        var controller = new InternalResourceRecoveryController(access, commands, "facts-key");
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        var query = new MerchantOperatorAccessPort.Query("MAIN", "E", "S", "A", "MERCHANT", "MCH", "biz:order:view");
        var binding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT", "A", 0,
                "RESERVE", "a".repeat(64), "b".repeat(64));
        var request = new ResourceSourceAuthorityPort.Request(binding, "A", "MERCHANT", "MCH");
        String body = new ObjectMapper().writeValueAsString(endpoint.equals("commands") ? request : query);
        String response = mvc.perform(post("/internal/merchant/recovery/" + endpoint)
                .header(InternalHttp.TOKEN_HEADER, "facts-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private database details", "code", "command");
    }

    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }
    @Test
    void checksMachineCredentialsBeforeOperatorAndPreservesForbidden() throws Exception {
        var calls = new AtomicInteger();
        Class<?> access = required("ai.neargo.shop.spi.user.MerchantOperatorAccessPort");
        Class<?> facts = required("ai.neargo.shop.spi.resource.ResourceCommandFactsPort");
        Object people = Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[] {access}, (p, m, a) -> {
            calls.incrementAndGet();
            return false;
        });
        Object commands = Proxy.newProxyInstance(facts.getClassLoader(), new Class<?>[] {facts}, (p, m, a) -> {
            calls.incrementAndGet();
            throw BizException.of(ErrorCode.FORBIDDEN);
        });
        Object controller = required("ai.neargo.shop.portal.internal.InternalResourceRecoveryController")
                .getConstructors()[0].newInstance(people, commands, "facts-key");
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        String query = "{\"tenantNo\":\"MAIN\",\"entityNo\":\"E\",\"storeNo\":\"S\",\"operatorNo\":\"A\","
                + "\"realm\":\"MERCHANT\",\"subjectKind\":\"MCH\",\"permission\":\"biz:order:view\"}";
        String path = "/internal/merchant/recovery/operator-access";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isUnauthorized());
        assertThat(calls.get()).isZero();
        String body = mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "facts-key")
                .contentType(MediaType.APPLICATION_JSON).content(query)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(body).isEqualTo("false");
        String request = "{\"binding\":{\"tenantNo\":\"MAIN\",\"entityNo\":\"E\",\"storeNo\":\"S\","
                + "\"commandNo\":\"C\",\"sourceType\":\"BEAUTY_APPOINTMENT\",\"sourceNo\":\"A\","
                + "\"sourceRevision\":0,\"action\":\"RESERVE\",\"commandHash\":\"" + "a".repeat(64) + "\","
                + "\"planHash\":\""
                + "b".repeat(64) + "\"},\"operatorNo\":\"A\",\"realm\":\"MERCHANT\",\"subjectKind\":\"MCH\"}";
        String commandsPath = "/internal/merchant/recovery/commands";
        mvc.perform(post(commandsPath).header(InternalHttp.TOKEN_HEADER, "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized());
        assertThat(calls.get()).isOne();
        mvc.perform(post(commandsPath).header(InternalHttp.TOKEN_HEADER, "facts-key")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        assertThat(calls.get()).isEqualTo(2);
    }
}
