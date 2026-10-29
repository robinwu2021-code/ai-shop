package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AuthorizationRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogScopeMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSnapshotMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSourceMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.FulfillmentLineLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.FulfillmentLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.InboundEventMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.InboundReceiptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLineLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.RequestJournalMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SourceNoticeMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncPolicyMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunItemMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunMapper;
import ai.neargo.shop.commerce.mapper.CommerceStatisticsMappers.StatisticsMapper;
import ai.neargo.shop.commerce.port.ChannelConnectionIdentityRouter;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.ChannelStatisticsService;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.commerce.service.SyncPolicyService;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.CatalogPublicationServiceImpl;
import ai.neargo.shop.commerce.service.impl.CatalogScopeServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelAccountServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelAuthorizationServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelCatalogAuthorityServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelConnectionServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelFulfillmentServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelInboundServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOperationServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOrderDecisionService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderImportService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMaintenanceService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMappingResolver;
import ai.neargo.shop.commerce.service.impl.ChannelOrderServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOrderSourceEvidencePortImpl;
import ai.neargo.shop.commerce.service.impl.ChannelResourceMappingPortImpl;
import ai.neargo.shop.commerce.service.impl.ChannelStatisticsServiceImpl;
import ai.neargo.shop.commerce.service.impl.CommerceCommands;
import ai.neargo.shop.commerce.service.impl.CommerceRecoveryServiceImpl;
import ai.neargo.shop.commerce.service.impl.CommerceSupportServiceImpl;
import ai.neargo.shop.commerce.service.impl.HistoricalManagedMappingService;
import ai.neargo.shop.commerce.service.impl.ManagedMappingProjectionService;
import ai.neargo.shop.commerce.service.impl.MappingPersistence;
import ai.neargo.shop.commerce.service.impl.ProviderServiceImpl;
import ai.neargo.shop.commerce.service.impl.ResourceMappingServiceImpl;
import ai.neargo.shop.commerce.service.impl.ResourceMappingVerificationServiceImpl;
import ai.neargo.shop.commerce.service.impl.SourceNoticeServiceImpl;
import ai.neargo.shop.commerce.service.impl.SyncPolicyServiceImpl;
import ai.neargo.shop.commerce.service.impl.SyncRunServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelProductProfilePort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import ai.neargo.shop.spi.commerce.CommerceGateway;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashSet;

import javax.sql.DataSource;

/** 默认无装配、无迁移；核心仅依赖本域契约与原平台持久化能力。 */
@AutoConfiguration(afterName = "ai.neargo.shop.common.config.PrimaryPersistenceConfiguration")
@ConditionalOnProperty(prefix = "shop.commerce", name = "enabled", havingValue = "true")
@MapperScan(
        basePackages = "ai.neargo.shop.commerce.mapper",
        markerInterface = BaseMapper.class,
        sqlSessionTemplateRef = "sqlSessionTemplate")
public class CommerceCoreAutoConfiguration {
    @Bean
    CommerceSchemaReady commerceSchemaReady(
            @Qualifier("dataSource") DataSource source, Environment env) {
        return new CommerceSchemaValidator()
                .initialize(source, env.getProperty("shop.commerce.migration", "VALIDATE"));
    }

    @Bean
    CommerceCommands commerceCommands(
            CommerceSchemaReady ready,
            RequestJournalMapper journals,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new CommerceCommands(journals, manager, new ObjectMapper());
    }

    @Bean
    ChannelAccountService channelAccountService(
            AccountMapper accounts, ProductProfileMapper profiles, CommerceCommands commands) {
        return new ChannelAccountServiceImpl(accounts, profiles, commands);
    }

    @Bean
    ChannelConnectionServiceImpl channelConnectionService(
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            ObjectProvider<ChannelConnectionIdentityPort> authority,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            AuthorizationRevisionMapper grants) {
        return new ChannelConnectionServiceImpl(
                connections,
                revisions,
                accounts,
                profiles,
                commands,
                () -> ChannelConnectionIdentityRouter.available(authority.orderedStream().toList()),
                operations,
                attempts,
                grants);
    }

    @Bean
    ProviderServiceImpl providerService(
            ProviderMapper providers,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            ObjectProvider<ChannelProductProfilePort> protocols) {
        var service = new ProviderServiceImpl(providers, profiles, commands);
        service.protocols(() -> protocols.orderedStream().toList());
        return service;
    }

    @Bean
    @ConditionalOnProperty(name = "shop.commerce.mock.bootstrap.enabled", havingValue = "true")
    ApplicationRunner commerceMockProfileBootstrap(
            ProviderServiceImpl profiles,
            ObjectProvider<ChannelProductProfilePort> protocols,
            Environment env) {
        return arguments -> {
            if (!env.matchesProfiles("commerce-mock")
                    || env.matchesProfiles("prod", "production")
                    || !env.getProperty("shop.commerce.mock.enabled", Boolean.class, false))
                throw new IllegalStateException(
                        "Mock bootstrap requires an explicit non-production mock environment");
            for (var protocol : protocols.orderedStream().toList()) {
                var registration = protocol.registration();
                if (registration != null && registration.definition().mock())
                    profiles.registerMock(
                            registration, "BOOTSTRAP:" + PricingSnapshotHash.of(registration));
            }
        };
    }

