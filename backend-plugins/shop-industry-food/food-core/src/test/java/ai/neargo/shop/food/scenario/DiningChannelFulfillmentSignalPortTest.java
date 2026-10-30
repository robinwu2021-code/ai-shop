package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverLine;
import ai.neargo.shop.food.dto.KitchenContracts.KitchenTaskScope;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.service.impl.DiningChannelFulfillmentSignalPortImpl;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class DiningChannelFulfillmentSignalPortTest {
    final ServiceEventMapper events = mock(ServiceEventMapper.class);
    final KitchenTaskMapper tasks = mock(KitchenTaskMapper.class);
    final KitchenTicketMapper tickets = mock(KitchenTicketMapper.class);
    final OrderBatchMapper batches = mock(OrderBatchMapper.class);
    final OrderLineMapper lines = mock(OrderLineMapper.class);
    final ChannelOrderSourceMapper sources = mock(ChannelOrderSourceMapper.class);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final DiningServiceEvent event =
            new DiningServiceEvent(
                    "EVENT",
                    "E",
                    "S",
                    "BATCH",
                    "TICKET",
                    "TASK",
                    "LINE",
                    "COMPONENT",
                    "STATION",
                    "MAKE",
                    1,
                    "CUSTODY_HANDOVER",
                    new BigDecimal("0.5"),
                    "Actual handover",
                    "STAFF",
                    now.minusSeconds(1));
    final DiningChannelFulfillmentSignalPortImpl service =
            new DiningChannelFulfillmentSignalPortImpl(
                    events,
                    tasks,
                    tickets,
                    batches,
                    lines,
                    sources,
                    json,
                    Clock.fixed(now, ZoneOffset.UTC));
    CustodyReceiptVO receipt;
    FnbOrderBatch batch;
    FnbOrderLine line;
    FnbChannelOrderSource source;

    void setup(boolean channel) {
        var stored = new FnbServiceEvent();
        stored.setEventNo("EVENT");
        stored.setTaskNo("TASK");
        stored.setEntityNo("E");
        stored.setAction("CUSTODY_HANDOVER");
        stored.setQty(new BigDecimal("0.5"));
        stored.setReason("Actual handover");
        stored.setCreatedBy("STAFF");
        when(events.selectOne(any()))
                .thenAnswer(
                        call -> {
                            stored.setCustodySnapshot(json.writeValueAsString(receipt));
                            return stored;
                        });
        var task = new FnbKitchenTask();
        task.setTaskNo("TASK");
        task.setTicketNo("TICKET");
        task.setFoodLineNo("LINE");
        task.setComponentNo("COMPONENT");
        task.setStationNo("STATION");
        task.setTaskRole("MAKE");
        task.setRouteVersion(1L);
        when(tasks.selectOne(any())).thenReturn(task);
        var ticket = new FnbKitchenTicket();
        ticket.setTicketNo("TICKET");
        ticket.setBatchNo("BATCH");
        ticket.setKind("MAKE");
        when(tickets.selectOne(any())).thenReturn(ticket);
        batch = new FnbOrderBatch();
        batch.setStatus("ACCEPTED");
        batch.setFrozenRequest("FROZEN");
        batch.setPayMode(channel ? "CHANNEL" : "PREPAY");
        batch.setFulfillment("DELIVERY");
        when(batches.selectOne(any())).thenReturn(batch);
        line = new FnbOrderLine();
        line.setSourceLineRef(channel ? "SOURCE_LINE" : null);
        when(lines.selectOne(any())).thenReturn(line);
        source = new FnbChannelOrderSource();
        source.setStatus("ACCEPTED");
        source.setImportNo("IMPORT");
        source.setBatchNo("BATCH");
        source.setCoreOrderNo("CORE");
        source.setCoreSubOrderNo("SUB");
        source.setAcceptanceReceiptRef("ACTUAL_ACCEPTED");
        when(sources.selectOne(any())).thenReturn(source);
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
                        List.of(new HandoverLine("LINE", new BigDecimal("0.5"))),
                        List.of(
                                new CustodySourceLine(
                                        "LINE",
                                        line.getSourceLineRef(),
                                        List.of(
                                                new CoreLineScope(
                                                        "ALLOCATION",
                                                        11L,
                                                        "SOURCE_COMPONENT",
                                                        new BigDecimal("2"),
                                                        "KG",
                                                        "CORE_RECEIPT",
                                                        0L)),
                                        List.of(
                                                new KitchenTaskScope(
                                                        "TASK",
                                                        "COMPONENT",
                                                        "STATION",
                                                        "MAKE",
                                                        1,
                                                        0)))),
                        null,
                        "RIDER",
                        "STAFF",
                        now.minusSeconds(2).toString(),
                        false,
                        true);
    }

    @Test
    void selectsTheExactPersistedEventAndImmutableCustodyRevisionWithoutScanningAllCustody() {
        setup(true);
        var signal = service.resolve(event);
        assertThat(signal.status()).isEqualTo(DiningChannelFulfillmentSignalPort.Status.READY);
        assertThat(signal.importNo()).isEqualTo("IMPORT");
        assertThat(signal.custodyOperationNo()).isEqualTo("HANDOVER");
        assertThat(signal.sourceRevision())
                .isEqualTo(DiningChannelFulfillmentFacts.revision(receipt));
        assertThat(signal.originalEvent()).isEqualTo(event);
        assertThat(signal.validUntil()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void genuineOrdinaryCustodyWithNoChannelLineReferenceIsExplicitlyNotChannel() {
        setup(false);
        var signal = service.resolve(event);
        assertThat(signal.status())
                .isEqualTo(DiningChannelFulfillmentSignalPort.Status.NOT_CHANNEL);
        assertThat(signal.importNo()).isNull();
        verifyNoInteractions(sources);
    }

    @Test
    void aMissingNativeSourceOrForeignCoreBindingCannotMasqueradeAsOrdinaryBusiness() {
        setup(true);
        when(sources.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(event)).isInstanceOf(RuntimeException.class);
        when(sources.selectOne(any())).thenReturn(source);
        source.setCoreOrderNo("FOREIGN");
        assertThatThrownBy(() -> service.resolve(event)).isInstanceOf(RuntimeException.class);
    }
}
