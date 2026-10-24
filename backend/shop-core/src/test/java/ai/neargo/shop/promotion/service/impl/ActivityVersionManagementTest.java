package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.dto.*;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.*;
import ai.neargo.shop.promotion.entity.*;
import ai.neargo.shop.promotion.mapper.*;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.policy.*;
import ai.neargo.shop.promotion.service.*;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class ActivityVersionManagementTest {
    private final ActivityMapper heads = mock(ActivityMapper.class);
    private final PmtPresetTemplateMapper presets = mock(PmtPresetTemplateMapper.class);
    private final ActivityCommandDeduplicator commands = mock(ActivityCommandDeduplicator.class);
    private final ActivityActor actor = new ActivityActor("MAIN", "M1", "U1", null);

    @SuppressWarnings("unchecked") private ActivityVersionServiceImpl service() {
        var props = new PromotionFeatureProperties(); props.getActivity().setEnabled(true);
        ObjectProvider<EntitlementCatalogPort> catalog = mock(ObjectProvider.class);
        ObjectProvider<ActivityCapabilityEvidence> evidence = mock(ObjectProvider.class);
        when(evidence.orderedStream()).thenAnswer(call -> Stream.empty());
        when(commands.replay(any(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(commands.execute(any(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(6)).get());
        when(heads.insert(any(PmtActivity.class))).thenReturn(1);
        var service = new ActivityVersionServiceImpl(heads, mock(UnifiedActivityManagementMapper.class),
                mock(PmtActivityVersionMapper.class), mock(PmtActivityBudgetMapper.class),
                mock(PmtActivityScopeMapper.class), mock(PmtRechargeTierMapper.class), presets, commands,
                new ActivityRuleValidator(), mock(EntitlementScopeValidationService.class), catalog,
                mock(ProductScopeFactsPort.class), mock(MemberQueryPort.class), new PromotionFeatureGate(props),
                evidence, new ObjectMapper());
        var coordinator = mock(ai.neargo.shop.numbering.store.DocumentNumberCoordinator.class);
        when(coordinator.transaction(anyMap(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(coordinator.findAll(any(), anyCollection())).thenReturn(Map.of());
        when(coordinator.bind(any(), any())).thenReturn("PT20300101000001");
        service.setDocumentNumbers(new CoreMarketingDocumentNumbers(coordinator));
        return service;
    }
    private UnifiedActivityDefinition definition(String name, String scene) {
        return new UnifiedActivityDefinition(name, scene, "AP001", 0, new RedemptionScope("ALL", List.of()),
                null, new UnifiedActivityDefinition.Schedule("ONE_OFF", Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2030-02-01T00:00:00Z"), "Asia/Shanghai", List.of(), null, null),
                new UnifiedActivityDefinition.Rule(1, new UnifiedActivityDefinition.Trigger("AMOUNT", 1000L, null),
                List.of(new UnifiedActivityDefinition.Benefit("CUT", 100L, null, null, null)), List.of()),
                100L, 10000L, 1, "CNY");
    }
    private void preset(String effect) {
        var row = new PmtPresetTemplate(); row.setTemplateCode("AP001"); row.setTemplateVersion(2);
        row.setName("预置优惠"); row.setDraftSchema("{\"scene\":\"ORDER\",\"effect\":\"" + effect + "\"}");
        when(presets.catalog("MAIN", "ACTIVITY")).thenReturn(List.of(row));
    }
    @Test void instantiateUsesPresetMetadataWithoutInventingCommercialValues() {
        var service = service(); preset("CUT");
        var saved = service.saveDraft(actor, new SaveDraft(null, null, definition(null, null)), "KEY1");
        assertEquals("预置优惠", saved.definition().name()); assertEquals("ORDER", saved.definition().scene());
        assertEquals(2, saved.definition().templateVersion()); assertEquals(100L, saved.definition().rule().benefits().getFirst().amountMinor());
        assertEquals("DRAFT", saved.status()); assertNull(saved.currentVersionNo());
    }
    @Test void presetEffectCannotBeReplacedWithCheaperCapabilityRequirements() {
        var service = service(); preset("GIFT");
        assertThrows(BizException.class, () -> service.saveDraft(actor, new SaveDraft(null, null, definition("优惠", "ORDER")), "KEY1"));
        verify(heads, never()).insert(any(PmtActivity.class));
    }
    @Test void committedReplySurvivesUnavailablePresetAndMutableSchedule() {
        var service = service();
        var original = new ActivityView("ACT1", 0, "DRAFT", null, definition("原始活动", "ORDER"));
        when(commands.replay(any(), anyString(), anyString(), any(), any())).thenReturn(Optional.of(original));
        assertSame(original, service.saveDraft(actor, new SaveDraft(null, null, definition(null, null)), "KEY1"));
        verifyNoInteractions(presets, heads);
    }
    @Test void storeRestrictedActorCannotCreateAllStoreActivity() {
        var service = service(); preset("CUT");
        var restricted = new ActivityActor("MAIN", "M1", "U1", Set.of("ST1"));
        assertThrows(BizException.class, () -> service.saveDraft(restricted, new SaveDraft(null, null, definition("优惠", "ORDER")), "KEY1"));
        verify(heads, never()).insert(any(PmtActivity.class));
    }
}