    @Bean
    ChannelAuthorizationServiceImpl channelAuthorizationService(
            AccountMapper accounts,
            ProductProfileMapper profiles,
            ProviderMapper providers,
            AuthorizationRevisionMapper revisions,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            ObjectProvider<ChannelAuthorizationGateway> gateways) {
        return new ChannelAuthorizationServiceImpl(
                accounts,
                profiles,
                providers,
                revisions,
                operations,
                attempts,
                commands,
                gateways.orderedStream().toList());
    }

    @Bean
    ChannelOperationService channelOperationService(
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            SyncRunMapper runs,
            ResourceMappingMapper mappings) {
        return new ChannelOperationServiceImpl(operations, attempts, commands, runs, mappings);
    }

    @Bean
    CatalogPersistence catalogPersistence(
            CatalogScopeMapper scopes,
            CatalogSourceMapper sources,
            SyncPolicyMapper policies,
            CatalogSnapshotMapper snapshots,
            SyncRunMapper runs,
            SyncRunItemMapper items,
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            AuthorizationRevisionMapper authorizations,
            ProductProfileMapper profiles,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            ObjectProvider<ChannelSourceValidationPort> authority,
            ObjectProvider<ChannelCatalogSourcePort> owners,
            ObjectProvider<ChannelCatalogGateway> gateways) {
        return new CatalogPersistence(
                scopes,
                sources,
                policies,
                snapshots,
                runs,
                items,
                connections,
                revisions,
                accounts,
                authorizations,
                profiles,
                operations,
                attempts,
                commands,
                authority::getIfAvailable,
                owners.orderedStream().toList(),
                gateways.orderedStream().toList());
    }

    @Bean
    ChannelCatalogAuthorityService channelCatalogAuthorityService(
            CatalogPersistence data,
            ProviderMapper providers,
            ObjectProvider<ChannelConnectionIdentityPort> identity,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ChannelCatalogAuthorityServiceImpl(
                data,
                providers,
                () -> ChannelConnectionIdentityRouter.available(identity.orderedStream().toList()),
                manager,
                Clock.systemUTC());
    }

    @Bean
    CatalogScopeService catalogScopeService(CatalogPersistence db) {
        return new CatalogScopeServiceImpl(db);
    }

    @Bean
    CatalogPublicationServiceImpl catalogPublicationService(CatalogPersistence db) {
        return new CatalogPublicationServiceImpl(db);
    }

    @Bean
    SyncPolicyService syncPolicyService(CatalogPersistence db) {
        return new SyncPolicyServiceImpl(db);
    }

    @Bean
    SyncRunServiceImpl syncRunService(CatalogPersistence db) {
        return new SyncRunServiceImpl(db);
    }

    @Bean
    CommerceRecoveryService commerceRecoveryService(
            CatalogPersistence db,
            CatalogPublicationServiceImpl previews,
            SyncRunServiceImpl runs,
            ObjectProvider<ChannelRecoveryPort> workflows) {
        var service = new CommerceRecoveryServiceImpl(db, previews, runs);
        service.contributors(() -> workflows.orderedStream().toList());
        return service;
    }

    @Bean
    SourceNoticeServiceImpl sourceNoticeService(
            CatalogPersistence db,
            SourceNoticeMapper notices,
            ObjectProvider<CommerceRecoveryService> recovery) {
        return new SourceNoticeServiceImpl(db, notices, recovery::getObject);
    }

    @Bean
    CommerceSupportServiceImpl commerceSupportService(
            CatalogPersistence db,
            ChannelStatisticsService statistics,
            ChannelOperationService operations) {
        return new CommerceSupportServiceImpl(db, statistics, operations);
    }

    @Bean
    ChannelStatisticsServiceImpl channelStatisticsService(
            StatisticsMapper mapper,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<CommerceGateway> gateways,
            Environment env,
            ObjectProvider<ChannelCatalogSourcePort> catalogSources,
            ObjectProvider<ChannelSourceValidationProvider> validations,
            ObjectProvider<ChannelOrderIngestPort> orderOwners,
            ObjectProvider<ChannelFulfillmentSourcePort> fulfillmentOwners) {
        var service =
                new ChannelStatisticsServiceImpl(
                        mapper,
                        manager,
                        Clock.systemUTC(),
                        () -> gateways.orderedStream().toList(),
                        env.getProperty(
                                "shop.commerce.health.backlog-warning-seconds", Long.class, 300L));
        service.sources(
                () -> {
                    var available = new HashSet<String>();
                    catalogSources
                            .orderedStream()
                            .forEach(source -> available.add("CATALOG:" + source.owner()));
                    validations
                            .orderedStream()
                            .forEach(source -> available.add("VALIDATION:" + source.owner()));
                    orderOwners
                            .orderedStream()
                            .forEach(source -> available.add("ORDER:" + source.owner()));
                    fulfillmentOwners
                            .orderedStream()
                            .forEach(source -> available.add("FULFILLMENT:" + source.owner()));
                    return available;
                });
        return service;
    }

