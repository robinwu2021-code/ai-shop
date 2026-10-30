package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.StatementContracts;
import ai.neargo.shop.commerce.mapper.StatementLineMapper;
import ai.neargo.shop.commerce.mapper.StatementMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelReconciliationServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Line;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

class ChannelStatementImportTest {
    static class Gateway implements ChannelStatementGateway {
        int fetches, queries;
        Lookup sent, queried;
        Runnable before;
        final Instant now = Instant.now();
        final Document document =
                new Document(
                        "STATEMENT",
                        "R1",
                        "PP",
                        "REMOTE_ACCOUNT",
                        "CNY",
                        now.minusSeconds(86400),
                        now.minusSeconds(1),
                        now,
                        new Breakdown("100", "10", "18", "0", "3", "0", "105"),
                        List.of(
                                new Line(
                                        "SALE",
                                        "SALE",
                                        "REMOTE_STORE",
                                        "ORDER",
                                        null,
                                        new Breakdown("100", "10", "18", "0", "0", "0", "108")),
                                new Line(
                                        "FEE",
                                        "FEE",
                                        "UNKNOWN_STORE",
                                        null,
                                        null,
                                        new Breakdown("0", "0", "0", "0", "3", "0", "-3"))));

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.STATEMENT);
        }

        public Receipt fetchStatement(Lookup lookup) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            fetches++;
            sent = lookup;
            if (before != null) before.run();
            return new Receipt(
                    lookup.operationNo(),
                    PricingSnapshotHash.of(lookup),
                    Status.UNKNOWN,
                    null,
                    null,
                    null);
        }

        public Receipt queryStatement(Lookup lookup) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            queries++;
            queried = lookup;
            return new Receipt(
                    lookup.operationNo(),
                    PricingSnapshotHash.of(lookup),
                    Status.FOUND,
                    document,
                    PricingSnapshotHash.of(document),
                    "ORIGINAL_STATEMENT");
        }
    }

    @Test
    void
            persistentQueryIntentRecoversFrozenOriginalEvidenceAndNeverGuessesUnknownStoreOrLocalFunds() {
        var gateway = new Gateway();
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            s.sql()
                                    .execute(
                                            "UPDATE cim_authorization_revision SET"
                                                + " granted_scopes='[\"CATALOG\",\"STATEMENT\"]'");
                            var service =
                                    new ChannelReconciliationServiceImpl(
                                            s.context().getBean(CatalogPersistence.class),
                                            s.context().getBean(StatementMapper.class),
                                            s.context().getBean(StatementLineMapper.class),
                                            List.of(gateway),
                                            Clock.systemUTC());
                            var request =
                                    new StatementContracts.Import(
                                            "STATEMENT",
                                            "R1",
                                            PricingSnapshotHash.of(gateway.document));
                            var original =
                                    service.importStatement("E", "ACC", "ACTOR", request, "KEY");
                            assertThat(gateway.fetches).isZero();
                            gateway.before =
                                    () ->
                                            assertThat(
                                                            s.sql()
                                                                    .queryForObject(
                                                                            "SELECT COUNT(*) FROM"
                                                                                + " cim_operation_attempt",
                                                                            Integer.class))
                                                    .isOne();
                            assertThat(service.recoverDue(10)).isZero();
                            assertThat(gateway.fetches).isOne();
                            s.sql()
                                    .execute(
                                            "UPDATE cim_account SET"
                                                    + " active_authorization_no='ROTATED'");
                            s.sql()
                                    .execute(
                                            "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                                    + " 00:00:00'");
                            assertThat(service.recoverDue(10)).isOne();
                            assertThat(gateway.queried).isEqualTo(gateway.sent);
                            assertThat(gateway.fetches).isOne();
                            assertThat(gateway.queries).isOne();
                            var page =
                                    service.listStatements(
                                            "E",
                                            "ACC",
                                            new StatementContracts.Query(
                                                    null, null, null, null, 1, 20));
                            assertThat(page.records().getFirst().status()).isEqualTo("UNMAPPED");
                            assertThat(page.records().getFirst().totals().netMinor())
                                    .isEqualTo("105");
                            var store =
                                    service.listStoreLines(
                                            "E",
                                            "S",
                                            new StatementContracts.LineQuery(
                                                    original.statementNo(), null, null, 1, 20));
                            assertThat(store.records()).hasSize(1);
                            assertThat(store.records().getFirst().differenceCode())
                                    .isEqualTo("UNVERIFIED_LOCAL");
                            assertThat(
                                            service.listStoreLines(
                                                            "E",
                                                            "OTHER",
                                                            new StatementContracts.LineQuery(
                                                                    original.statementNo(),
                                                                    null,
                                                                    null,
                                                                    1,
                                                                    20))
                                                    .records())
                                    .isEmpty();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_statement_line WHERE"
                                                                    + " store_no IS NULL AND"
                                                                    + " difference_code='UNMAPPED'",
                                                            Integer.class))
                                    .isOne();
                            assertThatThrownBy(
                                            () ->
                                                    service.listStatements(
                                                            "OTHER",
                                                            "ACC",
                                                            new StatementContracts.Query(
                                                                    null, null, null, null, 1, 20)))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }
}
