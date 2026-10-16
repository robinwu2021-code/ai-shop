package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.config.PayDataSourceConfig;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.posting.impl.PointsPostingServiceImpl;
import ai.neargo.shop.pay.points.store.impl.MybatisPostingStore;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;

import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

/**
 * Same real pay factory/posting code can run on H2 or an isolated real SQL server; never points
 * test doubles.
 */
@SpringJUnitConfig(PointsPostingDatabaseTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_posting_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsPostingDatabaseTest {
    @Autowired private PointsPostingService posting;

    @Autowired
    @Qualifier("payDataSource")
    private DataSource dataSource;

    @BeforeAll
    void schema() {
        initializeSchema(dataSource);
    }

    static void initializeSchema(DataSource dataSource) {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V408__points_v3_ledger.sql"))
                .execute(dataSource);
        try (var connection = dataSource.getConnection();
                var columns =
                        connection
                                .getMetaData()
                                .getColumns(connection.getCatalog(), null, "pts_fund_pool", null)) {
            boolean installed = false;
            while (columns.next()) {
                installed |= "locked_u_minor".equalsIgnoreCase(columns.getString("COLUMN_NAME"));
            }
            if (!installed) {
                new ResourceDatabasePopulator(
                                new ClassPathResource(
                                        "db/migration/V411__points_withdrawal_budget_locks.sql"))
                        .execute(dataSource);
            }
            try (var evidenceColumns =
                    connection
                            .getMetaData()
                            .getColumns(connection.getCatalog(), null, "pts_fund_evidence", null)) {
                boolean bound = false;
                while (evidenceColumns.next()) {
                    bound |=
                            "binding_purpose"
                                    .equalsIgnoreCase(evidenceColumns.getString("COLUMN_NAME"));
                }
                if (!bound) {
                    new ResourceDatabasePopulator(
                                    new ClassPathResource(
                                            "db/migration/V412__points_evidence_business_binding.sql"))
                            .execute(dataSource);
                }
            }
            try (var routeColumns =
                    connection
                            .getMetaData()
                            .getColumns(
                                    connection.getCatalog(), null, "pts_store_statement", null)) {
                boolean routeInstalled = false;
                while (routeColumns.next()) {
                    routeInstalled |=
                            "route_key".equalsIgnoreCase(routeColumns.getString("COLUMN_NAME"));
                }
                if (!routeInstalled) {
                    new ResourceDatabasePopulator(
                                    new ClassPathResource(
                                            "db/migration/V424__points_statement_original_route_groups.sql"))
                            .execute(dataSource);
                }
            }
            try (var ruleColumns =
                    connection
                            .getMetaData()
                            .getColumns(connection.getCatalog(), null, "pts_point_type", null)) {
                boolean ruleInstalled = false;
                while (ruleColumns.next()) {
                    ruleInstalled |=
                            "fallback_rule_json"
                                    .equalsIgnoreCase(ruleColumns.getString("COLUMN_NAME"));
                }
                if (!ruleInstalled) {
                    new ResourceDatabasePopulator(
                                    new ClassPathResource(
                                            "db/migration/V425__points_type_fallback_rule.sql"))
                            .execute(dataSource);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Points schema fixture failed", failure);
        }
    }

    @Test
    void competing_grants_cannot_spend_the_same_last_budget() throws Exception {
        String type = fixture();
        posting.post(topup(type, "F:" + type));
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> grant1 = () -> grantAfterBarrier(type, "U1", start);
        Callable<Boolean> grant2 = () -> grantAfterBarrier(type, "U2", start);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = workers.invokeAll(List.of(grant1, grant2));
            int wins = 0;
            for (var future : futures) {
                if (future.get()) {
                    wins++;
                }
            }
            assertThat(wins).isEqualTo(1);
        }
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT c_minor FROM pts_fund_pool WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(100L);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT u_minor FROM pts_fund_pool WHERE type_code=?",
                                        Long.class,
                                        type))
                .isZero();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(2L);
    }

    @Test
    void one_money_evidence_cannot_fund_two_vouchers() {
        String type = fixture();
        posting.post(topup(type, "F1:" + type));
        assertThatThrownBy(() -> posting.post(topup(type, "F2:" + type)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT u_minor FROM pts_fund_pool WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(100L);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(1L);
    }

    @Test
    void reversed_account_input_order_has_no_deadlock_or_lost_update() throws Exception {
        String type = fixture();
        posting.post(topup(type, "F1:" + type));
        jdbc().update(
                        "INSERT INTO"
                                + " pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                                + " VALUES(?,'CNY',100,1)",
                        "E2:" + type);
        Voucher fund2 = topup(type, "F2:" + type);
        posting.post(
                new Voucher(
                        fund2.bizRef(),
                        type,
                        "CNY",
                        fund2.rate(),
                        fund2.pointsMoves(),
                        fund2.fundMoves(),
                        List.of(),
                        0,
                        List.of(new EvidenceRef("E2:" + type, "CNY", 100)),
                        "{}"));
        posting.post(
                new Voucher(
                        "G2:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, -200),
                                move(type, HolderType.USER, "A", Bucket.AVAILABLE, 100),
                                move(type, HolderType.USER, "B", Bucket.AVAILABLE, 100)),
                        List.of(new FundMove(FundBucket.U, -200), new FundMove(FundBucket.C, 200)),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        Voucher aToB =
                new Voucher(
                        "AB:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.USER, "A", Bucket.AVAILABLE, -50),
                                move(type, HolderType.USER, "B", Bucket.AVAILABLE, 50)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}");
        Voucher bToA =
                new Voucher(
                        "BA:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.USER, "B", Bucket.AVAILABLE, -50),
                                move(type, HolderType.USER, "A", Bucket.AVAILABLE, 50)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results =
                    workers.invokeAll(
                            List.<Callable<Boolean>>of(
                                    () -> {
                                        start.await();
                                        posting.post(aToB);
                                        return true;
                                    },
                                    () -> {
                                        start.await();
                                        posting.post(bToA);
                                        return true;
                                    }));
            for (var result : results) {
                assertThat(result.get()).isTrue();
            }
        }
        assertThat(
                        jdbc().queryForList(
                                        "SELECT available_points FROM pts_account WHERE type_code=?"
                                                + " AND holder_type='USER' ORDER BY holder_no",
                                        Long.class,
                                        type))
                .containsExactly(100L, 100L);
    }

    @Test
    void reserve_position_is_atomic_and_release_restores_exact_free_budget() {
        String type = fixture();
        posting.post(topup(type, "F:" + type));
        posting.post(
                new Voucher(
                        "G:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, -100),
                                move(type, HolderType.USER, "U", Bucket.AVAILABLE, 100)),
                        List.of(new FundMove(FundBucket.U, -100), new FundMove(FundBucket.C, 100)),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        jdbc().update(
                        "INSERT INTO"
                                + " pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                                + " VALUES(?,'CNY',100,1)",
                        "B:" + type);
        posting.post(
                new Voucher(
                        "B:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(),
                        List.of(new FundMove(FundBucket.B, 100)),
                        List.of(),
                        0,
                        List.of(new EvidenceRef("B:" + type, "CNY", 100)),
                        "{}"));
        String position = "P:" + type;
        String reserve = "R:" + type;
        posting.post(
                new Voucher(
                        "H:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.USER, "U", Bucket.AVAILABLE, -100),
                                move(type, HolderType.USER, "U", Bucket.HELD, 100)),
                        List.of(),
                        List.of(
                                new PositionMove(
                                        PositionMove.Kind.REDEEM,
                                        position,
                                        -1,
                                        "NEW",
                                        "HELD",
                                        100,
                                        0,
                                        "{}")),
                        100,
                        List.of(),
                        "{}",
                        List.of(new ReserveMove(reserve, position, -1, 100, 100, 0, 0))));
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT reserved_b_minor FROM pts_fund_pool WHERE"
                                                + " type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(100L);
        posting.post(
                new Voucher(
                        "RELEASE:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.USER, "U", Bucket.HELD, -100),
                                move(type, HolderType.USER, "U", Bucket.AVAILABLE, 100)),
                        List.of(),
                        List.of(
                                new PositionMove(
                                        PositionMove.Kind.REDEEM,
                                        position,
                                        1,
                                        "HELD",
                                        "RELEASED",
                                        100,
                                        0,
                                        "{}")),
                        -100,
                        List.of(),
                        "{}",
                        List.of(new ReserveMove(reserve, position, 1, 100, -100, 0, 100))));
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT reserved_b_minor FROM pts_fund_pool WHERE"
                                                + " type_code=?",
                                        Long.class,
                                        type))
                .isZero();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT released_minor FROM pts_refund_reserve WHERE"
                                                + " reserve_no=?",
                                        Long.class,
                                        reserve))
                .isEqualTo(100L);
    }

    private boolean grantAfterBarrier(String type, String user, CyclicBarrier barrier)
            throws Exception {
        barrier.await();
        try {
            posting.post(
                    new Voucher(
                            "G:" + type + ":" + user,
                            type,
                            "CNY",
                            new RedeemRate(1, 1),
                            List.of(
                                    move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, -100),
                                    move(type, HolderType.USER, user, Bucket.PENDING, 100)),
                            List.of(
                                    new FundMove(FundBucket.U, -100),
                                    new FundMove(FundBucket.C, 100)),
                            List.of(),
                            0,
                            List.of(),
                            "{}"));
            return true;
        } catch (IllegalStateException rejected) {
            assertThat(rejected.getMessage()).isEqualTo("POINTS_BALANCE_INSUFFICIENT");
            return false;
        }
    }

    private String fixture() {
        String type = "DB" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update(
                        "INSERT INTO"
                            + " pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                            + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",
                        type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        jdbc().update(
                        "INSERT INTO"
                                + " pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                                + " VALUES(?,'CNY',100,1)",
                        "E:" + type);
        return type;
    }

    private Voucher topup(String type, String ref) {
        return new Voucher(
                ref,
                type,
                "CNY",
                new RedeemRate(1, 1),
                List.of(
                        move(type, HolderType.MINT, "PLATFORM", Bucket.ISSUED, -100),
                        move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, 100)),
                List.of(new FundMove(FundBucket.U, 100)),
                List.of(),
                0,
                List.of(new EvidenceRef("E:" + type, "CNY", 100)),
                "{}");
    }

    private PointsMove move(String type, HolderType holder, String no, Bucket bucket, long amount) {
        return new PointsMove(new AccountKey(type, holder, no, bucket), amount);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration
    @EnableTransactionManagement
    @Import({PayDataSourceConfig.class, PointsPostingServiceImpl.class, MybatisPostingStore.class})
    static class Config {
        @Bean
        MybatisPlusInterceptor interceptor() {
            return new MybatisPlusInterceptor();
        }

        @Bean
        MetaObjectHandler metaObjectHandler() {
            return new MetaObjectHandler() {
                @Override
                public void insertFill(MetaObject metaObject) {}

                @Override
                public void updateFill(MetaObject metaObject) {}
            };
        }
    }
}