    @Bean
    ChannelOrderServiceImpl channelOrderService(
            CatalogPersistence db,
            OrderLinkMapper orders,
            OrderRevisionMapper revisions,
            OrderLineLinkMapper lineLinks,
            InboundEventMapper events,
            ObjectProvider<ChannelOrderGateway> gateways,
            ObjectProvider<ChannelOrderDecisionService> decisions,
            ObjectProvider<ChannelOrderMaintenanceService> maintenance) {
        var service =
                new ChannelOrderServiceImpl(
                        db,
                        orders,
                        revisions,
                        events,
                        gateways.orderedStream().toList(),
                        Clock.systemUTC(),
                        lineLinks);
        service.decisions(decisions::getObject);
        service.maintenance(maintenance::getObject);
        return service;
    }

    @Bean
    ChannelFulfillmentServiceImpl channelFulfillmentService(
            ChannelOrderServiceImpl orders,
            FulfillmentLinkMapper links,
            FulfillmentLineLinkMapper lines,
            ObjectProvider<ChannelFulfillmentSourcePort> sources,
            ObjectProvider<ChannelFulfillmentGateway> gateways) {
        return new ChannelFulfillmentServiceImpl(
                orders,
                links,
                lines,
                () -> sources.orderedStream().toList(),
                gateways.orderedStream().toList(),
                Clock.systemUTC());
    }

    @Bean
    ChannelOrderSourceEvidencePort channelOrderSourceEvidencePort(
            ChannelOrderServiceImpl data,
            ChannelOrderMaintenanceService maintenance,
            ProviderMapper providers,
            HistoricalManagedMappingService history) {
        return new ChannelOrderSourceEvidencePortImpl(data, maintenance, providers, history);
    }

    @Bean
    ChannelOrderMaintenanceService channelOrderMaintenanceService(ChannelOrderServiceImpl data) {
        return new ChannelOrderMaintenanceService(data, Clock.systemUTC());
    }

    @Bean
    ChannelOrderDecisionService channelOrderDecisionService(
            ChannelOrderServiceImpl data, ObjectProvider<ChannelOrderIngestPort> owners) {
        return new ChannelOrderDecisionService(data, () -> owners.orderedStream().toList());
    }

    @Bean
    ChannelOrderMappingResolver channelOrderMappingResolver(
            MappingPersistence data,
            ChannelResourceMappingPort authority,
            HistoricalManagedMappingService history) {
        return new ChannelOrderMappingResolver(data, authority, history);
    }

    @Bean
    HistoricalManagedMappingService historicalManagedMappingService(MappingPersistence data) {
        return new HistoricalManagedMappingService(data, Clock.systemUTC());
    }

    @Bean
    ChannelOrderImportService channelOrderImportService(
            ChannelOrderServiceImpl data,
            ChannelOrderMappingResolver mappings,
            ObjectProvider<ChannelOrderIngestPort> owners) {
        return new ChannelOrderImportService(
                data, mappings, () -> owners.orderedStream().toList(), Clock.systemUTC());
    }

    @Bean
    ChannelInboundServiceImpl channelInboundService(
            CatalogPersistence db,
            InboundEventMapper events,
            InboundReceiptMapper receipts,
            ProviderMapper providers,
            ObjectProvider<ChannelInboundGateway> gateways,
            ObjectProvider<ChannelInboundHandler> handlers,
            Environment environment) {
        var service =
                new ChannelInboundServiceImpl(
                        db,
                        events,
                        receipts,
                        providers,
                        gateways.orderedStream().toList(),
                        environment.getProperty(
                                "shop.commerce.callbacks.raw-retention-days", Integer.class, 30));
        service.handlers(() -> handlers.orderedStream().toList());
        return service;
    }

    @Bean
    MappingPersistence mappingPersistence(
            CatalogPersistence db,
            ResourceMappingMapper mappings,
            ResourceMappingRevisionMapper revisions,
            ObjectProvider<ChannelResourceIdentityGateway> gateways) {
        return new MappingPersistence(db, mappings, revisions, gateways.orderedStream().toList());
    }

    @Bean
    ManagedMappingProjectionService managedMappingProjectionService(MappingPersistence data) {
        return new ManagedMappingProjectionService(data);
    }

    @Bean
    ResourceMappingService resourceMappingService(MappingPersistence data) {
        return new ResourceMappingServiceImpl(data);
    }

    @Bean
    ResourceMappingVerificationService resourceMappingVerificationService(MappingPersistence data) {
        return new ResourceMappingVerificationServiceImpl(data);
    }

    @Bean
    ChannelResourceMappingPort channelResourceMappingPort(MappingPersistence data) {
        return new ChannelResourceMappingPortImpl(data);
    }
}
