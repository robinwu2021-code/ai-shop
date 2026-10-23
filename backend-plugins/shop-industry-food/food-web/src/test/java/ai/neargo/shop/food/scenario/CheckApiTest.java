package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.CheckService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class CheckApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class,Security.class,Host.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    static final String OPEN="{\"tableNos\":[\"T1\"],\"partySize\":2,\"payMode\":\"PREPAY\",\"tableVersions\":[{\"tableNo\":\"T1\",\"version\":0}],\"sharingMode\":\"EXCLUSIVE\",\"seats\":[]}";
    @Test void allTenRoutesUseOriginalPermissionsAndTrustedScope() {
        runner.run(c -> {
            var mvc=mvc(c);String auth=auth(c,"staff");
            mvc.perform(get("/biz/x/food/stores/A/checks").header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/biz/x/food/checks/C1").header("Authorization",auth)).andExpect(jsonPath("$.data.check.checkNo").value("C1"));
            mvc.perform(get("/biz/x/food/checks/C1/actions").header("Authorization",auth)).andExpect(jsonPath("$.data.records[0].action").value("CLEAR"));
            mvc.perform(get("/biz/x/food/table-operations/OP1").header("Authorization",auth)).andExpect(jsonPath("$.data.operation.status").value("SUCCEEDED"));
            for(String[] action:List.of(
                    new String[]{"/biz/x/food/stores/A/checks",OPEN},
                    new String[]{"/biz/x/food/checks/C1:clear","{\"version\":0,\"reason\":\"离店\",\"occupancyNos\":[\"O1\"]}"},
                    new String[]{"/biz/x/food/checks/C1:transfer","{\"version\":0,\"reason\":\"换台\",\"targetTableNos\":[\"T2\"],\"tableVersions\":[{\"tableNo\":\"T1\",\"version\":0},{\"tableNo\":\"T2\",\"version\":0}]}"},
                    new String[]{"/biz/x/food/checks/C1:combine-tables","{\"version\":0,\"reason\":\"并桌\",\"tableVersions\":[{\"tableNo\":\"T2\",\"version\":0}]}"},
                    new String[]{"/biz/x/food/tables/T1:share","{\"version\":0,\"partySize\":1,\"payMode\":\"PREPAY\"}"}))
                mvc.perform(post(action[0]).header("Authorization",auth).header("Idempotency-Key","original")
                        .contentType(MediaType.APPLICATION_JSON).content(action[1])).andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0));
            mvc.perform(put("/biz/x/food/checks/C1/seats").header("Authorization",auth).header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"partySize\":2,\"seats\":[],\"reason\":\"加人\"}"))
                    .andExpect(jsonPath("$.data.check.partySize").value(2));
            assertThat(c.getBean(Calls.class).count.get()).isEqualTo(10);
        });
    }
    @Test void rejectsUnauthorizedStoreVersionBodyAndMissingKeyBeforeBusinessCall() {
        runner.run(c -> {
            var mvc=mvc(c);var staff=auth(c,"staff");
            mvc.perform(get("/biz/x/food/checks/C1").header("Authorization",auth(c,"denied"))).andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/checks/C1").header("Authorization",staff).header("X-Store-No","B")).andExpect(jsonPath("$.code").value(10404));
            mvc.perform(get("/biz/x/food/checks/FOREIGN").header("Authorization",staff)).andExpect(jsonPath("$.code").value(10404));
            mvc.perform(get("/biz/x/food/stores/B/checks").header("Authorization",staff)).andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/biz/x/food/stores/A/checks").header("Authorization",staff).param("size","101")).andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post("/biz/x/food/stores/A/checks").header("Authorization",staff)
                    .contentType(MediaType.APPLICATION_JSON).content(OPEN)).andExpect(jsonPath("$.code").value(10400));
            for(String field:List.of("actorNo","entityNo","financialStatus"))
                mvc.perform(post("/biz/x/food/stores/A/checks").header("Authorization",staff).header("Idempotency-Key","original")
                        .contentType(MediaType.APPLICATION_JSON).content(OPEN.replaceFirst("\\{","{\""+field+"\":\"forged\",")))
                        .andExpect(jsonPath("$.code").value(10400));
        });
    }
    @Test void waiterCanReadTableMetadataWithoutConfigurationWritePermission() {
        runner.withUserConfiguration(TableConfigurationApiTest.Host.class).run(c -> {
            var mvc=mvc(c);var staff=auth(c,"staff");
            mvc.perform(get("/biz/x/food/stores/A/table-areas").header("Authorization",staff))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(get("/biz/x/food/stores/A/tables").header("Authorization",staff))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/biz/x/food/tables/T1").header("Authorization",staff))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.table.tableNo").value("T1"));
            mvc.perform(post("/biz/x/food/stores/A/tables").header("Authorization",staff).header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"areaNo\":\"AREA1\",\"code\":\"T1\",\"name\":\"四人桌\",\"capacity\":4,\"sortNo\":0}"))
                    .andExpect(jsonPath("$.code").value(70006));
        });
    }
    @Test void offAndOpsHaveNoCheckRoutes() {
        for(String setting:List.of("shop.food.migration.mode=off","spring.profiles.active=ops"))
            runner.withPropertyValues(setting).run(c -> mvc(c).perform(get("/biz/x/food/checks/C1")
                    .header("Authorization",auth(c,"staff"))).andExpect(status().isNotFound()));
    }
    MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class),
                new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request,
                            HttpServletResponse response,FilterChain chain)
                            throws IOException,ServletException {
                        var original=DataScopeContext.current();
                        try { chain.doFilter(request,response); }
                        finally { assertThat(DataScopeContext.current()).isSameAs(original); }
                    }
                }).build();
    }
    String auth(WebApplicationContext c,String actor) { return "Bearer "+c.getBean(MemoryTokenStore.class)
            .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor))); }
    static class Calls { final AtomicInteger count=new AtomicInteger(); }
    @Configuration(proxyBeanMethods=false) static class Host {
        @Bean Calls checkCalls() { return new Calls(); }
        @Bean CheckService checkService(Calls calls) {
            return (CheckService)Proxy.newProxyInstance(CheckService.class.getClassLoader(),new Class<?>[]{CheckService.class},(p,m,a) -> {
                if(m.getDeclaringClass()==Object.class) return m.getName().equals("hashCode")?System.identityHashCode(p):"fixture";
                var registry=new DataScopeTableRegistry();
                registry.register("fnb_check",Map.of(ScopeDim.MERCHANT,"entity_no"));
                var predicate=new DataScopeHandler(registry).getSqlSegment(
                        new net.sf.jsqlparser.schema.Table("fnb_check"),null,"food-scope-proof");
                assertThat(predicate.toString()).contains("entity_no").contains("'E'");
                assertThat(a[0]).isEqualTo("E");
                if("B".equals(a[1])) throw BizException.of(ErrorCode.NOT_FOUND);
                assertThat(a[1]).isEqualTo("A");
                if(a.length>2 && "FOREIGN".equals(a[2])) throw BizException.of(ErrorCode.NOT_FOUND);
                if(List.of("openCheck","saveSeats","clearCheck","transferCheck","combineTables","openSharedCheck").contains(m.getName())) {
                    assertThat(a[a.length-1]).isEqualTo("original");assertThat(a[m.getName().equals("openCheck")?2:3]).isEqualTo("staff");
                }
                calls.count.incrementAndGet();
                var check=new CheckVO("C1","A",LocalDate.of(2026,10,21),"OPEN","PREPAY",2,List.of("T1"),0L);
                var op=new OperationVO("OP1","SUCCEEDED",1L,"C1");
                return switch(m.getName()) {
                    case "listChecks" -> PageData.of(List.of(check),1,1,20);
                    case "listActions" -> PageData.of(List.of(new ActionLogVO("OP1","CLEAR","staff",Instant.EPOCH,"离店")),1,1,20);
                    case "getCheck" -> new CheckDetailVO(check,List.of(),List.of());
                    case "clearCheck" -> op;
                    case "transferCheck","combineTables","getTableOperation" -> new OperationResultVO(op);
                    default -> new CheckResultVO(check);
                };
            });
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean("perm") PermChecker perm(ObjectProvider<LivePermResolver> resolver) { return new PermChecker(resolver); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean SecurityFilterChain chain(HttpSecurity http,MemoryTokenStore tokens) throws Exception {
            var filter=new BizContextFilter((actor,requested) -> new BizContext("E",Set.of(),Set.of(),Set.of("A","B"),"A",false,
                    Map.of(),Map.of("A",actor.equals("denied")?Set.of():BizPerms.of(Set.of(BizPerms.CLERK)),"B",BizPerms.of(Set.of(BizPerms.CLERK)))));
            return http.csrf(x -> x.disable()).authorizeHttpRequests(x -> x.anyRequest().authenticated())
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens),UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(filter,MerchantTokenAuthFilter.class).build();
        }
    }
}
