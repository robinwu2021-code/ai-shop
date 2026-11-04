package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.starter.http.*;

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
            CommerceRefundAutoConfiguration.class
        })
@ConditionalOnClass(
        name = {"org.springframework.web.servlet.DispatcherServlet", "jakarta.servlet.Servlet"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.http.enabled"},
        havingValue = "true")
public class CommerceMallHttpAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "commerce", name = "catalog.enabled", havingValue = "true")
    CommerceCatalogHttpController commerceCatalogHttpController(
            CatalogFacade catalog, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
        return new CommerceCatalogHttpController(catalog, context, envelope);
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce", name = "mall.enabled", havingValue = "true")
    CommerceBuyerMallHttpController commerceBuyerMallHttpController(
            ai.neargo.commerce.api.mall.MallFacade mall,
            ai.neargo.commerce.api.mall.StorefrontFacade storefront,
            ai.neargo.commerce.api.mall.MallQueryFacade queries,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.fulfillment.PaymentFacade>
                    payments,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.mall.RefundFacade>
                    refunds,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        return new CommerceBuyerMallHttpController(
                mall,
                storefront,
                queries,
                payments.getIfAvailable(),
                refunds.getIfAvailable(),
                context,
                envelope);
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce", name = "mall.enabled", havingValue = "true")
    CommerceOpsMallHttpController commerceOpsMallHttpController(
            ai.neargo.commerce.api.mall.MallQueryFacade queries,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.fulfillment.FulfillmentFacade>
                    fulfillment,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.mall.RefundFacade>
                    refunds,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        return new CommerceOpsMallHttpController(
                queries, refunds.getIfAvailable(), fulfillment.getIfAvailable(), context, envelope);
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce", name = "catalog.enabled", havingValue = "true")
    CommerceSourceHttpController commerceSourceHttpController(
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.subscription.SourceManagementFacade>
                    sources,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        return new CommerceSourceHttpController(sources.getIfAvailable(), context, envelope);
    }
}
