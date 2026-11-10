package ai.neargo.shop.beauty.scenario;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Read suites load the full source configuration, but must never invoke actual execution owners. */
@Configuration(proxyBeanMethods = false)
class BillReadExecutionBoundaryFixture {
    private <T> T forbidden(Class<T> type) {
        return mock(type, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) return RETURNS_DEFAULTS.answer(invocation);
            throw new AssertionError("Bill/settlement read invoked execution owner: " + type.getSimpleName());
        });
    }
    @Bean @ConditionalOnMissingBean(ResourceOccupationPort.class)
    ResourceOccupationPort readOnlyNativeOccupation() { return forbidden(ResourceOccupationPort.class); }
    @Bean @ConditionalOnMissingBean(ResourceExecutionEvidencePort.class)
    ResourceExecutionEvidencePort readOnlyNativeEvidence() { return forbidden(ResourceExecutionEvidencePort.class); }
    @Bean @ConditionalOnMissingBean(ResourceQualificationPort.class)
    ResourceQualificationPort readOnlyQualification() { return forbidden(ResourceQualificationPort.class); }
    @Bean @ConditionalOnMissingBean(ServiceExecutionStartPort.class)
    ServiceExecutionStartPort readOnlyCoreStart() { return forbidden(ServiceExecutionStartPort.class); }
    @Bean @ConditionalOnMissingBean(ServiceExecutionEvidencePort.class)
    ServiceExecutionEvidencePort readOnlyCoreEvidence() { return forbidden(ServiceExecutionEvidencePort.class); }
}
