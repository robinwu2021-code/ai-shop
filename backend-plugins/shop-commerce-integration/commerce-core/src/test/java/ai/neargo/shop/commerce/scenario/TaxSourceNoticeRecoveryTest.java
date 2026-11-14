package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;
import ai.neargo.shop.commerce.mapper.TaxSourceNoticeMapper;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.TaxSourceNoticeServiceImpl;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

class TaxSourceNoticeRecoveryTest {
    TaxSourceNoticeContracts.Notice notice(String event, String entity, String key) {
        return new TaxSourceNoticeContracts.Notice(
                "PLATFORM",
                event,
                "MAIN",
                entity,
                key,
                1L,
                "a".repeat(64),
                Instant.now().minusSeconds(1));
    }

    void scope(CatalogSynchronizationTest.State state, String number, String entity) {
        state.sql()
                .update(
                        "INSERT INTO"
                            + " cim_catalog_scope(scope_no,entity_no,store_no,connection_no,remote_scope_key,name,remote_consistency,generation,status)"
                            + " VALUES (?,?,?,'CON',?,'Scope','CONSISTENT',0,'ACTIVE')",
                        number,
                        entity,
                        "S" + number,
                        number);
    }

    @Test
    void originalPlatformScopeFansOutInDurableBoundedBatchesAndReplayDoesNotCreateAnotherNotice() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            for (int i = 0; i < 101; i++)
                                scope(state, "SC" + i, i % 2 == 0 ? "E" : "OTHER");
                            var calls = new ArrayList<SourceNoticeContracts.Notice>();
                            var targets = mock(SourceNoticeService.class);
                            when(targets.receive(any()))
                                    .thenAnswer(
                                            call -> {
                                                calls.add(call.getArgument(0));
                                                return new SourceNoticeContracts.Receipt(
                                                        "TARGET", Instant.now());
                                            });
                            var service =
                                    new TaxSourceNoticeServiceImpl(
                                            state.context().getBean(CatalogPersistence.class),
                                            state.context().getBean(TaxSourceNoticeMapper.class),
                                            targets);
                            var original = notice("GLOBAL", null, "pricing.tax.default");
                            var receipt = service.receive(original);
                            assertThat(service.receive(original)).isEqualTo(receipt);
                            assertThat(service.recoverDue(10)).isOne();
                            assertThat(calls).hasSize(200);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM"
                                                                    + " cim_tax_source_notice",
                                                            String.class))
                                    .isEqualTo("PENDING");
                            // A fresh worker resumes the committed cursor rather than redelivering
                            // the first 100 scopes.
                            var restarted =
                                    new TaxSourceNoticeServiceImpl(
                                            state.context().getBean(CatalogPersistence.class),
                                            state.context().getBean(TaxSourceNoticeMapper.class),
                                            targets);
                            assertThat(restarted.recoverDue(10)).isOne();
                            assertThat(calls).hasSize(202);
                            assertThat(
                                            calls.stream()
                                                    .map(SourceNoticeContracts.Notice::eventNo)
                                                    .distinct())
                                    .containsExactly("GLOBAL");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM"
                                                                    + " cim_tax_source_notice",
                                                            String.class))
                                    .isEqualTo("COMPLETE");
                        });
    }

    @Test
    void merchantSourceCannotFanOutToOtherOriginalMerchantAndFailureKeepsCursorForRetry() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            scope(state, "ONE", "E");
                            scope(state, "TWO", "OTHER");
                            var targets = mock(SourceNoticeService.class);
                            when(targets.receive(any()))
                                    .thenThrow(
                                            new IllegalStateException(
                                                    "target notice persistence unavailable"));
                            var service =
                                    new TaxSourceNoticeServiceImpl(
                                            state.context().getBean(CatalogPersistence.class),
                                            state.context().getBean(TaxSourceNoticeMapper.class),
                                            targets);
                            service.receive(notice("MERCHANT", "E", "pricing.tax.merchant.E"));
                            assertThat(service.recoverDue(10)).isZero();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT scope_cursor FROM"
                                                                    + " cim_tax_source_notice",
                                                            Long.class))
                                    .isZero();
                            var calls = new ArrayList<SourceNoticeContracts.Notice>();
                            doAnswer(
                                            call -> {
                                                calls.add(call.getArgument(0));
                                                return new SourceNoticeContracts.Receipt(
                                                        "TARGET", Instant.now());
                                            })
                                    .when(targets)
                                    .receive(any());
                            state.sql()
                                    .execute(
                                            "UPDATE cim_tax_source_notice SET"
                                                    + " next_check_at='2000-01-01 00:00:00'");
                            assertThat(service.recoverDue(10)).isOne();
                            assertThat(calls).hasSize(2);
                            assertThat(calls)
                                    .allMatch(
                                            n ->
                                                    n.entityNo().equals("E")
                                                            && n.storeNo().equals("SONE"));
                        });
    }

    @Test
    void inventedPlatformMerchantAndChangedReplayAreRejected() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            var service =
                                    new TaxSourceNoticeServiceImpl(
                                            state.context().getBean(CatalogPersistence.class),
                                            state.context().getBean(TaxSourceNoticeMapper.class),
                                            mock(SourceNoticeService.class));
                            assertThatThrownBy(
                                            () ->
                                                    service.receive(
                                                            notice(
                                                                    "INVALID",
                                                                    "E",
                                                                    "pricing.tax.default")))
                                    .isInstanceOf(RuntimeException.class);
                            var original = notice("ORIGINAL", null, "pricing.tax.default");
                            service.receive(original);
                            assertThatThrownBy(
                                            () ->
                                                    service.receive(
                                                            new TaxSourceNoticeContracts.Notice(
                                                                    original.sourceSystem(),
                                                                    original.eventNo(),
                                                                    original.tenantNo(),
                                                                    original.entityNo(),
                                                                    original.settingKey(),
                                                                    2L,
                                                                    original.contentHash(),
                                                                    original.effectiveAt())))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }
}
