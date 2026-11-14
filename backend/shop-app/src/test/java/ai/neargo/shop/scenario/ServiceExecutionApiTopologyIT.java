package ai.neargo.shop.scenario;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.shop.job.JobHandlerRegistry;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual embedded API composition with real owners and the production registry, without bean overriding. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@ActiveProfiles({"test", "api"})
@TestPropertySource(properties = {"shop.job.enabled=true", "spring.main.allow-bean-definition-overriding=false"})
class ServiceExecutionApiTopologyIT extends ManualSourceHostFixture {
    @Autowired JobHandlerRegistry registry;
    @Autowired ServiceExecutionPort quantity;
    @Autowired ServiceExecutionSourcePort source;

    @Test
    void eachOwnerHasOneDistinctHandlerDeclarationAndActualProvider() {
        assertPair("coreServiceExecutionRecoveryJob", "coreServiceExecutionRecoveryDeclaration",
                "service-execution-recovery", "ai.neargo.shop.trade.job.ServiceExecutionRecoveryJob");
        assertPair("beautyServiceExecutionRecoveryJob", "beautyServiceExecutionRecoveryDeclaration",
                "beauty-service-execution-recovery", "ai.neargo.shop.beauty.job.ServiceExecutionRecoveryJob");
        assertThat(web.containsBean("serviceExecutionRecoveryJob")).isFalse();
        assertThat(web.getBean("serviceExecutionSchemaVerifier").getClass().getName())
                .isEqualTo("ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier");
        assertThat(web.getBean("beautyServiceExecutionSchemaVerifier").getClass().getName())
                .isEqualTo("ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier");
        assertThat(AopUtils.getTargetClass(quantity).getName())
                .isEqualTo("ai.neargo.shop.trade.port.ServiceExecutionPortImpl");
        assertThat(AopUtils.getTargetClass(source).getName())
                .isEqualTo("ai.neargo.shop.beauty.port.ServiceExecutionSourcePortImpl");
    }

    private void assertPair(String beanName, String declarationName, String logicalName, String ownerType) {
        var handler = web.getBean(beanName, JobHandler.class);
        assertThat(AopUtils.getTargetClass(handler).getName()).isEqualTo(ownerType);
        assertThat(web.getBeansOfType(JobHandler.class).values().stream()
                .filter(h -> logicalName.equals(h.name())).count()).isEqualTo(1L);
        var declaration = web.getBean(declarationName, JobDeclaration.class);
        assertThat(declaration.handlerName()).isEqualTo(logicalName);
        assertThat(declaration.defaultCron()).isEqualTo("0 * * * * *");
        assertThat(declaration.timeoutSec()).isEqualTo(120);
        assertThat(declaration.lockAtMostSec()).isEqualTo(120);
        assertThat(registry.find(logicalName).orElseThrow()).isSameAs(handler);
        assertThat(registry.declarations().stream().filter(d -> logicalName.equals(d.handlerName())).count())
                .isEqualTo(1L);
    }
}
