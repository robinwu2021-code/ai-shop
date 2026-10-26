package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AuthorizationRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogScopeMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSnapshotMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSourceMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.RequestJournalMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncPolicyMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunItemMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunMapper;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.commerce.service.SyncPolicyService;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.CatalogPublicationServiceImpl;
import ai.neargo.shop.commerce.service.impl.CatalogScopeServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelAccountServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelAuthorizationServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelConnectionServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOperationServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelResourceMappingPortImpl;
import ai.neargo.shop.commerce.service.impl.CommerceCommands;
import ai.neargo.shop.commerce.service.impl.CommerceRecoveryServiceImpl;
import ai.neargo.shop.commerce.service.impl.MappingPersistence;
import ai.neargo.shop.commerce.service.impl.ProviderServiceImpl;
import ai.neargo.shop.commerce.service.impl.ResourceMappingServiceImpl;
import ai.neargo.shop.commerce.service.impl.ResourceMappingVerificationServiceImpl;
import ai.neargo.shop.commerce.service.impl.SyncPolicyServiceImpl;
import ai.neargo.shop.commerce.service.impl.SyncRunServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

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
    ChannelConnectionService channelConnectionService(
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            ObjectProvider<ChannelConnectionIdentityPort> authority) {
        return new ChannelConnectionServiceImpl(
                connections, revisions, accounts, profiles, commands, authority::getIfAvailable);
    }

    @Bean
    ProviderService providerService(
            ProviderMapper providers, ProductProfileMapper profiles, CommerceCommands commands) {
        return new ProviderServiceImpl(providers, profiles, commands);
    }

    @Bean
    ChannelAuthorizationService channelAuthorizationService(
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
            CommerceCommands commands) {
        return new ChannelOperationServiceImpl(operations, attempts, commands);
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
            SyncRunServiceImpl runs) {
        return new CommerceRecoveryServiceImpl(db, previews, runs);
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
