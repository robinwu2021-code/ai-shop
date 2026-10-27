package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.server.api.InternalDiningSessionController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.lang.reflect.Proxy;
import java.time.Instant;
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

class InternalDiningSessionApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true","shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);
    @Test void allFiveRoutesUseServiceTokenAndReturnRawTypedObjects() {
        runner.run(c -> {
            var mvc=mvc(c);String common="\"entityNo\":\"E\",\"storeNo\":\"A\"";
            String actor=",\"actorNo\":\"staff\",\"tableNo\":\"T1\",\"idempotencyKey\":\"original\"";
            for(String[] item:List.of(
                    new String[]{DiningSessionInternalApi.ROTATE_PATH,"{"+common+actor+",\"request\":{\"version\":0,\"expiresAt\":\"2026-12-01T00:00:00Z\"}}","$.token"},
                    new String[]{DiningSessionInternalApi.REVOKE_PATH,"{"+common+actor+",\"request\":{\"version\":0,\"reason\":\"撤销\"}}","$.operation.status"},
                    new String[]{DiningSessionInternalApi.RESOLVE_PATH,"{\"tableToken\":\"raw\"}","$.tableNo"},
                    new String[]{DiningSessionInternalApi.START_PATH,"{"+common+",\"buyerNo\":\"buyer\",\"idempotencyKey\":\"original\",\"request\":{\"tableToken\":\"raw\"}}","$.accessToken"},
                    new String[]{DiningSessionInternalApi.END_PATH,"{\"sessionNo\":\"S1\",\"accessToken\":\"short\",\"buyerNo\":\"buyer\",\"idempotencyKey\":\"original\"}","$.operation.status"})) {
                mvc.perform(post(item[0]).header(InternalHttp.TOKEN_HEADER,"wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system()).contentType(MediaType.APPLICATION_JSON)
                        .content(item[1])).andExpect(status().isOk()).andExpect(jsonPath(item[2]).exists())
                        .andExpect(jsonPath("$.code").doesNotExist());
                mvc.perform(post(item[0]).contentType(MediaType.APPLICATION_JSON).content(item[1]))
                        .andExpect(status().isUnauthorized());
            }
        });
    }
    @Test void rejectsMalformedAndPreservesBusinessConflict() {
        runner.run(c -> {
            var mvc=mvc(c);
            mvc.perform(post(DiningSessionInternalApi.RESOLVE_PATH).header(InternalHttp.TOKEN_HEADER,"wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tableToken\":\"conflict\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post(DiningSessionInternalApi.RESOLVE_PATH).header(InternalHttp.TOKEN_HEADER,"wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        });
    }
    MockMvc mvc(WebApplicationContext c) { return MockMvcBuilders.webAppContextSetup(c)
            .addFilters(c.getBean(FilterChainProxy.class)).build(); }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalDiningSessionController.class})
    static class Host {
        @Bean DiningSessionService sessionService() {
            return (DiningSessionService)Proxy.newProxyInstance(DiningSessionService.class.getClassLoader(),
                    new Class<?>[]{DiningSessionService.class},(proxy,method,args) -> {
                        if(method.getDeclaringClass()==Object.class) return "fixture";
                        var expiry=Instant.parse("2026-12-01T00:00:00Z");
                        if(method.getName().equals("resolveTableToken")) {
                            if(args[0].equals("conflict")) throw BizException.of(ErrorCode.CONFLICT);
                            assertThat(args[0]).isEqualTo("raw");return new DiningSessionTableVO("E","A","T1",expiry);
                        }
                        if(method.getName().equals("endSession")) {
                            assertThat(args).containsExactly("S1","short","buyer","original");
                        } else {
                            assertThat(args[0]).isEqualTo("E");assertThat(args[1]).isEqualTo("A");
                            if(method.getName().equals("startSession")) {
                                assertThat(args[2]).isEqualTo("buyer");assertThat(args[4]).isEqualTo("original");
                                return new DiningSessionVO("S1","short",expiry,null);
                            }
                            assertThat(args[2]).isEqualTo("T1");assertThat(args[3]).isEqualTo("staff");
                            assertThat(args[5]).isEqualTo("original");
                            if(method.getName().equals("rotateTableToken")) return new TableTokenVO("raw",expiry);
                        }
                        return new OperationResultVO(new OperationVO("OP","SUCCEEDED",1L,"T1"));
                    });
        }
    }
}
