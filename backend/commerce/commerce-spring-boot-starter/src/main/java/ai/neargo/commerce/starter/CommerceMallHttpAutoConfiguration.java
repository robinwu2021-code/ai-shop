package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.starter.http.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Optional HTTP translation only. Payment and refund coordination are separate opt-ins. */
@AutoConfiguration(
        after = {
            CommerceCatalogAutoConfiguration.class,
            CommerceSourceManagementAutoConfiguration.class,
            CommerceMallQueryAutoConfiguration.class,
            CommerceFulfillmentAutoConfiguration.class,
            CommerceRefundAutoConfiguration.class,
            CommerceOfflineAutoConfiguration.class
        })
@ConditionalOnClass(
        name = {"org.springframework.web.servlet.DispatcherServlet", "jakarta.servlet.Servlet"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.http.enabled"},
        havingValue = "true")
public class CommerceMallHttpAutoConfiguration {
    /**
     * Merchant surface (/biz/stores/{storeNo}/mall/**). A host that splits public and internal
     * deployments turns it off on the internal instance with
     * {@code commerce.mall.http.buyer.enabled=false}, so those routes do not exist there.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "commerce.mall.http.buyer",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class Buyer {
        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "mall.enabled", havingValue = "true")
        CommerceBuyerMallHttpController commerceBuyerMallHttpController(
                ai.neargo.commerce.api.mall.MallFacade mall,
                ai.neargo.commerce.api.mall.MallQueryFacade queries,
                ObjectProvider<ai.neargo.commerce.api.fulfillment.PaymentFacade> payments,
                ObjectProvider<ai.neargo.commerce.api.mall.RefundFacade> refunds,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceBuyerMallHttpController(
                    mall,
                    queries,
                    payments.getIfAvailable(),
                    refunds.getIfAvailable(),
                    context,
                    envelope);
        }

        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "mall.enabled", havingValue = "true")
        CommerceStorefrontHttpController commerceStorefrontHttpController(
                ai.neargo.commerce.api.mall.StorefrontFacade storefront,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceStorefrontHttpController(storefront, context, envelope);
        }

        @Bean
        @ConditionalOnBean(ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class)
        CommerceOfflineBuyerHttpController commerceOfflineBuyerHttpController(
                ai.neargo.commerce.api.offline.OfflinePurchaseFacade offline,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceOfflineBuyerHttpController(offline, context, envelope);
        }
    }

    /**
     * Operator surface (/ops/**: catalog, orders, subscription sources, offline review). A host
     * turns it off on its public instance with {@code commerce.mall.http.operator.enabled=false}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "commerce.mall.http.operator",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class Operator {
        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "catalog.enabled", havingValue = "true")
        CommerceCatalogHttpController commerceCatalogHttpController(
                CatalogFacade catalog, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
            return new CommerceCatalogHttpController(catalog, context, envelope);
        }

        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "catalog.enabled", havingValue = "true")
        CommerceMallProductHttpController commerceMallProductHttpController(
                CatalogFacade catalog, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
            return new CommerceMallProductHttpController(catalog, context, envelope);
        }

        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "mall.enabled", havingValue = "true")
        CommerceOpsMallHttpController commerceOpsMallHttpController(
                ai.neargo.commerce.api.mall.MallQueryFacade queries,
                ObjectProvider<ai.neargo.commerce.api.fulfillment.FulfillmentFacade> fulfillment,
                ObjectProvider<ai.neargo.commerce.api.mall.RefundFacade> refunds,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceOpsMallHttpController(
                    queries, refunds.getIfAvailable(), fulfillment.getIfAvailable(), context, envelope);
        }

        @Bean
        @ConditionalOnProperty(prefix = "commerce", name = "catalog.enabled", havingValue = "true")
        CommerceSourceHttpController commerceSourceHttpController(
                ObjectProvider<ai.neargo.commerce.api.subscription.SourceManagementFacade> sources,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceSourceHttpController(sources.getIfAvailable(), context, envelope);
        }

        @Bean
        @ConditionalOnBean(ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class)
        CommerceOfflineOpsHttpController commerceOfflineOpsHttpController(
                ai.neargo.commerce.api.offline.OfflinePurchaseFacade offline,
                ai.neargo.commerce.api.mall.MallFacade mall,
                MallHttpContextPort context,
                MallHttpEnvelopePort envelope) {
            return new CommerceOfflineOpsHttpController(offline, mall, context, envelope);
        }
    }
}
