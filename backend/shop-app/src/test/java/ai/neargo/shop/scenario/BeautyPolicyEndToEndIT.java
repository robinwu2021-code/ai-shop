package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Explicit disposable MySQL + embedded profile only; real HTTP/controller/service/transaction/SQL chain. */
class BeautyPolicyEndToEndIT {
    private Class<?> type(String name) {
        return ClassUtils.resolveClassName("ai.neargo.shop.beauty.config." + name, getClass().getClassLoader());
    }

    @Test
    void authenticatesPublishesReplaysAndRejectsStoreFallbackAgainstActualDatabase() throws Exception {
        String adminUrl = System.getProperty("beauty.test.admin-url");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql\\?.*");
        String database = "beauty_http_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        admin.execute("CREATE DATABASE " + database);
        var source = new DriverManagerDataSource(adminUrl.replace("/mysql?", "/" + database + "?"), "root", "");
        Object validator = type("BeautySchemaValidator").getConstructor().newInstance();
        type("BeautySchemaValidator").getMethod("initialize", DataSource.class, String.class)
                .invoke(validator, source, "migrate");
        new ResourceDatabasePopulator(new FileSystemResource("../../backend-plugins/shop-industry-beauty/"
                + "beauty-core/src/test/resources/platform-write-fixture.sql")).execute(source);
        var sql = new JdbcTemplate(source);
        new WebApplicationContextRunner().withUserConfiguration(Host.class, PrimaryPersistenceConfiguration.class)
                .withConfiguration(AutoConfigurations.of(type("BeautyAutoConfiguration"),
                        type("BeautyPersistenceAutoConfiguration"), type("BeautyBusinessAutoConfiguration"),
                        type("BeautyWebAutoConfiguration")))
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded",
                        "spring.profiles.active=api", "spring.datasource.url=" + source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    String token = context.getBean(MemoryTokenStore.class).issue(
                            TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
                    String path = "/biz/x/beauty/store-policies/versions";
                    String body = "{\"expectedRevision\":0,\"validFrom\":\"2026-10-20T18:00:00+08:00\","
                            + "\"policy\":{\"bookingAheadDays\":7,\"cancelRule\":{\"freeCancelBeforeMinutes\":60,"
                            + "\"allowCustomerCancel\":true},\"allowedModes\":[\"PREPAID_SINGLE\"]}}";
                    String first = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "HTTP-K").header("X-Actor-No", "spoof")
                            .contentType("application/json").content(body)).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    String replay = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "HTTP-K").contentType("application/json")
                            .content(body.replace("18:00:00+08:00", "10:00:00Z")))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    assertThat(replay).isEqualTo(first);
                    String effective = mvc.perform(get("/biz/x/beauty/store-policies")
                            .header("Authorization", "Bearer " + token).param("storeNo", "S")
                            .param("at", "2026-10-20T18:00:00+08:00"))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    assertThat(new ObjectMapper().readTree(effective).get("effectiveRevision").asLong()).isOne();
                    mvc.perform(get("/biz/x/beauty/store-policies")
                            .header("Authorization", "Bearer " + token).param("storeNo", "OTHER"))
                            .andExpect(status().isForbidden());

                    mvc.perform(post(path).header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "BAD-STORE").header("X-Store-No", "FOREIGN")
                            .contentType("application/json").content(body)).andExpect(status().isForbidden());
                    for (String table : List.of("bty_store_policy", "bty_store_policy_version", "bty_action_log",
                            "sys_idempotent", "sys_outbox")) {
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                                .as(table).isOne();
                    }
                    assertThat(sql.queryForObject("SELECT operator_ref FROM bty_action_log", String.class))
                            .isEqualTo("staff");
                    assertThat(sql.queryForObject("SELECT created_by FROM bty_store_policy_version", String.class))
                            .isEqualTo("staff");
                });
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BizException.class)
        ResponseEntity<Void> forbidden(BizException error) { return ResponseEntity.status(403).build(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Host {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean AuditMetaObjectHandler audit() { return new AuditMetaObjectHandler(); }
        @Bean MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean Errors errors() { return new Errors(); }
        @Bean("perm") PermChecker perm(ObjectProvider<LivePermResolver> resolver) { return new PermChecker(resolver); }
        @Bean StoreDirectoryPort stores() {
            var stores = mock(StoreDirectoryPort.class);
            when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                    "S", "S", "E", null, "ACTIVE", true, null, null, null, null, 0, 0)));
            return stores;
        }
        @Bean UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:BEAUTY_STORE_POLICY_VERSION_PUBLISHED")); // Test only.
            return facade;
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, store) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("S"), "S", false, Map.of(), Map.of("S", Set.of("biz:store"))));
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
