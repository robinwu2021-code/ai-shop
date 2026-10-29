package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.ImportGuardMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.OrderSourceMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.PartyMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.port.RetailChannelSourceReader;
import ai.neargo.shop.retail.commerce.service.RetailChannelOrderService;
import ai.neargo.shop.retail.commerce.service.impl.RetailChannelOrderServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.RetailProductSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Retail ingestion is opt-in and only installs when actual original evidence, historical and
 * physical owners exist.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.retail.commerce.config.RetailCommerceAutoConfiguration",
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceOrderEvidenceClientAutoConfiguration",
            "ai.neargo.shop.trade.config.TradeCoreAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {"shop.retail.commerce.enabled", "shop.retail.commerce.orders.enabled"},
        havingValue = "true")
@ConditionalOnBean({
    ChannelOrderSourceEvidencePort.class,
    RetailSelectionMapper.class,
    RetailProductSourcePort.class,
    MaterialCatalogQueryPort.class,
    IndustryChannelOrderPort.class,
    PlatformTransactionManager.class,
    ObjectMapper.class
})
@MapperScan(
        basePackageClasses = RetailChannelMappers.class,
        markerInterface = BaseMapper.class,
        sqlSessionTemplateRef = "sqlSessionTemplate")
public class RetailChannelOrderAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RetailChannelSourceReader.class)
    RetailChannelSourceReader retailChannelSourceReader(
            ChannelOrderSourceEvidencePort evidence,
            RetailSelectionMapper selections,
            RetailProductSourcePort products,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new RetailChannelSourceReader(
                evidence,
                selections,
                products,
                materials,
                json,
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(RetailChannelOrderService.class)
    RetailChannelOrderServiceImpl retailChannelOrderService(
            ImportGuardMapper guards,
            OrderSourceMapper sources,
            PartyMapper parties,
            RetailChannelSourceReader reader,
            @Lazy IndustryChannelOrderPort core,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            ObjectProvider<Clock> clocks,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new RetailChannelOrderServiceImpl(
                guards,
                sources,
                parties,
                reader,
                core,
                materials,
                json,
                clocks.getIfAvailable(Clock::systemUTC),
                manager);
    }
}
