package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverLine;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.impl.DiningChannelFulfillmentServiceImpl;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class DiningChannelFulfillmentServiceTest {
    final ChannelOrderSourceMapper sources = mock(ChannelOrderSourceMapper.class);
    final OrderBatchMapper batches = mock(OrderBatchMapper.class);
    final OrderLineMapper lines = mock(OrderLineMapper.class);
    final CoreOrderBindingMapper bindings = mock(CoreOrderBindingMapper.class);
    final CoreLineAllocationMapper allocations = mock(CoreLineAllocationMapper.class);
    final KitchenTaskService kitchen = mock(KitchenTaskService.class);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final DiningChannelFulfillmentServiceImpl service =
            new DiningChannelFulfillmentServiceImpl(
                    sources,
                    batches,
                    lines,
                    bindings,
                    allocations,
                    kitchen,
                    json,
                    Clock.fixed(now, ZoneOffset.UTC));
    FnbOrderBatch batch;
    CustodyReceiptVO receipt;
    FnbCoreLineAllocation main, option;

    DiningChannelFulfillmentFacts.Query query() {
        return new DiningChannelFulfillmentFacts.Query(
                "E",
                "S",
                "BATCH",
                "CORE",
                "HANDOVER",
                DiningChannelFulfillmentFacts.revision(receipt));
    }

    void original() {
        var source = new FnbChannelOrderSource();
        source.setSourceNo("SOURCE");
        source.setSourceVersion(1L);
        source.setSourceHash("SOURCE_HASH");
        source.setImportNo("IMPORT");
        source.setEntityNo("E");
        source.setStoreNo("S");
        source.setBatchNo("BATCH");
        source.setStatus("ACCEPTED");
        source.setCoreOrderNo("CORE");
        source.setCoreSubOrderNo("SUB");
        source.setCoreAcceptanceReceiptRef("CORE_ACCEPTED");
        var reference =
                new Reference(
                        new Scope("MAIN", "E", "S"), "FOOD", "SOURCE", 1, "SOURCE_HASH", "IMPORT");
        source.setCoreAcceptanceSnapshot(
                json.writeValueAsString(
                        new Acceptance(
                                reference,
                                "CORE",
                                "SUB",
                                "CORE_ACCEPTED",
                                "PROVIDER_COLLECTED",
                                List.of(
                                        new Allocation(
                                                "EXT_MAIN",
                                                null,
                                                "MAIN",
                                                "SOURCE_LINE",
                                                "COMP_MAIN",
                                                "OPAQUE_MAIN",
                                                "4",
                                                "KG",
                                                100,
                                                "CNY",
                                                "CORE_ALLOC_MAIN",
                                                11),
                                        new Allocation(
                                                "EXT_OPTION",
                                                "EXT_MAIN",
                                                "OPTION",
                                                "SOURCE_LINE",
                                                "COMP_OPTION",
                                                "OPAQUE_OPTION",
                                                "1",
                                                "KG",
                                                20,
                                                "CNY",
                                                "CORE_ALLOC_OPTION",
                                                12)))));
        when(sources.selectOne(any())).thenReturn(source);
        batch = new FnbOrderBatch();
        batch.setBatchNo("BATCH");
        batch.setStatus("ACCEPTED");
        batch.setPayMode("CHANNEL");
        batch.setFulfillment("DELIVERY");
        batch.setFrozenRequest("FROZEN");
        when(batches.selectOne(any())).thenReturn(batch);
        var binding = new FnbCoreOrderBinding();
        binding.setBindingNo("BIND");
        binding.setPaymentRootNo("CORE");
        binding.setStoreOrderNo("SUB");
        when(bindings.selectOne(any())).thenReturn(binding);
        var line = new FnbOrderLine();
        line.setFoodLineNo("FOOD_LINE");
        line.setSourceLineRef("SOURCE_LINE");
        line.setQty(new BigDecimal("2"));
        when(lines.selectOne(any())).thenReturn(line);
        main = allocation("ALLOC_MAIN", 11, "COMP_MAIN", "4", "CORE_ALLOC_MAIN");
        option = allocation("ALLOC_OPTION", 12, "COMP_OPTION", "1", "CORE_ALLOC_OPTION");
        when(allocations.selectOne(any())).thenReturn(main, option);
        receipt =
                new CustodyReceiptVO(
                        "HANDOVER",
                        "E",
                        "S",
                        "BATCH",
                        "CORE",
                        "SUB",
                        "BIND",
                        0L,
                        1L,
                        FoodCommandFingerprint.hash("custody-source:v1", "FROZEN"),
                        "CUSTODY_HANDOVER",
                        "DELIVERY",
                        List.of(new HandoverLine("FOOD_LINE", new BigDecimal("0.5"))),
                        List.of(
                                new CustodySourceLine(
                                        "FOOD_LINE",
                                        "SOURCE_LINE",
                                        List.of(
                                                new CoreLineScope(
                                                        "ALLOC_MAIN",
                                                        11L,
                                                        "COMP_MAIN",
                                                        new BigDecimal("4"),
                                                        "KG",
                                                        "CORE_ALLOC_MAIN",
                                                        0L),
                                                new CoreLineScope(
                                                        "ALLOC_OPTION",
                                                        12L,
                                                        "COMP_OPTION",
                                                        new BigDecimal("1"),
                                                        "KG",
                                                        "CORE_ALLOC_OPTION",
                                                        0L)),
                                        List.of())),
                        null,
                        "RIDER",
                        "ACTOR",
                        now.minusSeconds(2).toString(),
                        false,
                        true);
        when(kitchen.custodyReceipts("E", "S", "BATCH")).thenAnswer(call -> List.of(receipt));
    }

    FnbCoreLineAllocation allocation(
            String no, long id, String component, String quantity, String receipt) {
        var row = new FnbCoreLineAllocation();
        row.setAllocationNo(no);
        row.setOrderItemId(id);
        row.setSourceComponentRef(component);
        row.setQty(new BigDecimal(quantity));
        row.setCoreSnapshotHash(receipt);
        row.setVersion(0L);
        row.setUnit("KG");
        return row;
    }

    @Test
    void readsActualCommittedCustodyAndProratesTheActualOpaqueMainAllocationExactly() {
        original();
        var facts = service.capture(query());
        assertThat(facts.lines()).hasSize(1);
        var line = facts.lines().getFirst();
        assertThat(line.coreItemRef()).isEqualTo("OPAQUE_MAIN");
        assertThat(line.componentRef()).isEqualTo("COMP_MAIN");
        assertThat(line.quantity()).isEqualByComparingTo("1");
        assertThat(line.unit()).isEqualTo("KG");
        assertThat(facts.sourceReceiptRef()).contains("HANDOVER", query().sourceRevision());
        assertThat(facts.occurredAt()).isEqualTo(now.minusSeconds(2));
        assertThat(facts.validUntil()).isEqualTo(now.plusSeconds(30));
        verify(allocations, times(2)).selectOne(any());
    }

    @Test
    void aCorruptUnpublishedOptionStillInvalidatesTheWholeOriginalCustodyFact() {
        original();
        option.setQty(new BigDecimal("2"));
        assertThatThrownBy(() -> service.capture(query())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void historicalHashAndScopeAreRequiredAndPackingIsNotSupplierHandover() {
        original();
        var q = query();
        assertThatThrownBy(
                        () ->
                                service.capture(
                                        new DiningChannelFulfillmentFacts.Query(
                                                "E",
                                                "S",
                                                "BATCH",
                                                "CORE",
                                                "HANDOVER",
                                                "CUSTODY_V1:wrong")))
                .isInstanceOf(RuntimeException.class);
        receipt =
                new CustodyReceiptVO(
                        receipt.operationNo(),
                        receipt.entityNo(),
                        receipt.storeNo(),
                        receipt.batchNo(),
                        receipt.orderNo(),
                        receipt.storeOrderNo(),
                        receipt.bindingNo(),
                        receipt.bindingVersion(),
                        receipt.batchVersion(),
                        receipt.frozenRequestHash(),
                        "PACK_CONFIRMED",
                        receipt.kind(),
                        receipt.lines(),
                        receipt.sources(),
                        receipt.recipientNo(),
                        receipt.recipientRef(),
                        receipt.actorNo(),
                        receipt.recordedAt(),
                        receipt.complete(),
                        receipt.coreDeliveryPending());
        assertThatThrownBy(() -> service.capture(query())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void prepayAndPickupBatchesCannotMasqueradeAsAcceptedChannelDelivery() {
        original();
        batch.setPayMode("PREPAY");
        assertThatThrownBy(() -> service.capture(query())).isInstanceOf(RuntimeException.class);
        batch.setPayMode("CHANNEL");
        batch.setFulfillment("PICKUP");
        assertThatThrownBy(() -> service.capture(query())).isInstanceOf(RuntimeException.class);
    }
}
