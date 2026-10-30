package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pure DTO currency semantics; current real common owners provide CNY only. */
class WorkOrderBillServiceImplTest {
    /**
     * DTO-semantic evidence only: actual common owners currently produce CNY, never invented USD
     * owner facts.
     */
    @Test
    void currencyAggregationKeepsSeparateSubtotalsAndUnknownNull() throws Exception {
        Object bill =
                Arrays.stream(WorkOrderBillServiceImpl.class.getConstructors())
                        .filter(c -> c.getParameterCount() == 11)
                        .findFirst()
                        .orElseThrow()
                        .newInstance(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Clock.systemUTC());
        var aggregate = bill.getClass().getDeclaredMethod("currencies", List.class, boolean.class);
        aggregate.setAccessible(true);
        var json = JsonMapper.builder().build();
        Object cnyManual = source("CNY", true, "MANUAL_OFFLINE", 101L);
        Object usdManual = source("USD", true, "MANUAL_OFFLINE", 205L);
        var totals =
                json.readTree(
                        json.writeValueAsString(
                                aggregate.invoke(bill, List.of(cnyManual, usdManual), true)));
        assertThat(totals.size()).isEqualTo(2);
        assertThat(totals.get(0).path("currency").asText()).isEqualTo("CNY");
        assertThat(totals.get(0).path("manualDeclaredMinor").asLong()).isEqualTo(101);
        assertThat(totals.get(1).path("currency").asText()).isEqualTo("USD");
        assertThat(totals.get(1).path("manualDeclaredMinor").asLong()).isEqualTo(205);
        assertThat(totals.get(0).path("receivedMinor").isNull()).isTrue();
        var unknown =
                json.readTree(
                        json.writeValueAsString(
                                aggregate.invoke(
                                        bill,
                                        List.of(
                                                cnyManual,
                                                source("CNY", false, "MANUAL_OFFLINE", null)),
                                        true)));
        assertThat(unknown.get(0).path("complete").asBoolean(true)).isFalse();
        assertThat(unknown.get(0).path("manualDeclaredMinor").isNull()).isTrue();
        assertThat(unknown.get(0).path("receivedMinor").isNull()).isTrue();
    }

    /**
     * Only unavailable providers are used: this proves reader selection, never positive owner
     * readiness.
     */
    @Test
    @SuppressWarnings("unchecked")
    void manualIntentSelectsManualReaderAlongsideUnchangedHistoricalV2() throws Exception {
        var json = JsonMapper.builder().build();
        var db =
                new AppointmentPersistence(
                        null, null, null, null, null, null, null, null, null, null, json, null);
        var observations = mock(ServiceSourceObservation.class);
        ObjectProvider<CheckoutPort> financial = mock(ObjectProvider.class);
        var bill =
                new WorkOrderBillServiceImpl(
                        db,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        financial,
                        observations,
                        null,
                        Clock.systemUTC());
        var old = intent(2);
        var manual = intent(3);
        String oldBytes = old.getFrozenCommand();
        for (var rows : List.of(List.of(old, manual), List.of(manual, old))) {
            var view = reader(bill, rows);
            assertThat(view.admissionKind()).isEqualTo("MANUAL_OFFLINE");
            assertThat(view.financialVerification()).isEqualTo("NOT_APPLICABLE_MANUAL");
            assertThat(view.complete()).isFalse();
            assertThat(view.manualDeclaredMinor()).isNull();
            assertThat(old.getFrozenCommand()).isEqualTo(oldBytes);
        }
        verifyNoInteractions(financial);
        assertThat(reader(bill, List.of(old)).admissionKind()).isEqualTo("LEGACY_FUNDED");
        verify(financial).getIfAvailable();
    }

