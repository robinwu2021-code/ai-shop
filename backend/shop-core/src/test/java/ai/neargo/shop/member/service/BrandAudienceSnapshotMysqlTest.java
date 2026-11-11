package ai.neargo.shop.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.BrandAudienceSnapshotPort.Context;
import ai.neargo.shop.spi.member.BrandAudienceSnapshotPort.Skip;
import ai.neargo.shop.member.service.impl.BrandAudienceSnapshotServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort.Identity;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Native JSON, current-read locks and immutable claim receipts require the isolated MySQL 9 fixture. */
@EnabledIfEnvironmentVariable(named = "BRAND_AUDIENCE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BrandAudienceSnapshotMysqlTest {
    private static final Context CONTEXT = new Context("MAIN", "M1", "B1", "E1", "A1");

    @Test
    void allBrandMembersUseCanonicalAccountsAcrossSourceEntitiesWithConservedSkips() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", "U1", true);
            f.member("P2", "LEAD", null, true);
            f.member("P3", "BLOCKED", "U3", true);
            f.member("P4", "ACTIVE", "U4", false);
            f.member("P5", "ACTIVE", "U5", true);
            f.member("P6", "ACTIVE", "U5", true);
            f.member("P7", "ACTIVE", "U7", true);
            f.active.remove("U7");
            f.jdbc.update("UPDATE mbr_scope_member SET first_store_no='OTHER_ENTITY_STORE' WHERE customer_no='P1'");
            f.jdbc.update("INSERT INTO mbr_scope_member VALUES('MAIN','OTHER','BRAND','B2','PX','S2','ACTIVE',0,0)");
            f.jdbc.update("INSERT INTO mbr_scope_member VALUES('MAIN','STORE','STORE','S1','PY','S1','ACTIVE',0,0)");
            var snapshot = f.service.freeze(CONTEXT, "CP1", "V1");
            assertThat(snapshot.plannedCount()).isEqualTo(7);
            assertThat(snapshot.preSkippedCount()).isEqualTo(3);
            assertThat(snapshot.preSkipReasons()).containsExactlyInAnyOrder(
                    new Skip("LEAD", 1), new Skip("IDENTITY_CONFLICT", 1), new Skip("DUPLICATE_ALIAS", 1));
            assertThat(snapshot.recipients()).containsExactly(
                    new Recipient("U1", true, null), new Recipient("U3", false, "BLOCKED"),
                    new Recipient("U5", true, null), new Recipient("U7", false, "ACCOUNT_UNAVAILABLE"));
            assertThat(snapshot.preSkipReasons().stream().mapToInt(Skip::count).sum())
                    .isEqualTo(snapshot.preSkippedCount());
            assertThat(snapshot.plannedCount()).isEqualTo(snapshot.preSkippedCount() + snapshot.recipients().size());
            assertThat(snapshot.expiresAt()).isBetween(Instant.now().plusSeconds(590), Instant.now().plusSeconds(600));
            assertThatThrownBy(() -> snapshot.recipients().clear()).isInstanceOf(UnsupportedOperationException.class);
            String persisted = f.jdbc.queryForObject("SELECT member_facts_json FROM mbr_brand_audience_snapshot",
                    String.class);
            assertThat(persisted).doesNotContain("phone", "138", "remark", "OTHER_ENTITY_STORE");
        }
    }

    @Test
    void blockedOrConflictingAliasesCannotBeBypassedByAnActiveAlias() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", "U1", true);
            f.member("P2", "BLOCKED", "U1", true);
            f.member("P3", "ACTIVE", "U2", true);
            f.member("P4", "ACTIVE", "U2", false);
            var snapshot = f.service.freeze(CONTEXT, "CP1", "V1");
            assertThat(snapshot.recipients()).isEmpty();
            assertThat(snapshot.preSkippedCount()).isEqualTo(4);
            assertThat(snapshot.preSkipReasons()).containsExactlyInAnyOrder(
                    new Skip("BLOCKED_ALIAS", 2), new Skip("IDENTITY_CONFLICT", 2));
        }
    }

    @Test
    void missingOrMismatchedCanonicalIdentityNeverBecomesARecipient() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", null, true);
            f.member("P2", "ACTIVE", "U2", true);
            f.identities.remove("P2");
            f.member("P3", "ACTIVE", "U3", true);
            f.identities.put("P3", new Identity("OTHER_CUSTOMER", "U3", null, true));
            var snapshot = f.service.freeze(CONTEXT, "CP1", "V1");
            assertThat(snapshot.recipients()).isEmpty();
            assertThat(snapshot.preSkippedCount()).isEqualTo(3);
            assertThat(snapshot.preSkipReasons()).containsExactlyInAnyOrder(new Skip("NO_USER", 1),
                    new Skip("IDENTITY_UNAVAILABLE", 1), new Skip("IDENTITY_CONFLICT", 1));
        }
    }

    @Test
    void oldSnapshotNeverAddsNewMembersAndClaimedReplaySurvivesExpiryAndLaterChanges() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", "U1", true);
            var frozen = f.service.freeze(CONTEXT, "CP1", "V1");
            f.member("P2", "ACTIVE", "U2", true);
            assertThat(f.service.get(CONTEXT, frozen.snapshotNo(), "CP1", "V1").recipients())
                    .containsExactly(new Recipient("U1", true, null));
            var claimed = f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V1", "CMD1");
            f.jdbc.update("UPDATE mbr_scope_member SET status='BLOCKED',version=version+1");
            f.active.clear();
            f.expire();
            var replay = f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V1", "CMD1");
            assertThat(replay.recipients()).isEqualTo(claimed.recipients());
            assertThat(replay.claimedCommandNo()).isEqualTo("CMD1");
            assertThat(f.service.get(CONTEXT, frozen.snapshotNo(), "CP1", "V1")).isEqualTo(replay);
            assertThatThrownBy(() -> f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V1", "CMD2"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    void memberVersionStatusDeletionIdentityAndAccountChangesRejectFirstClaim() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", "U1", true);
            for (String mutation : List.of("VERSION", "STATUS", "DELETED", "IDENTITY", "ACCOUNT")) {
                f.jdbc.update("UPDATE mbr_scope_member SET status='ACTIVE',deleted=0,version=0");
                f.identities.put("P1", new Identity("P1", "U1", "138****8000", true));
                f.active.add("U1");
                var frozen = f.service.freeze(CONTEXT, "CP1", "V1");
                switch (mutation) {
                    case "VERSION" -> f.jdbc.update("UPDATE mbr_scope_member SET version=1");
                    case "STATUS" -> f.jdbc.update("UPDATE mbr_scope_member SET status='BLOCKED'");
                    case "DELETED" -> f.jdbc.update("UPDATE mbr_scope_member SET deleted=1");
                    case "IDENTITY" -> f.identities.put("P1", new Identity("P1", "U2", null, true));
                    case "ACCOUNT" -> f.active.remove("U1");
                    default -> throw new AssertionError(mutation);
                }
                assertThatThrownBy(() -> f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V1", mutation))
                        .as(mutation).isInstanceOf(BizException.class);
                assertThat(f.jdbc.queryForObject("SELECT claimed_command_no FROM mbr_brand_audience_snapshot "
                        + "WHERE snapshot_no=?", String.class, frozen.snapshotNo())).isNull();
            }
        }
    }

    @Test
    void competingCommandsCannotBothClaimTheSameSnapshot() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(2)) {
            f.member("P1", "ACTIVE", "U1", true);
            var frozen = f.service.freeze(CONTEXT, "CP1", "V1");
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> claimAfterGate(f, frozen.snapshotNo(), "CMD1", gate));
            var second = pool.submit(() -> claimAfterGate(f, frozen.snapshotNo(), "CMD2", gate));
            gate.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).containsOnlyOnce("REJECTED");
            String accepted = results.stream().filter(result -> !"REJECTED".equals(result)).findFirst().orElseThrow();
            assertThat(f.service.get(CONTEXT, frozen.snapshotNo(), "CP1", "V1").claimedCommandNo())
                    .isEqualTo(accepted);
        }
    }

    private String claimAfterGate(Fixture fixture, String snapshotNo, String command, CountDownLatch gate)
            throws InterruptedException {
        gate.await();
        try {
            return fixture.service.claim(CONTEXT, snapshotNo, "CP1", "V1", command).claimedCommandNo();
        } catch (BizException conflict) {
            return "REJECTED";
        }
    }

    @Test
    void snapshotIsBoundToTenantManagementBrandIssuerActorResourceAndVersionAndUnclaimedExpiry() throws Exception {
        try (var f = new Fixture()) {
            f.member("P1", "ACTIVE", "U1", true);
            var frozen = f.service.freeze(CONTEXT, "CP1", "V1");
            for (Context other : List.of(new Context("OTHER", "M1", "B1", "E1", "A1"),
                    new Context("MAIN", "M2", "B1", "E1", "A1"),
                    new Context("MAIN", "M1", "B2", "E1", "A1"),
                    new Context("MAIN", "M1", "B1", "E2", "A1"),
                    new Context("MAIN", "M1", "B1", "E1", "A2"))) {
                assertThatThrownBy(() -> f.service.get(other, frozen.snapshotNo(), "CP1", "V1"))
                        .isInstanceOf(BizException.class);
                assertThatThrownBy(() -> f.service.claim(other, frozen.snapshotNo(), "CP1", "V1", "CMD1"))
                        .isInstanceOf(BizException.class);
            }
            assertThatThrownBy(() -> f.service.get(CONTEXT, frozen.snapshotNo(), "CP2", "V1"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V2", "CMD1"))
                    .isInstanceOf(BizException.class);
            f.expire();
            assertThatThrownBy(() -> f.service.get(CONTEXT, frozen.snapshotNo(), "CP1", "V1"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.claim(CONTEXT, frozen.snapshotNo(), "CP1", "V1", "CMD1"))
                    .isInstanceOf(BizException.class);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String database = "brand_audience_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final JdbcTemplate jdbc;
        private final Map<String, Identity> identities = new HashMap<>();
        private final Set<String> active = new HashSet<>();
        private final BrandAudienceSnapshotService service;

        private Fixture() throws Exception {
            String url = System.getenv("BRAND_AUDIENCE_MYSQL_URL");
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/(?:\\?.*)?")) {
                throw new IllegalArgumentException("Only the local 3308 server root URL is accepted");
            }
            String user = System.getenv("BRAND_AUDIENCE_MYSQL_USER");
            String password = System.getenv("BRAND_AUDIENCE_MYSQL_PASSWORD");
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            if (!admin.queryForObject("SELECT VERSION()", String.class).startsWith("9.")) {
                throw new IllegalStateException("MySQL 9 is required");
            }
            admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            var source = new DriverManagerDataSource(url.replace(":3308/", ":3308/" + database), user, password);
            jdbc = new JdbcTemplate(source);
            try {
                String migration = Files.readString(
                        Path.of("../shop-app/src/main/resources/db/migration/V652__brand_promotion.sql"));
                var ddl = Pattern.compile("(?s)CREATE TABLE mbr_brand_audience_snapshot\\s*\\(.*?;")
                        .matcher(migration);
                if (!ddl.find()) throw new IllegalStateException("Production snapshot DDL is missing");
                jdbc.execute(ddl.group());
                jdbc.execute("CREATE TABLE mbr_scope_member (tenant_no VARCHAR(64),scope_member_no VARCHAR(64),"
                        + "scope_type VARCHAR(16),scope_no VARCHAR(64),customer_no VARCHAR(64),"
                        + "first_store_no VARCHAR(64),status VARCHAR(24),version BIGINT,deleted TINYINT,"
                        + "PRIMARY KEY(tenant_no,scope_member_no)) ENGINE=InnoDB");
                var identityPort = mock(MemberIdentityFactsPort.class);
                when(identityPort.find(anyString(), anyString())).thenAnswer(call ->
                        Optional.ofNullable(identities.get(call.getArgument(1))));
                var userPort = mock(UserScopeFactsPort.class);
                when(userPort.findActive(anyString(), anyString())).thenAnswer(call -> {
                    String no = call.getArgument(1);
                    return active.contains(no) ? Optional.of(new UserScopeFactsPort.ActiveUser(no, "Member"))
                            : Optional.empty();
                });
                var brands = mock(BrandPromotionScopePort.class);
                when(brands.find(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
                    if (!"MAIN".equals(call.getArgument(0)) || !"M1".equals(call.getArgument(1))
                            || !"B1".equals(call.getArgument(2)) || !"E1".equals(call.getArgument(3))) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    return new BrandPromotionScopePort.Brand("M1", "B1", "E1", 0, 0, List.of());
                });
                var json = new ObjectMapper();
                service = new BrandAudienceSnapshotServiceImpl(new MemberScopeRepository(jdbc,
                        new DataSourceTransactionManager(source), json), identityPort, userPort, brands, json);
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private void member(String customer, String status, String user, boolean consistent) {
            jdbc.update("INSERT INTO mbr_scope_member VALUES('MAIN',?,'BRAND','B1',?,'S1',?,0,0)",
                    "SM" + customer, customer, status);
            identities.put(customer, new Identity(customer, user, "138****8000", consistent));
            if (user != null) active.add(user);
        }

        private void expire() {
            jdbc.update("UPDATE mbr_brand_audience_snapshot SET expires_at=?",
                    LocalDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC));
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
