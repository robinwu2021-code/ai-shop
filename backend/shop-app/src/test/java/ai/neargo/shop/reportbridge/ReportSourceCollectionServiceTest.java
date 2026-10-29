package ai.neargo.shop.reportbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.reportbridge.service.impl.ReportSourceCollectionServiceImpl;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ReportSourceCollectionServiceTest {
    @Test
    void noPaymentOwnerIsUnknownRatherThanZero() {
        var service =
                new ReportSourceCollectionServiceImpl(
                        List.of(port(Owner.FOOD, false), port(Owner.CORE, false)));
        var result = service.collect(query());
        assertThat(result).hasSize(7);
        for (String family : List.of("KITCHEN", "STAFF")) {
            var absent =
                    result.stream()
                            .filter(
                                    snapshot ->
                                            snapshot.source()
                                                    .equals(new SourceKey(Owner.FOOD, family)))
                            .findFirst()
                            .orElseThrow();
            assertThat(absent.coverage()).isEqualTo(Coverage.UNKNOWN);
            assertThat(absent.unknownReason()).isEqualTo("OWNER_SOURCE_NOT_AVAILABLE");
            assertThat(absent.facts()).isEmpty();
        }
        var pay =
                result.stream()
                        .filter(s -> s.source().owner() == Owner.PAY)
                        .findFirst()
                        .orElseThrow();
        assertThat(pay.coverage()).isEqualTo(Coverage.UNKNOWN);
        assertThat(pay.facts()).isEmpty();
    }

    @Test
    void sourceChangingDuringCollectionCannotPublishAStableDay() {
        var service =
                new ReportSourceCollectionServiceImpl(
                        List.of(port(Owner.FOOD, true), port(Owner.CORE, false)));
        assertThatThrownBy(() -> service.collect(query()))
                .hasMessage("REPORT_SOURCE_CHANGED_DURING_COLLECTION");
    }

    @Test
    void rejectsAmbiguousOwners() {
        assertThatThrownBy(
                        () ->
                                new ReportSourceCollectionServiceImpl(
                                        List.of(port(Owner.CORE, false), port(Owner.CORE, false))))
                .hasMessage("REPORT_DUPLICATE_SOURCE_OWNER");
    }

    @Test
    void incompleteFoodBindingSetCannotMakeCoreSubsetLookComplete() {
        var service =
                new ReportSourceCollectionServiceImpl(
                        List.of(dependent(Owner.FOOD), dependent(Owner.CORE)));
        var result = service.collect(query());
        var core =
                result.stream()
                        .filter(s -> s.source().owner() == Owner.CORE)
                        .findFirst()
                        .orElseThrow();
        assertThat(core.coverage()).isEqualTo(Coverage.PARTIAL);
        var facts =
                new ReportMetricCalculator(new ReportMetricCatalog())
                        .aggregate(query().partition(), result);
        var count =
                facts.stream()
                        .filter(f -> f.metricCode().equals("ORDER_CREATED_COUNT"))
                        .findFirst()
                        .orElseThrow()
                        .value();
        assertThat(count.integerValue()).isNull();
        assertThat(count.knownInteger()).isEqualTo(1L);
    }

    @Test
    void originalKitchenOwnerIsCollectedWithoutPretendingStaffOrExpensesExist() {
        var kitchen =
                new ReportSourceFactsPort() {
                    public SourceKey source() {
                        return new SourceKey(Owner.FOOD, "KITCHEN");
                    }

                    public SourceSnapshot readSnapshot(SourceQuery q) {
                        var fact =
                                new MetricFact(
                                        "KITCHEN_READY_QTY",
                                        "KITCHEN_STATION_DAY",
                                        Attribution.OCCURRENCE,
                                        Map.of("stationNo", "STATION", "taskRole", "MAKE"),
                                        Map.of(),
                                        MetricValue.quantity(new BigDecimal("2.5"), "PORTION"));
                        return new SourceSnapshot(
                                q.partition(),
                                source(),
                                "ORIGINAL",
                                "1",
                                Instant.now(),
                                1,
                                "b".repeat(64),
                                Coverage.COMPLETE,
                                null,
                                List.of(fact),
                                List.of());
                    }
                };
        var service =
                new ReportSourceCollectionServiceImpl(
                        List.of(port(Owner.FOOD, false), port(Owner.CORE, false), kitchen));
        var snapshots = service.collect(query());
        var facts =
                new ReportMetricCalculator(new ReportMetricCatalog())
                        .aggregate(query().partition(), snapshots);
        assertThat(facts)
                .singleElement()
                .satisfies(
                        fact ->
                                assertThat(fact.value().quantityValue())
                                        .isEqualByComparingTo("2.5"));
        assertThat(
                        snapshots.stream()
                                .filter(snapshot -> snapshot.source().owner() == Owner.EXPENSE))
                .allSatisfy(
                        snapshot -> assertThat(snapshot.coverage()).isEqualTo(Coverage.UNKNOWN));
    }

    private ReportSourceFactsPort dependent(Owner owner) {
        return new ReportSourceFactsPort() {
            public SourceKey source() {
                return new SourceKey(owner, "ORDER");
            }

            public SourceSnapshot readSnapshot(SourceQuery q) {
                boolean food = owner == Owner.FOOD;
                var fact =
                        new MetricFact(
                                food ? "ORDER_BATCH_COUNT" : "ORDER_CREATED_COUNT",
                                "OPERATING_ORDER_DAY",
                                Attribution.ORIGINAL_ORDER,
                                Map.of(),
                                Map.of(),
                                MetricValue.count(food ? 2 : 1));
                return new SourceSnapshot(
                        q.partition(),
                        source(),
                        "SNAP",
                        "1",
                        Instant.now(),
                        food ? 2 : 1,
                        "a".repeat(64),
                        food ? Coverage.PARTIAL : Coverage.COMPLETE,
                        food ? "UNRESOLVED_BINDING" : null,
                        List.of(fact),
                        List.of("KNOWN_ORDER"));
            }
        };
    }

    private ReportSourceFactsPort port(Owner owner, boolean changing) {
        var calls = new AtomicInteger();
        return new ReportSourceFactsPort() {
            public SourceKey source() {
                return new SourceKey(owner, "ORDER");
            }

            public SourceSnapshot readSnapshot(SourceQuery q) {
                String version = changing ? String.valueOf(calls.incrementAndGet()) : "1";
                return new SourceSnapshot(
                        q.partition(),
                        source(),
                        "SNAP",
                        version,
                        Instant.now(),
                        0,
                        "a".repeat(64),
                        Coverage.EMPTY_VERIFIED,
                        null,
                        List.of(),
                        List.of());
            }
        };
    }

    private SourceQuery query() {
        var instant = Instant.parse("2026-10-20T00:00:00Z");
        return new SourceQuery(
                new Partition(new Scope("MAIN", "E1", "S1"), LocalDate.of(2026, 10, 20), 1),
                new DayEvidence("D1", "P1", 0, "UTC", 0, instant, instant.plusSeconds(86400)),
                "ACTOR",
                List.of(),
                100);
    }
}
