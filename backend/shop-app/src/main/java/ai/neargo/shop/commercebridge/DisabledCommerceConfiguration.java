package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.service.StorePaySettingService;

import jakarta.servlet.http.*;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;

import javax.sql.DataSource;

/** A legacy API instance must not become a bypass during a mixed-version rollout. */
@Configuration(proxyBeanMethods = false)
@Profile("api")
@Conditional(DisabledCommerceConfiguration.Disabled.class)
class DisabledCommerceConfiguration {
    static final class Disabled implements Condition {
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"true"
                    .equalsIgnoreCase(context.getEnvironment().getProperty("commerce.enabled"));
        }
    }

    @Bean
    @DependsOnDatabaseInitialization
    DisabledAdmission disabledCommerceAdmission(DataSource ds, PlatformTransactionManager manager) {
        var stores = new ShopCommerceStoreAdapter(ds, manager);
        if (stores.hasPersistedNewStores())
            throw new IllegalStateException(
                    "STORE_V1 requires commerce.enabled on merchant API instances");
        return new DisabledAdmission(stores);
    }

    static final class DisabledAdmission
            implements BizContextFilter.Admission, StorePaySettingService.WriteAdmission {
        private final ShopCommerceStoreAdapter stores;
        private final CommerceRoutePolicy policy = new CommerceRoutePolicy();

        DisabledAdmission(ShopCommerceStoreAdapter stores) {
            this.stores = stores;
        }

        public boolean admit(
                LoginUser user,
                BizContext selected,
                HttpServletRequest req,
                HttpServletResponse response)
                throws IOException {
            var route =
                    policy.match(
                            req.getMethod(),
                            req.getRequestURI().substring(req.getContextPath().length()));
            String requested = req.getHeader(BizContextFilter.STORE_HEADER);
            if (route.lane() == CommerceRoutePolicy.Lane.ACCOUNT
                    && (requested == null
                            || requested.isBlank()
                            || requested.equals(selected.currentStoreNo()))) return true;
            try {
                if (!stores.affects(user, requested, selected.currentStoreNo(), route.pathStore()))
                    return true;
            } catch (RuntimeException unavailable) {
                // Missing model or authority data must never be treated as evidence of a legacy
                // store.
            }
            ShopCommerceEndContextAdapter.reject(response, AccessStatus.CHECK_UNAVAILABLE);
            return false;
        }

        public void require(String storeNo) {
            if (stores.current(new StoreRef("MAIN", storeNo)).model() != CommerceModel.LEGACY)
                throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        }
    }
}