    private BtyServiceSubmission intent(int version) {
        var row = new BtyServiceSubmission();
        row.setEntityNo("E");
        row.setStoreNo("S");
        String key = "a".repeat(64);
        String namespace =
                version == 2
                        ? "legacy-namespace"
                        : IdempotencyScope.requestHash(
                                "BEAUTY_MANUAL_SUBMISSION_V1",
                                "MAIN",
                                "E",
                                "S",
                                "MERCHANT",
                                "MCH",
                                "A",
                                "BAPI-056",
                                key);
        row.setRequestKeyHash(namespace);
        var command =
                new ServiceSubmissionServiceImpl.Command(
                        version,
                        "MERCHANT",
                        "MCH",
                        "A",
                        "BAPI-056",
                        namespace,
                        key,
                        "D",
                        1,
                        "scope-hash",
                        "USER",
                        "U",
                        "U",
                        null,
                        null,
                        1,
                        0,
                        "OP",
                        "P",
                        version == 2 ? "EXISTING" : "MANUAL_EXISTING",
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        "SO",
                        1L,
                        "b".repeat(64),
                        List.of(),
                        List.of(),
                        version == 3 ? "MANUAL_OFFLINE" : null,
                        version == 3 ? new CustomerParty("USER", "U", null, 1L) : null,
                        version == 3 ? "CONSUMER_SESSION" : null);
        row.setFrozenCommand(JsonMapper.builder().build().writeValueAsString(command));
        assertThat(ServiceSubmissionServiceImpl.validCommand(command, row)).isTrue();
        return row;
    }

    private SourceBillVO reader(WorkOrderBillServiceImpl bill, List<BtyServiceSubmission> rows)
            throws Exception {
        var type = Class.forName(WorkOrderBillServiceImpl.class.getName() + "$Manifest");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var manifest =
                constructor.newInstance(
                        new ServiceSubmissionQuery.Root(null, null, null, null, List.of()),
                        rows,
                        List.of(),
                        "semantic-only");
        var method =
                WorkOrderBillServiceImpl.class.getDeclaredMethod(
                        "source", String.class, String.class, String.class, type, List.class);
        method.setAccessible(true);
        Object read = method.invoke(bill, "E", "S", "A", manifest, rows);
        var view = read.getClass().getDeclaredMethod("view");
        view.setAccessible(true);
        return (SourceBillVO) view.invoke(read);
    }

    private Object source(String currency, boolean complete, String kind, Long declared) {
        return new SourceBillVO(
                "operation",
                "order",
                "source",
                currency,
                null,
                null,
                null,
                null,
                null,
                "WHOLE_SOURCE",
                List.of(),
                List.of(),
                null,
                complete,
                List.of(),
                kind,
                "NOT_APPLICABLE_MANUAL",
                null,
                declared);
    }
    /** Call order only; the native source tests prove the durable pending/suspension distinction. */
    @Test
    @SuppressWarnings("unchecked")
    void billReadRequestsRefreshBeforeOwnerRead() throws Exception {
        var mapper = JsonMapper.builder().build();
        var db = new AppointmentPersistence(null, null, null, null, null, null, null, null, null, null, mapper, null);
        var observations = mock(ServiceSourceObservation.class);
        ObjectProvider<CheckoutPort> checkout = mock(ObjectProvider.class);
        ObjectProvider<ManualServiceAdmissionPort> manual = mock(ObjectProvider.class);
        var canonical = new ServiceSourceObservation.ManualCanonical("E", "S",
                new CustomerParty("USER", "U", null, null), "OP", "ORDER", "SOURCE");
        var known = new BtySourceObservation(); known.setAdmissionKind("MANUAL_OFFLINE");
        when(observations.manualKnown(eq("E"), eq("S"), any(), any())).thenReturn(canonical);
        when(observations.manualHistory(canonical)).thenReturn(known);
        var bill = new WorkOrderBillServiceImpl(db, null, null, null, null, null, null, checkout,
                observations, null, Clock.systemUTC(), manual);
        assertThat(reader(bill, List.of(intent(3))).complete()).isFalse();
        var order = inOrder(observations, manual);
        order.verify(observations).manualKnown(eq("E"), eq("S"), any(), any());
        order.verify(observations).manualHistory(canonical);
        order.verify(observations).invalidateManual(canonical, "A");
        order.verify(manual).getIfAvailable();
        verifyNoInteractions(checkout);
    }

}
