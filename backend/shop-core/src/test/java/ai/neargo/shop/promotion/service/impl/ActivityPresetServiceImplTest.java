package ai.neargo.shop.promotion.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtPresetTemplate;
import ai.neargo.shop.promotion.mapper.PmtPresetTemplateMapper;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class ActivityPresetServiceImplTest {
    @Test void catalogUsesTrustedOwnerEvidenceAndExplicitDisableDiagnostic() {
        var presets = mock(PmtPresetTemplateMapper.class);
        var row = new PmtPresetTemplate();
        row.setTemplateCode("AP009"); row.setTemplateVersion(1); row.setCatalogKind("ACTIVITY");
        row.setName("优惠"); row.setCategory("成交优惠"); row.setEnabled(0);
        row.setIndustryTags("[\"通用\"]"); row.setDraftSchema("{\"scene\":\"ORDER\"}");
        row.setRequiredCapabilities("[\"ORDER_PRICING\"]");
        when(presets.catalog("MAIN", "ACTIVITY")).thenReturn(List.of(row));
        var props = new PromotionFeatureProperties(); props.getPresets().setEnabled(true);
        @SuppressWarnings("unchecked") ObjectProvider<ActivityCapabilityEvidence> providers = mock(ObjectProvider.class);
        ActivityCapabilityEvidence owner = actor -> Set.of(ActivityCapability.ORDER_PRICING);
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(owner));
        var service = new ActivityPresetServiceImpl(presets, new PromotionFeatureGate(props), new ObjectMapper(), providers);
        var actor = new ActivityActor("MAIN", "M1", "U1", null);
        var disabled = service.list(actor, "ACTIVITY", null, null, "美业", 1, 20).records().getFirst();
        assertFalse(disabled.available());
        assertEquals(List.of("PRESET_DISABLED"), disabled.missingCapabilities());
        row.setEnabled(1);
        assertTrue(service.list(actor, "ACTIVITY", null, null, null, 1, 20).records().getFirst().available());
        // A fully enabled generic CUT capability cannot prove first-order semantics.
        row.setTemplateCode("AP001");
        var firstOrder = service.list(actor, "ACTIVITY", null, null, null, 1, 20).records().getFirst();
        assertFalse(firstOrder.available());
        assertTrue(firstOrder.missingCapabilities().contains("PRESET_EXECUTOR"));
    }
}
