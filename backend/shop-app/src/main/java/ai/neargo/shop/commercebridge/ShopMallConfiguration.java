package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.fulfillment.PaymentFacade;
import ai.neargo.commerce.api.mall.RefundFacade;
import ai.neargo.commerce.api.port.CheckoutReadinessPort;
import ai.neargo.commerce.api.port.OfflineCollectionPort;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.commercebridge.port.ShopMallPaymentRecovery;
import ai.neargo.shop.config.MallOperatorSessions;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.service.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.time.Clock;
import java.util.*;

@Configuration(proxyBeanMethods = false)
// api serves the merchant mall routes, ops the operator routes (see commerce.mall.http.* in
// application.yml per profile). Both need the same trusted host ports.
@Profile({"api", "ops"})
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.http.enabled"},
        havingValue = "true")
@EnableConfigurationProperties(ShopMallProperties.class)
class ShopMallConfiguration {
    @Bean
    @ConditionalOnMissingBean(ai.neargo.commerce.api.port.MoneyPolicyPort.class)
    ai.neargo.commerce.api.port.MoneyPolicyPort mallMoneyPolicy() {
        return (tenant, currency) -> {
            if (!"MAIN".equals(tenant)) return Optional.empty();
            return switch (currency) {
                case "TWD" -> Optional.of(new ai.neargo.commerce.api.common.MoneyPolicy("twd-whole-v1", "TWD", 2, 100, 100));
                case "CNY" -> Optional.of(ai.neargo.commerce.api.common.MoneyPolicy.legacy("CNY"));
                default -> Optional.empty();
            };
        };
    }

    @Bean
    @ConditionalOnMissingBean(ShopMallPayerBindings.class)
    ShopMallPayerBindings mallPayerBindings() {
        return ShopMallPayerBindings.UNAVAILABLE;
    }

    @Bean
    ShopMallAuthority mallAuthority(
            AccountSessionVerifier buyers,
            MallOperatorSessions operators,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores,
            ShopMallPayerBindings bindings) {
        return new ShopMallAuthority(
                buyers, operators.verifier(), resolver, stores, Clock.systemUTC(), bindings);
    }

    /** Merchant plan cards only (/biz); the ops instance has no merchant foundation reads. */
    @Bean
    @Profile("api")
    @ConditionalOnProperty(prefix = "commerce.mall", name = "enabled", havingValue = "true")
    ai.neargo.commerce.api.foundation.FoundationPurchaseAvailabilityPort mallFoundationAvailability(
            ai.neargo.commerce.api.foundation.FoundationAuthorityPort foundation,
            ShopMallAuthority authority,
            ai.neargo.commerce.api.mall.StorefrontFacade storefront) {
        return new ShopMallFoundationAvailability(foundation, authority, storefront);
    }

    @Bean
    ShopMallEnvelope mallEnvelope() {
        return new ShopMallEnvelope();
    }

    @Bean
    ShopMallDeploymentRegistry mallDeployments(ShopMallProperties settings) {
        return new ShopMallDeploymentRegistry(
                settings.acceptedDeployments(), settings.requiredEnds());
    }

    @Bean
    ShopMallHardwareInventoryAdapter mallInventory(
            ObjectProvider<CoordinatedInventoryService> inventory, ShopMallProperties settings) {
        return new ShopMallHardwareInventoryAdapter(
                inventory.getIfAvailable(), settings.inventoryMappings());
    }

    @Bean
    CheckoutReadinessPort mallCheckoutReadiness(
            ShopMallHardwareInventoryAdapter inventory,
            ShopMallPayerBindings bindings,
            ObjectProvider<PayGateway> gateways,
            @Value("${commerce.mall.payment.enabled:false}") boolean enabled,
            @Value("${commerce.mall.payment.platform-merchant-no:}") String recipient,
            @Value("${commerce.mall.payment.routes:}") String routes,
            @Value("${commerce.mall.offline.enabled:false}") boolean offlineEnabled) {
        Set<String> allowed = new HashSet<>();
        for (String route : routes.split(",")) allowed.add(route.trim());
        // Capabilities read local configuration/certificate availability only, never provider IO.
        var options =
                gateways.orderedStream()
                        .flatMap(
                                g ->
                                        g.platformCollectionCapability().stream()
                                                .filter(
                                                        c ->
                                                                c.merchantNo().equals(recipient)
                                                                        && allowed.contains(
                                                                                g.payChannel()
                                                                                        + ":"
                                                                                        + c
                                                                                                .method()))
                                                .map(
                                                        c ->
                                                                new CheckoutReadinessPort
                                                                        .PaymentOption(
                                                                        g.payChannel(),
                                                                        c.method(),
                                                                        c.currency(),
                                                                        c.maximumMinor())))
                        .toList();
        boolean offline = enabled && offlineEnabled && allowed.contains("OFFLINE:BANK_TRANSFER");
        return new ShopMallCheckoutReadiness(
                enabled, inventory, () -> options, bindings, offline, recipient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce.mall.payment", name = "enabled", havingValue = "true")
    ShopMallPlatformPaymentAdapter mallPayments(
            PlatformCollectionService collection,
            ObjectProvider<PlatformRefundService> refunds,
            ShopMallPayerBindings bindings) {
        return new ShopMallPlatformPaymentAdapter(collection, refunds.getIfAvailable(), bindings);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "commerce.mall",
            name = {"payment.enabled", "offline.enabled"},
            havingValue = "true")
    OfflineCollectionPort mallOfflineCollection(
            PlatformCollectionService collection,
            ObjectProvider<PlatformRefundService> refunds,
            @Value("${commerce.mall.payment.platform-merchant-no}") String recipient) {
        return new ShopMallOfflineCollectionAdapter(collection, refunds.getIfAvailable(), recipient);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "commerce.mall",
            name = {"payment.enabled", "offline.enabled"},
            havingValue = "true")
    PrivateEvidencePort mallOfflineEvidence(
            MediaStore media,
            MediaUploadService uploads,
            SysMediaAssetMapper assets) {
        return new ShopMallPrivateEvidenceAdapter(media, uploads, assets, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce.mall.payment", name = "enabled", havingValue = "true")
    ShopMallPaymentRecovery mallPaymentRecovery(
            PlatformCollectionService collection,
            ObjectProvider<PlatformRefundService> refunds,
            PlatformMoneyHandoffService handoffs,
            PaymentFacade payments,
            ObjectProvider<RefundFacade> coreRefunds) {
        return new ShopMallPaymentRecovery(
                collection,
                refunds.getIfAvailable(),
                handoffs,
                payments,
                coreRefunds.getIfAvailable());
    }
}
