package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.trade.api.biz.BizPricingConfigController;
import ai.neargo.shop.trade.api.ops.OpsPricingTemplateController;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigRequests;
import ai.neargo.shop.trade.policy.PricingAccessPolicy;
import ai.neargo.shop.trade.service.PricingSeedService;
import ai.neargo.shop.trade.service.PricingSeedService.ImportMode;
import ai.neargo.shop.trade.service.PricingTemplateService;
import ai.neargo.shop.trade.service.StorePricingConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real method-security proxies prove that publication is not inherited from a draft-edit grant. */
class PricingConfigApiTest {
    private AnnotationConfigApplicationContext context;
    private OpsPricingTemplateController ops;
    private BizPricingConfigController biz;
    private PricingAccessPolicy access;
    private PricingTemplateService templates;
    private StorePricingConfigService configs;
    private PricingSeedService seeds;
    private final Context platform = new Context("MAIN", "OPS", true, null, null,
            new PricingCapabilities(0, 0, false, false, false), "DRAFT_ONLY", Instant.EPOCH, Set.of());

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        ops = context.getBean(OpsPricingTemplateController.class);
        biz = context.getBean(BizPricingConfigController.class);
        access = context.getBean(PricingAccessPolicy.class);
        templates = context.getBean(PricingTemplateService.class);
        configs = context.getBean(StorePricingConfigService.class);
        seeds = context.getBean(PricingSeedService.class);
        when(access.draft(true)).thenReturn(platform);
    }

    @AfterEach
    void clear() {
        context.close();
        SecurityContextHolder.clearContext();
        BizContext.clear();
    }

    @Test
    void opsDraftUpdateCannotPublishOrReadAndDoesNotReachServiceOnDeniedCalls() {
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(),
                List.of(Perms.PRICING_TEMPLATE_UPDATE)));
        assertThatThrownBy(() -> ops.draft("PT1")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ops.publish("PT1", publish(), "KEY"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(templates);
        ops.create(new PricingConfigRequests.Create("FOOD", "DEFAULT", "Default", "{}"), "KEY");
        verify(templates).create(eq(platform), any(), eq("KEY"));
    }

    @Test
    void readPermissionCanRequestPreviewButMissingEvidenceStopsBeforeCompilation() {
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(),
                List.of(Perms.PRICING_TEMPLATE_READ)));
        when(access.publication(true, "PT1")).thenThrow(BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        assertThatThrownBy(() -> ops.preview("PT1", new PricingConfigRequests.Preview(0, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        verifyNoInteractions(templates);
    }

    @Test
    void merchantDraftEditorCannotInitializePublishOrRollback() {
        merchant(BizPerms.PRICING_CONFIG_UPDATE);
        assertThatThrownBy(() -> biz.initialize(new PricingConfigRequests.Initialize("PR1", "initial"), "K"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> biz.publish("PC1", publish(), "K")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> biz.rollback("PC1", publish(), "K")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(configs);
        biz.upgrade("PC1", new PricingConfigRequests.Upgrade(2, "PR2"), "K");
        verify(configs).upgradeTemplate(any(), eq("PC1"), any(), eq("K"));
    }

    @Test
    void seedUsesServerRegistryAndTrustedOperatorContext() {
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(),
                List.of(Perms.PRICING_TEMPLATE_UPDATE)));
        ops.importSeed(new PricingConfigRequests.Seed("{\"schemaVersion\":2}", ImportMode.DRY_RUN));
        verify(seeds).importDrafts(platform, "{\"schemaVersion\":2}", ImportMode.DRY_RUN);
        ops.importSeed(new PricingConfigRequests.Seed("{\"schemaVersion\":3}", ImportMode.DRY_RUN));
        verify(seeds).importDrafts(platform, "{\"schemaVersion\":3}", ImportMode.DRY_RUN);
    }

    @Test
    void actualHttpBindingRejectsMissingIdempotencyAndForgedEvidenceBeforeService() throws Exception {
        PricingAccessPolicyTest.login(LoginUser.operator("OPS", "Ops", List.of(),
                List.of(Perms.PRICING_TEMPLATE_UPDATE)));
        var mvc = MockMvcBuilders.standaloneSetup(ops).build();
        var body = "{\"industryCode\":\"FOOD\",\"templateCode\":\"DEFAULT\","
                + "\"name\":\"Default\",\"definition\":\"{}\"}";
        mvc.perform(post("/ops/pricing-templates").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/ops/pricing-templates").header("Idempotency-Key", "K")
                .contentType(MediaType.APPLICATION_JSON).content(body.substring(0, body.length() - 1)
                        + ",\"capabilities\":{}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(templates);
        mvc.perform(post("/ops/pricing-templates").header("Idempotency-Key", "K")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(templates).create(eq(platform), any(), eq("K"));
    }

    private static PricingConfigRequests.Publish publish() {
        return new PricingConfigRequests.Publish(0, null, "HASH", "publish", null);
    }

    private static void merchant(String permission) {
        PricingAccessPolicyTest.login(LoginUser.merchant("ACT", "B"));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false,
                Map.of(), Map.of("S", Set.of(permission))));
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "perm")
        public TestPermissions permissions() { return new TestPermissions(); }
        @Bean
        public PricingAccessPolicy access() { return mock(PricingAccessPolicy.class); }
        @Bean
        public PricingTemplateService templates() { return mock(PricingTemplateService.class); }
        @Bean
        public StorePricingConfigService configs() { return mock(StorePricingConfigService.class); }
        @Bean
        public PricingSeedService seeds() { return mock(PricingSeedService.class); }
        @Bean
        public OpsPricingTemplateController ops(PricingTemplateService templates, PricingSeedService seeds,
                PricingAccessPolicy access) { return new OpsPricingTemplateController(templates, seeds, access); }
        @Bean
        public BizPricingConfigController biz(StorePricingConfigService configs, PricingAccessPolicy access) {
            return new BizPricingConfigController(configs, access);
        }
    }

    public static class TestPermissions {
        public boolean can(String permission) {
            return SecurityUtils.requireUser().grantedPermissions().contains(permission);
        }
        public boolean canBiz(String permission) { return BizContext.current().can(permission); }
    }
}
