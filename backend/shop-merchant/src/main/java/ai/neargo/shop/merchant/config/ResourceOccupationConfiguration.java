package ai.neargo.shop.merchant.config;

import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.mapper.ResourceOccupationLifecycleMapper;
import ai.neargo.shop.merchant.service.ResourceOccupationLifecycleService;
import ai.neargo.shop.merchant.service.impl.ResourceOccupationLifecycleServiceImpl;
import ai.neargo.shop.merchant.port.ResourceOccupationLifecyclePortImpl;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldUnitMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationSegmentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationUnitMapper;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourceStartAccess;
import ai.neargo.shop.merchant.port.ResourceOccupationPortImpl;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceOccupationService;
import ai.neargo.shop.merchant.service.impl.ResourceOccupationServiceImpl;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** HUMAN orchestration uses the installed embedded or remote source owner under the Beauty feature gate. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class ResourceOccupationConfiguration {
    @Bean("resourceOccupationClock") Clock resourceOccupationClock() { return Clock.systemUTC(); }
    @Bean ResourceOccupationService resourceOccupationService(ServiceTaskStartAuthorityPort sources,
            ServiceExecutionEvidencePort core, ExecutionStockRetentionPort retention, ResourceExecutionEvidencePort evidence,
            ResourceCommandService commands, ResourceAdmissionService admission, ResourceStartAccess access,
            ResourceOccupationGuard guard, BookingOwnerMapper owners, BookingHoldMapper holds,
            BookingHoldUnitMapper bookingUnits, ResourceBucketMapper buckets, ResourceOccupationMapper occupations,
            ResourceOccupationUnitMapper units, ResourceOccupationSegmentMapper segments, ResourceOccupationBucketMapper projections,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("resourceOccupationClock") Clock clock) {
        return new ResourceOccupationServiceImpl(sources, core, retention, evidence, commands, admission, access, guard,
                owners, holds, bookingUnits, buckets, occupations, units, segments, projections, manager, clock);
    }
    @Bean ResourceOccupationPortImpl resourceOccupationPort(ResourceOccupationService service) {
        return new ResourceOccupationPortImpl(service);
    }
    @Bean ResourceOccupationLifecycleService resourceOccupationLifecycleService(ServiceTaskWorkflowAuthorityPort sources, ServiceTaskStartAuthorityPort starts,
            ResourceExecutionEvidencePort evidence, ResourceOccupationGuard guard, ResourceStartAccess access,
            ResourceAdmissionService admission, BookingOwnerMapper owners, ResourceCommandMapper commands,
            ResourceBucketMapper buckets, ResourceOccupationSegmentMapper segments, ResourceOccupationBucketMapper projections,
            ResourceOccupationLifecycleMapper lifecycle, @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("resourceOccupationClock") Clock clock) {
        return new ResourceOccupationLifecycleServiceImpl(sources,starts,evidence,guard,access,admission,owners,commands,buckets,
                segments,projections,lifecycle,manager,clock);
    }
    @Bean ResourceOccupationLifecyclePortImpl resourceOccupationLifecyclePort(ResourceOccupationLifecycleService service) {
        return new ResourceOccupationLifecyclePortImpl(service);
    }
}
