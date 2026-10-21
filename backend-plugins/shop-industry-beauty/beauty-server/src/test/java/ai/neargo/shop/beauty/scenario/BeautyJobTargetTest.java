package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobHttpPaths;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.beauty.server.api.BeautyJobHandlerEndpoint;
import ai.neargo.shop.beauty.server.config.BeautyInternalSecurityConfiguration;
import ai.neargo.shop.beauty.server.config.BeautyJobTargetConfiguration;
import ai.neargo.shop.beauty.server.config.BeautyServerConfiguration;
import ai.neargo.shop.job.JobHandlerRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Real HTTP mapping/security/registry/DB lock seam for a service without shop-app. */
class BeautyJobTargetTest {
    @Test void disabledServerDoesNotRequireDatabaseForAnInactiveJobTarget() {
        new ApplicationContextRunner().withUserConfiguration(BeautyServerConfiguration.class,
                        BeautyJobTargetConfiguration.class, BeautyJobHandlerEndpoint.class)
                .withPropertyValues("shop.beauty.enabled=false", "shop.job.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(DataSource.class));
    }

    @Test void nonApiShapesDoNotExposeAnInertJobEndpoint() {
        for (String profile : new String[] {"ops", ""}) {
            new ApplicationContextRunner().withUserConfiguration(BeautyJobTargetConfiguration.class,
                            BeautyJobHandlerEndpoint.class)
                    .withPropertyValues("shop.beauty.enabled=true", "shop.job.enabled=true",
                            "spring.profiles.active=" + profile)
                    .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(JobHandlerRegistry.class)
                            .doesNotHaveBean(BeautyJobHandlerEndpoint.class));
        }
    }
    @Test void oneFrameworkRouteSetRequiresJobTokenAndRunsWithSharedDatabaseLock() throws Exception {
        try (var ctx = new AnnotationConfigWebApplicationContext()) {
            ctx.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, "shop.job.enabled=true",
                    "shop.beauty.enabled=true", "spring.profiles.active=api",
                    "shop.job.internal-token=job-secret", "shop.services.internal-token=service-secret");
            ctx.register(Web.class, BeautyInternalSecurityConfiguration.class, BeautyJobHandlerEndpoint.class,
                    BeautyJobTargetConfiguration.class);
            ctx.refresh();
            var jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
            jdbc.execute("CREATE TABLE shedlock(name VARCHAR(64) PRIMARY KEY, lock_until TIMESTAMP(3), "
                    + "locked_at TIMESTAMP(3), locked_by VARCHAR(255))");
            var mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean(FilterChainProxy.class)).build();
            var mappings = ctx.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet();
            assertThat(mappings.stream().filter(m -> m.getPatternValues().contains(JobHttpPaths.DECLARATIONS)))
                    .hasSize(1);
            assertThat(mappings.stream().filter(m -> m.getPatternValues().contains(JobHttpPaths.RUN))).hasSize(1);
            mvc.perform(get(JobHttpPaths.DECLARATIONS)).andExpect(status().isUnauthorized());
            mvc.perform(get(JobHttpPaths.DECLARATIONS).header(JobHttpPaths.TOKEN_HEADER, "service-secret"))
                    .andExpect(status().isUnauthorized());
            var declared = mvc.perform(get(JobHttpPaths.DECLARATIONS).header(JobHttpPaths.TOKEN_HEADER, "job-secret"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(declared).startsWith("[").contains("probe");
            mvc.perform(post(JobHttpPaths.RUN, "probe").header(JobHttpPaths.TOKEN_HEADER, "job-secret"))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock WHERE name='probe'", Integer.class))
                    .isEqualTo(1);
            jdbc.update("UPDATE shedlock SET lock_until=DATEADD('MINUTE', 2, CURRENT_TIMESTAMP)");
            mvc.perform(post(JobHttpPaths.RUN, "probe").header(JobHttpPaths.TOKEN_HEADER, "job-secret"))
                    .andExpect(status().isConflict());
        }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Web {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:beauty-job-target;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JobDeclaration declaration() {
            return new JobDeclaration("probe", "Probe", "Target contract", "test", "0 * * * * *",
                    true, 60, 120, true, true);
        }
        @Bean JobHandler handler() {
            return new JobHandler() {
                @Override public String name() { return "probe"; }
                @Override public JobResult run(JobInvocation in) { return JobResult.ok("executed"); }
            };
        }
    }
}
