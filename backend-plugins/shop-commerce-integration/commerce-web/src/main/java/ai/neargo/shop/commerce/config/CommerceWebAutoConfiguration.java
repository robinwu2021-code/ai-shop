package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.api.biz.BizCatalogPreviewController;
import ai.neargo.shop.commerce.api.biz.BizCatalogScopeController;
import ai.neargo.shop.commerce.api.biz.BizChannelAccountController;
import ai.neargo.shop.commerce.api.biz.BizChannelAuthorizationController;
import ai.neargo.shop.commerce.api.biz.BizChannelConnectionController;
import ai.neargo.shop.commerce.api.biz.BizChannelOperationController;
import ai.neargo.shop.commerce.api.biz.BizChannelProviderController;
import ai.neargo.shop.commerce.api.biz.BizResourceMappingController;
import ai.neargo.shop.commerce.api.biz.BizSyncPolicyController;
import ai.neargo.shop.commerce.api.biz.BizSyncRunController;
import ai.neargo.shop.commerce.api.callback.ChannelAuthorizationCallbackController;
import ai.neargo.shop.commerce.job.CommerceRecoveryJob;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** 无core实现依赖；集成形态通过api契约注入，独立远程形态尚不开放。 */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Import({
    BizChannelAccountController.class,
    BizChannelConnectionController.class,
    BizChannelProviderController.class,
    BizChannelAuthorizationController.class,
    BizChannelOperationController.class,
    BizCatalogScopeController.class,
    BizCatalogPreviewController.class,
    BizSyncPolicyController.class,
    BizSyncRunController.class,
    BizResourceMappingController.class,
    CommerceRecoveryJob.class,
    ChannelAuthorizationCallbackController.class
})
public class CommerceWebAutoConfiguration {
    @Bean
    CommerceRequestScope commerceRequestScope(StoreDirectoryPort stores) {
        return new CommerceRequestScope(stores);
    }
}
