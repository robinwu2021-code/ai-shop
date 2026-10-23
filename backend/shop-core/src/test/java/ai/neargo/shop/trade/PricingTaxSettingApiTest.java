package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.api.biz.BizPricingTaxSettingController;
import ai.neargo.shop.trade.api.ops.OpsPricingTaxSettingController;
import ai.neargo.shop.trade.dto.PricingTaxSettingRequests;
import ai.neargo.shop.trade.policy.PricingAccessPolicy;
import ai.neargo.shop.trade.policy.PricingTaxAccessPolicy;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real access policy and method security; per-store grants must not become all-store write authority. */
class PricingTaxSettingApiTest {
    private AnnotationConfigApplicationContext context;
    private PricingTaxSettingService service;
    private StoreDirectoryPort stores;
    private OpsPricingTaxSettingController ops;
    private BizPricingTaxSettingController biz;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(PricingTaxSettingService.class);
        stores = context.getBean(StoreDirectoryPort.class);
        ops = context.getBean(OpsPricingTaxSettingController.class);
        biz = context.getBean(BizPricingTaxSettingController.class);
        when(stores.cards(List.of("S1"))).thenReturn(Map.of("S1", new StoreDirectoryPort.StoreCard(
                "S1", "Store", "M1", "", "ACTIVE", true, "", "", null, null, 0, 0)));
    }

    @AfterEach
    void clear() {
        context.close();
        SecurityContextHolder.clearContext();
        BizContext.clear();
    }

    @Test
    void opsReadDoesNotGrantWriteAndTemplatePermissionDoesNotGrantTaxAccess() {
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(), List.of(Perms.PRICING_TAX_READ)));
        ops.get();
        verify(service).getPlatform(any());
        assertThatThrownBy(() -> ops.save(save(), "K")).isInstanceOf(AccessDeniedException.class);
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(),
                List.of(Perms.PRICING_TEMPLATE_UPDATE)));
        assertThatThrownBy(() -> ops.save(save(), "K")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void merchantStaffCannotWriteEvenWithExplicitStoreTaxWriteGrantOrWildcard() {
        for (String grant : List.of(BizPerms.PRICING_TAX_UPDATE, "*")) {
            merchant(false, grant);
            assertThatThrownBy(() -> biz.save(save(), "K")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> biz.reset(new PricingTaxSettingRequests.Reset(0), "K"))
                    .isInstanceOf(BizException.class);
        }
        verifyNoInteractions(service);
        assertThat(BizPerms.assignableCodes()).doesNotContain(BizPerms.PRICING_TAX_UPDATE);
    }

    @Test
    void ownerSavesAndResetsOwnMerchantWithoutAnyPublicationOrReviewEvidence() {
        merchant(true, "");
        biz.save(save(), "SAVE");
        biz.reset(new PricingTaxSettingRequests.Reset(1), "RESET");
        verify(service).saveMerchant(argThat(c ->
                c.entityNo().equals("M1") && c.storeNo().equals("S1") && c.actorNo().equals("ACT")),
                any(), eq("SAVE"));
        verify(service).resetMerchant(any(), any(), eq("RESET"));
    }

    @Test
    void authorizedStaffCanReadButForgedStoreOwnershipAndWrongRealmsCannotRead() {
        merchant(false, BizPerms.PRICING_TAX_READ);
        biz.get();
        verify(service).getMerchant(any());
        when(stores.cards(List.of("S1"))).thenReturn(Map.of());
        assertThatThrownBy(() -> biz.get()).isInstanceOf(BizException.class);
        PricingAccessPolicyTest.login(LoginUser.consumer("C", "Customer"));
        BizContext.clear();
        assertThatThrownBy(() -> biz.get()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void httpRejectsForgedScopeMissingKeysAndAmbiguousTaxTypesBeforeService() throws Exception {
        merchant(true, "");
        var mvc = MockMvcBuilders.standaloneSetup(biz).build();
        String valid = "{\"expectedVersion\":0,\"rateBp\":500,\"priceTaxMode\":\"EXCLUSIVE\"}";
        mvc.perform(put("/biz/pricing-tax-settings").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of(valid.replace("500", "500.9"), valid.replace("500", "\"500\""),
                valid.replace("500", "null"), valid.replace("500", "10001"),
                valid.replace("\"expectedVersion\":0,", ""), valid.replace(":0,", ":null,"),
                valid.replace("EXCLUSIVE", "INVALID"), valid.replace("\"EXCLUSIVE\"", "0"),
                valid.replace("}", ",\"entityNo\":\"OTHER\"}"))) {
            mvc.perform(put("/biz/pricing-tax-settings").header("Idempotency-Key", "K")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/biz/pricing-tax-settings/reset").header("Idempotency-Key", "K")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(put("/biz/pricing-tax-settings").header("Idempotency-Key", "K")
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
        verify(service).saveMerchant(any(), any(), eq("K"));
    }

    private PricingTaxSettingRequests.Save save() {
        return new PricingTaxSettingRequests.Save(0, 500, PriceTaxMode.EXCLUSIVE);
    }

    private void merchant(boolean owner, String permission) {
        PricingAccessPolicyTest.login(LoginUser.merchant("ACT", "B"));
        BizContext.set(new BizContext("M1", Set.of(), Set.of(), Set.of("S1"), "S1", owner,
                Map.of(), Map.of("S1", Set.of(permission))));
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "perm")
        public PricingConfigApiTest.TestPermissions permissions() { return new PricingConfigApiTest.TestPermissions(); }
        @Bean
        public PricingTaxSettingService service() { return mock(PricingTaxSettingService.class); }
        @Bean
        public StoreDirectoryPort stores() { return mock(StoreDirectoryPort.class); }
        @Bean
        public PricingAccessPolicy pricing(StoreDirectoryPort stores) {
            return new PricingAccessPolicy(stores,
                    new DefaultListableBeanFactory().getBeanProvider(PricingConfigEvidencePort.class));
        }
        @Bean
        public PricingTaxAccessPolicy access(PricingAccessPolicy pricing) { return new PricingTaxAccessPolicy(pricing); }
        @Bean
        public OpsPricingTaxSettingController ops(PricingTaxSettingService service, PricingTaxAccessPolicy access) {
            return new OpsPricingTaxSettingController(service, access);
        }
        @Bean
        public BizPricingTaxSettingController biz(PricingTaxSettingService service, PricingTaxAccessPolicy access) {
            return new BizPricingTaxSettingController(service, access);
        }
    }
}
