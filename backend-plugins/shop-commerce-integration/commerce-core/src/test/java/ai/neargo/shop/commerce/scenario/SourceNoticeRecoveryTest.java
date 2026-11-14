package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class SourceNoticeRecoveryTest {
    CreateCatalogScopeReq scope(String connection) {
        return new CreateCatalogScopeReq(
                connection, "STORE", "Menu", List.of(new SourceReq(Owner.FOOD, "MENU", "R1", 0)));
    }

    @Test
    void
            futureNoticeCannotBeConsumedEarlyEvenIfWakeupIsRequestedAndReplayKeepsTheOriginalIdentity() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var service = s.context().getBean(SourceNoticeService.class);
                            var future = Instant.now().plusSeconds(3600).plusNanos(123456);
                            var notice =
                                    new Notice(
                                            "PLATFORM",
                                            "FUTURE",
                                            "E",
                                            "S",
                                            Owner.FOOD,
                                            "MENU",
                                            future);
                            var receipt = service.receive(notice);
                            assertThat(service.receive(notice)).isEqualTo(receipt);
                            assertThat(receipt.readyAt()).isAfterOrEqualTo(future);
                            s.sql()
                                    .execute(
                                            "UPDATE cim_source_notice SET next_check_at='2000-01-01"
                                                + " 00:00:00'");
                            assertThat(service.recoverDue(10)).isZero();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM cim_source_notice",
                                                            String.class))
                                    .isEqualTo("PENDING");
                            assertThat(s.source().currentReads).isZero();
                        });
    }

    @Test
    void reenablingFollowsOriginalHeadAfterDisabledNotificationsAndKeepsAnEventOnlyTimer() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var created =
                                    s.scopes().createScope("E", "S", "A", scope("CON"), "scope");
                            var notices = s.context().getBean(SourceNoticeService.class);
                            s.source().headRevision = "R2";
                            notices.receive(
                                    new Notice(
                                            "PLATFORM",
                                            "WHILE_OFF",
                                            "E",
                                            "S",
                                            Owner.FOOD,
                                            "MENU",
                                            Instant.now().minusSeconds(1)));
                            assertThat(notices.recoverDue(10)).isOne();
                            assertThat(s.source().currentReads).isZero();
                            var policy =
                                    s.policies()
                                            .savePolicy(
                                                    "E",
                                                    "S",
                                                    created.scopeNo(),
                                                    "A",
                                                    new SaveSyncPolicyReq(
                                                            0L,
                                                            true,
                                                            true,
                                                            false,
                                                            false,
                                                            0,
                                                            "QUERY_ONLY"),
                                                    "enable");
                            assertThat(policy.enabled()).isTrue();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_sync_policy"
                                                                + " WHERE next_check_at IS NOT"
                                                                + " NULL",
                                                            Integer.class))
                                    .isOne();
                            s.sql()
                                    .execute(
                                            "UPDATE cim_sync_policy SET next_check_at='2000-01-01"
                                                + " 00:00:00'");
                            s.recovery().recoverDue(10);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT selection_revision FROM"
                                                                + " cim_catalog_source WHERE"
                                                                + " status='ACTIVE'",
                                                            String.class))
                                    .isEqualTo("R2");
                            assertThat(s.source().currentReads).isPositive();
                        });
    }

    @Test
    void oneNoticeDoesNotBroadcastEachScopeBackOntoEveryOtherScope() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            s.sql()
                                    .update(
                                            """
                                            INSERT INTO cim_store_connection(connection_no,entity_no,store_no,profile_no,account_no,
                                                external_store_ref,channel_store_key,active_revision_no,status,
                                                identity_receipt_ref,identity_verified_at,identity_valid_until)
                                            SELECT 'CON2',entity_no,store_no,profile_no,account_no,'REMOTE_STORE2',?,'CR2',status,
                                                identity_receipt_ref,identity_verified_at,identity_valid_until
                                            FROM cim_store_connection WHERE connection_no='CON'
                                            """,
                                            "e".repeat(64));
                            s.sql()
                                    .execute(
                                            """
                                            INSERT INTO cim_connection_revision(revision_no,entity_no,connection_no,revision,source_owner,
                                                accept_mode,fulfillment_profile,catalog_authority,config_json,config_hash)
                                            SELECT 'CR2',entity_no,'CON2',revision,source_owner,accept_mode,fulfillment_profile,
                                                catalog_authority,config_json,config_hash FROM cim_connection_revision WHERE revision_no='CR'
                                            """);
                            var first = s.scopes().createScope("E", "S", "A", scope("CON"), "one");
                            var second =
                                    s.scopes().createScope("E", "S", "A", scope("CON2"), "two");
                            for (var target : List.of(first, second))
                                s.policies()
                                        .savePolicy(
                                                "E",
                                                "S",
                                                target.scopeNo(),
                                                "A",
                                                new SaveSyncPolicyReq(
                                                        0L,
                                                        true,
                                                        true,
                                                        false,
                                                        false,
                                                        0,
                                                        "QUERY_ONLY"),
                                                "enable:" + target.scopeNo());
                            s.source().headRevision = "R2";
                            var notices = s.context().getBean(SourceNoticeService.class);
                            notices.receive(
                                    new Notice(
                                            "PLATFORM",
                                            "PUBLISHED",
                                            "E",
                                            "S",
                                            Owner.FOOD,
                                            "MENU",
                                            Instant.now().minusSeconds(1)));
                            assertThat(notices.recoverDue(10)).isOne();
                            assertThat(
                                            s.sql()
                                                    .queryForList(
                                                            "SELECT version FROM cim_catalog_scope"
                                                                + " ORDER BY id",
                                                            Long.class))
                                    .containsExactly(1L, 1L);
                            assertThat(
                                            s.sql()
                                                    .queryForList(
                                                            "SELECT version FROM cim_sync_policy"
                                                                + " ORDER BY id",
                                                            Long.class))
                                    .containsExactly(2L, 2L);
                        });
    }
}
