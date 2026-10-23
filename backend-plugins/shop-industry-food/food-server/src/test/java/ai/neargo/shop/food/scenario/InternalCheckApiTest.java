package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.server.api.InternalCheckController;
import ai.neargo.shop.food.server.api.InternalTableOperationController;
import ai.neargo.shop.food.server.api.InternalTableSharingController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.lang.reflect.Proxy;
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

class InternalCheckApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true","shop.services.internal-token=wire");
    @Test void allTenRoutesRequireServiceTokenAndReturnUnwrappedData() {
        runner.run(c -> {
            var mvc=mvc(c);String scope="\"entityNo\":\"E\",\"storeNo\":\"A\"";
            String actor=",\"actorNo\":\"staff\",\"idempotencyKey\":\"original\"";
            String id=",\"checkNo\":\"C1\"";
            for(String[] spec:List.of(
                    new String[]{CheckInternalApi.LIST_PATH,"{"+scope+"}","$.total"},
                    new String[]{CheckInternalApi.GET_PATH,"{"+scope+id+"}","$.check.checkNo"},
                    new String[]{CheckInternalApi.ACTIONS_PATH,"{"+scope+id+"}","$.total"},
                    new String[]{CheckInternalApi.OPERATION_PATH,"{"+scope+",\"operationNo\":\"OP1\"}","$.operation.status"},
                    new String[]{CheckInternalApi.OPEN_PATH,"{"+scope+actor+",\"request\":{\"tableNos\":[],\"partySize\":2,\"payMode\":\"PREPAY\",\"tableVersions\":[],\"sharingMode\":\"EXCLUSIVE\",\"seats\":[]}}","$.check.checkNo"},
                    new String[]{CheckInternalApi.SEATS_PATH,"{"+scope+id+actor+",\"request\":{\"version\":0,\"partySize\":2,\"seats\":[],\"reason\":\"加人\"}}","$.check.checkNo"},
                    new String[]{CheckInternalApi.CLEAR_PATH,"{"+scope+id+actor+",\"request\":{\"version\":0,\"reason\":\"清台\",\"occupancyNos\":[\"O1\"]}}","$.status"},
                    new String[]{CheckInternalApi.TRANSFER_PATH,"{"+scope+id+actor+",\"request\":{\"version\":0,\"reason\":\"换台\",\"targetTableNos\":[\"T1\"],\"tableVersions\":[{\"tableNo\":\"T1\",\"version\":0}]}}","$.operation.status"},
                    new String[]{CheckInternalApi.COMBINE_PATH,"{"+scope+id+actor+",\"request\":{\"version\":0,\"reason\":\"并桌\",\"tableVersions\":[{\"tableNo\":\"T1\",\"version\":0}]}}","$.operation.status"},
                    new String[]{CheckInternalApi.SHARE_PATH,"{"+scope+actor+",\"tableNo\":\"T1\",\"request\":{\"version\":0,\"partySize\":1,\"payMode\":\"PREPAY\"}}","$.check.checkNo"})) {
                mvc.perform(post(spec[0]).header(InternalHttp.TOKEN_HEADER,"wire").contentType(MediaType.APPLICATION_JSON).content(spec[1]))
                        .andExpect(status().isOk()).andExpect(jsonPath(spec[2]).exists()).andExpect(jsonPath("$.code").doesNotExist());
                mvc.perform(post(spec[0]).contentType(MediaType.APPLICATION_JSON).content(spec[1])).andExpect(status().isUnauthorized());
            }
        });
    }
    @Test void invalidCommandsAndBusinessConflictRetainHttpStatus() {
        runner.run(c -> {
            var mvc=mvc(c);
            mvc.perform(post(CheckInternalApi.GET_PATH).header(InternalHttp.TOKEN_HEADER,"wire")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"entityNo\":\"E\",\"storeNo\":\"A\",\"checkNo\":\"conflict\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post(CheckInternalApi.OPEN_PATH).header(InternalHttp.TOKEN_HEADER,"wire")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        });
    }
    MockMvc mvc(WebApplicationContext c) { return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build(); }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalCheckController.class, InternalTableSharingController.class, InternalTableOperationController.class})
    static class Host {
        @Bean CheckService service() {
            return (CheckService)Proxy.newProxyInstance(CheckService.class.getClassLoader(),new Class<?>[]{CheckService.class},(p,m,a) -> {
                if(m.getDeclaringClass()==Object.class) return m.getName().equals("hashCode")?System.identityHashCode(p):"fixture";
                assertThat(a[0]).isEqualTo("E");assertThat(a[1]).isEqualTo("A");
                if(a.length>2 && "conflict".equals(a[2])) throw BizException.of(ErrorCode.CONFLICT);
                if(List.of("openCheck","saveSeats","clearCheck","transferCheck","combineTables","openSharedCheck").contains(m.getName())) {
                    assertThat(a[a.length-1]).isEqualTo("original");assertThat(a[m.getName().equals("openCheck")?2:3]).isEqualTo("staff");
                }
                var check=new CheckVO("C1","A",LocalDate.of(2026,10,21),"OPEN","PREPAY",2,List.of("T1"),0L);
                var op=new OperationVO("OP1","SUCCEEDED",1L,"C1");
                return switch(m.getName()) {
                    case "listChecks" -> PageData.of(List.of(check),1,1,20);
                    case "listActions" -> PageData.of(List.of(),0,1,20);
                    case "getCheck" -> new CheckDetailVO(check,List.of(),List.of());
                    case "clearCheck" -> op;
                    case "transferCheck","combineTables","getTableOperation" -> new OperationResultVO(op);
                    default -> new CheckResultVO(check);
                };
            });
        }
    }
}
