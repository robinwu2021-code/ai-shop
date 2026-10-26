package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.RequestJournalMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.commerce.service.impl.ChannelAccountServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelConnectionServiceImpl;
import ai.neargo.shop.commerce.service.impl.CommerceCommands;
import ai.neargo.shop.commerce.service.impl.ProviderServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;

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
}
