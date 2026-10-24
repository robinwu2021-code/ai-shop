package ai.neargo.shop.trade;

import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingConfigCompiler;
import ai.neargo.shop.common.pricing.PricingRuleCatalog;
import ai.neargo.shop.common.pricing.SystemConstraints;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Frozen publication fixtures; these are never registered as production owner capability providers. */
final class PricingRuntimeFixture {
    private PricingRuntimeFixture() { }
    static final PricingCapabilities CAPS = new PricingCapabilities(8, 4, true, true, true);
    record Rows(OrdStorePricingConfig head, OrdStorePricingRevision revision, OrdPricingTemplateRevision template) { }
    static Rows rows() throws Exception {
        var json = JsonMapper.builder().build();
        String definition = json.readTree(Files.readString(Path.of("../../docs/technical/design/schema/"
                + "pricing-merchant-tax-initial-data.example.json"))).path("industryTemplates").get(0)
                .path("definition").toString();
        var config = new PricingConfigCompiler().compile(definition, "{}", SystemConstraints.standard(), CAPS).config();
        var head = new OrdStorePricingConfig();
        head.setTenantNo("MAIN"); head.setEntityNo("E"); head.setStoreNo("A"); head.setIndustryCode("FOOD");
        head.setConfigNo("CONF"); head.setTemplateNo("TEMPLATE"); head.setActiveRevisionNo("R"); head.setStatus("ACTIVE");
        head.setDeleted(0);
        var template = new OrdPricingTemplateRevision();
        template.setTenantNo("MAIN"); template.setTemplateNo("TEMPLATE"); template.setRevisionNo("TR");
        template.setIndustryCode("FOOD"); template.setSchemaVersion(3); template.setRevision(1L); template.setDeleted(0);
        template.setDefinitionSnapshot(config.canonicalJson());
        template.setDefinitionHash(PricingRuntimeConfigPolicy.sha256(config.canonicalJson()));
        template.setEngineSpecVersion(PricingRuleCatalog.registryVersion(3));
        template.setRuleCatalogVersion(PricingRuntimeConfigPolicy.catalogHash(config));
        var revision = new OrdStorePricingRevision();
        revision.setTenantNo("MAIN"); revision.setEntityNo("E"); revision.setStoreNo("A");
        revision.setConfigNo("CONF"); revision.setRevisionNo("R"); revision.setRevision(1L); revision.setIndustryCode("FOOD");
        revision.setTemplateRevisionNo("TR"); revision.setSchemaVersion(3); revision.setOverrideSnapshot("{}");
        revision.setEffectiveSnapshot(config.canonicalJson()); revision.setEffectiveHash(config.configHash());
        revision.setEngineSpecVersion(template.getEngineSpecVersion());
        revision.setRuleCatalogVersion(template.getRuleCatalogVersion()); revision.setDeleted(0);
        return new Rows(head, revision, template);
    }
}
