package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseIntent;
import ai.neargo.shop.pay.entitlement.entity.EntUseAllocation;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.policy.BrandCouponUseProof;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseProposalMapper;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class BrandCouponUseWriterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EntitlementContext context = new EntitlementContext("MAIN", "ACTOR", "REQUEST", "a".repeat(64));
    private final EntUseIntentMapper intents = mock(EntUseIntentMapper.class);
    private final EntInstanceMapper instances = mock(EntInstanceMapper.class);
    private final EntUsageMapper usages = mock(EntUsageMapper.class);
    private final EntUseAllocationMapper allocations = mock(EntUseAllocationMapper.class);
    private final EntitlementLifecycleService lifecycle = mock(EntitlementLifecycleService.class);
    private final EntitlementSourceFence fences = mock(EntitlementSourceFence.class);
    private final EntitlementPostingService posting = mock(EntitlementPostingService.class);
    private final EntitlementUseWriter writer = new EntitlementUseWriter(intents, instances, allocations, lifecycle,
            usages, mock(EntUseProposalMapper.class), posting, mock(PayEntitlementEventPublisher.class), fences, json);
    private final EntInstance asset = new EntInstance();
    private final EntUseIntent intent = new EntUseIntent();
    private final SourceRef source = new SourceRef("CORE", "ORDER", "ORDER-1", null, null, null, 1);
    private final List<UseItem> items = List.of(new UseItem("L1", "G1", "goods", 1, 1000));

    @Test void acceptedProofPreservesConsumerEntityInIntentAndUsage() {
        var d = definition("BRAND_STORES", "SOURCE");
        var frozen = fixture(d, AssetKind.ORDER_COUPON);
        var proof = proof(d, "VERSION", "CONSUMER", "S2");
        assertThat(writer.prepare(context, frozen, proof).status().name()).isEqualTo("HELD");
        assertThat(intent.getEntityNo()).isEqualTo("CONSUMER");
        assertThat(asset.getEntityNo()).isEqualTo("ISSUER");
        asset.setReservedUnits(1);
        var decision = new DecisionRef("DECISION", Instant.now(), 1, DecisionRef.DecisionType.USE_COMMIT);
        var truth = new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.COMMITTED, decision,
                "U1", "CONSUMER", "S2", "CNY", 100L, "a".repeat(64));
        writer.finish(context, "OPERATION", source, decision, true, truth);
        var usage = ArgumentCaptor.forClass(EntUsage.class);
        verify(usages).insert(usage.capture());
        assertThat(usage.getValue().getEntityNo()).isEqualTo("CONSUMER");
        assertThat(usage.getValue().getStoreNo()).isEqualTo("S2");
        assertThat(asset.getEntityNo()).isEqualTo("ISSUER");
    }

    @Test void lockedAssetMustMatchProofDefinitionIssuerConsumerAndStore() {
        var d = definition("BRAND_STORES", "SOURCE");
        var frozen = fixture(d, AssetKind.ORDER_COUPON);
        assertThatThrownBy(() -> writer.prepare(context, frozen)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> writer.prepare(context, frozen, proof(d, "VERSION", "CONSUMER", "OTHER")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> writer.prepare(context, frozen, proof(d, "VERSION", "OTHER", "S2")))
                .isInstanceOf(BizException.class);
        var proof = proof(d, "VERSION", "CONSUMER", "S2");
        asset.setEntityNo("OTHER-ISSUER");
        assertThatThrownBy(() -> writer.prepare(context, frozen, proof)).isInstanceOf(BizException.class);
        asset.setEntityNo("ISSUER");
        asset.setTemplateVersionNo("COPIED-VERSION");
        assertThatThrownBy(() -> writer.prepare(context, frozen, proof)).isInstanceOf(BizException.class);
        asset.setTemplateVersionNo("VERSION");
        asset.setRuleSnapshot(json.writeValueAsString(definition("BRAND_STORES", "OTHER-SOURCE")));
        assertThatThrownBy(() -> writer.prepare(context, frozen, proof)).isInstanceOf(BizException.class);
        verify(lifecycle, never()).reserve(any(), any(), any(), any(), anyInt());
    }

    @Test void ordinaryStoreModeAndForgedCardNeverGainCrossEntityUse() {
        var frozen = fixture(definition("STORES", "S2"), AssetKind.ORDER_COUPON);
        assertThatThrownBy(() -> writer.prepare(context, frozen)).isInstanceOf(BizException.class);
        var brand = definition("BRAND_STORES", "SOURCE");
        var card = fixture(brand, AssetKind.PAID_CARD);
        assertThatThrownBy(() -> writer.prepare(context, card, proof(brand, "VERSION", "CONSUMER", "S2")))
                .isInstanceOf(BizException.class);
    }

    private Prepared fixture(EntitlementDefinition d, AssetKind kind) {
        ReflectionTestUtils.setField(writer, "documentNumbers", mock(PayRightsDocumentNumbers.class));
        var command = new UseCommand("OPERATION", source, "U1", "CONSUMER", "S2", "CNY", kind, "I1", null,
                null, 1, kind == AssetKind.ORDER_COUPON ? 100L : null, items, "AUTH", null, 0,
                kind == AssetKind.ORDER_COUPON ? null : "PREVIEW", kind == AssetKind.ORDER_COUPON ? "QUOTE" : null);
        var sourceFact = new UseSourceFact("MAIN", source, "U1", "CONSUMER", "S2", "CNY", items,
                "a".repeat(64), "store", null, null);
        var auth = new SourceDecisionPort.AuthorizationDecision("AUTH",
                SourceDecisionPort.AuthorizationState.AUTHORIZED,
                source, "U1", "CONSUMER", "S2", "CNY", 100L, "a".repeat(64), "a".repeat(64),
                Instant.now().plusSeconds(120));
        var prepared = new Prepared(command, sourceFact, d, auth, "a".repeat(64), LocalDate.now(), 100L, 0,
                List.of(new CouponAllocation("I1", "L1", 100)));
        asset.setEntityNo("ISSUER"); asset.setUserNo("U1"); asset.setKind(kind.name());
        asset.setRuleSnapshot(json.writeValueAsString(d)); asset.setVersion(0L); asset.setStatus("ACTIVE");
        asset.setTemplateVersionNo("VERSION");
        asset.setInstanceNo("I1"); asset.setRemainingUnits(1); asset.setReservedUnits(0); asset.setAssetSeq(0L);
        intent.setStatus("PREPARING");
        org.mockito.Mockito.doAnswer(call -> {
            EntUseIntent row = call.getArgument(0);
            intent.setPayloadHash(row.getPayloadHash()); intent.setCommandSnapshot(row.getCommandSnapshot());
            intent.setEntityNo(row.getEntityNo()); intent.setStoreNo(row.getStoreNo());
            intent.setUserNo(row.getUserNo()); intent.setOperationNo(row.getOperationNo());
            intent.setCreatedAt(row.getCreatedAt()); return 1;
        }).when(intents).claim(any());
        when(intents.lock("MAIN", "OPERATION")).thenReturn(intent);
        when(intents.find("MAIN", "OPERATION")).thenReturn(intent);
        when(instances.lock("MAIN", "I1")).thenReturn(asset);
        when(instances.updateById(asset)).thenReturn(1);
        when(allocations.insert(any(EntUseAllocation.class))).thenReturn(1);
        when(intents.updateById(intent)).thenReturn(1);
        when(usages.insert(any(EntUsage.class))).thenReturn(1);
        return prepared;
    }

    private BrandCouponUseProof proof(EntitlementDefinition d, String version, String consumer, String store) {
        var port = mock(BrandCouponSourcePort.class);
        when(port.permitsUse("MAIN", "ISSUER", d, version, consumer, store)).thenReturn(true);
        return BrandCouponUseProof.authorize(port, "MAIN", "ISSUER", d, version, consumer, store);
    }

    private EntitlementDefinition definition(String mode, String ref) {
        return new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope(mode, List.of(ref)), List.of("TARGETED"));
    }
}
