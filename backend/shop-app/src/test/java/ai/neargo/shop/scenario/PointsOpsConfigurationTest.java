package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Create;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TermsChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeAction;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeChange;
import ai.neargo.shop.pay.points.admin.impl.PointsAdministrationServiceImpl;
import ai.neargo.shop.pay.points.agreement.impl.AgreementServiceImpl;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.pay.points.type.impl.PointTypeServiceImpl;
import ai.neargo.shop.payclient.PointsConfigurationAppService;
import ai.neargo.shop.payclient.impl.PointsConfigurationAppServiceImpl;
import ai.neargo.shop.portal.ops.pay.OpsPointsPolicyController;
import ai.neargo.shop.spi.points.PointsEarnPort;
import ai.neargo.shop.spi.points.PointsEarnPort.RuleMode;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOpsConfigurationTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_ops_config;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsOpsConfigurationTest {
    @Autowired PointsAdministrationService admin;
    @Autowired PointsConfigurationAppService app;
    @Autowired PointsReadStore read;
    @Autowired PointsPostingService posting;
    @Autowired EarnService earning;
    @Autowired PointsPayoutPreparationTest.TestClock clock;

    @Autowired
    @Qualifier("payDataSource")
    DataSource data;

    private final List<String> created = new ArrayList<>();

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(data);
    }

    @AfterEach
    void cleanup() {
        created.forEach(
                type ->
                        jdbc().update(
                                        "UPDATE pts_point_type SET active_market=NULL WHERE"
                                                + " type_code=?",
                                        type));
        created.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void operator_switch_replay_keeps_the_original_server_time_and_authenticated_actor() {
        String type = type();
        var request =
                new SwitchChange(
                        "SWITCH:" + type,
                        Scope.STORE,
                        type,
                        "SYNTHETIC_STORE",
                        false,
                        Flow.EARN,
                        "SYNTHETIC_PAUSE");
        clock.now = 100;
        var principal = LoginUser.operator("OPS_REAL", "Synthetic", List.of(), List.of(), null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var controller = new OpsPointsPolicyController(app);
        var result = controller.changeSwitch(request);
        assertThat(SecurityUtils.currentUserNo()).isEqualTo("OPS_REAL");
        clock.now = 1000;
        assertThat(controller.changeSwitch(request)).isEqualTo(result);
        var switches = admin.switches(type, 0, 50).rows();
        assertThat(switches).hasSize(1);
        assertThat(switches.getFirst().actor()).isEqualTo("OPS_REAL");
        assertThat(switches.getFirst().at()).isEqualTo(100);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_pause_interval WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                admin.changeSwitch(
                                        new SwitchChange(
                                                request.requestId(),
                                                Scope.STORE,
                                                type,
                                                request.subjectNo(),
                                                true,
                                                Flow.EARN,
                                                request.reason()),
                                        "OPS_REAL"))
                .hasMessage("POINTS_OPERATOR_REQUEST_CONFLICT");
        assertThat(read.pool(type).u()).isZero();
        assertThat(read.pool(type).b()).isZero();
    }

    @Test
    void fallback_revision_is_audited_replayable_and_does_not_reprice_an_existing_grant() {
        String type = type();
        assertThat(admin.fallback(type).value()).isZero();
        var first = new FallbackChange("RULE1:" + type, type, 0, RuleMode.FIXED, 4, "FIRST_RULE");
        var applied = admin.saveFallback(first, "OPS");
        assertThat(admin.saveFallback(first, "OPS")).isEqualTo(applied);
        assertThat(admin.fallback(type).revision()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                admin.saveFallback(
                                        new FallbackChange(
                                                "STALE:" + type,
                                                type,
                                                0,
                                                RuleMode.FIXED,
                                                99,
                                                "STALE"),
                                        "OPS"))
                .hasMessage("POINTS_RULE_REVISION_CONFLICT");
        fund(type);
        admin.transition(
                new TypeChange("ACTIVATE:" + type, type, TypeAction.ACTIVATE, "ACTIVATE"), "OPS");
        var original = grant(type, "OLD", admin.fallback(type).value());
        assertThat(earning.grant(original).grantedPoints()).isEqualTo(4);
        admin.saveFallback(
                new FallbackChange("RULE2:" + type, type, 1, RuleMode.FIXED, 9, "NEW_RULE"), "OPS");
        assertThat(earning.grant(original).grantedPoints()).isEqualTo(4);
        assertThat(earning.grant(grant(type, "NEW", admin.fallback(type).value())).grantedPoints())
                .isEqualTo(9);
        assertThat(read.account(type, HolderType.USER, "USER").points(Bucket.PENDING))
                .isEqualTo(13);
        assertThat(read.pool(type).c()).isEqualTo(13);
        assertThat(read.pool(type).u()).isEqualTo(87);
    }

    @Test
    void unknown_client_names_are_rejected_and_missing_scene_context_does_not_reenable_earning() {
        String type = type();
        assertThatThrownBy(
                        () ->
                                admin.changeSwitch(
                                        new SwitchChange(
                                                "BAD:" + type,
                                                Scope.CLIENT,
                                                type,
                                                "WECHAT_MP",
                                                false,
                                                Flow.EARN,
                                                "TYPO"),
                                        "OPS"))
                .hasMessage("POINTS_SWITCH_SUBJECT_INVALID");
        admin.changeSwitch(
                new SwitchChange(
                        "SCENE:" + type,
                        Scope.SCENE,
                        type,
                        "ONLINE",
                        false,
                        Flow.EARN,
                        "PAUSE_SCENE"),
                "OPS");
        assertThat(
                        read.enabled(
                                new Availability(
                                        type,
                                        "USER",
                                        "STORE",
                                        "MERCHANT",
                                        "CN",
                                        null,
                                        "H5",
                                        Flow.EARN)))
                .isFalse();
        assertThat(admin.switches(type, 0, 50).rows()).hasSize(1);
    }

    @Test
    void publishing_terms_is_atomic_and_does_not_create_a_store_signature() {
        String type = type();
        var r = new TermsChange("TERMS:" + type, type, "V1", "SYNTHETIC TERMS", "PUBLISH");
        admin.publishTerms(r, "OPS");
        admin.publishTerms(r, "OPS");
        assertThat(admin.terms(type).text()).isEqualTo("SYNTHETIC TERMS");
        assertThat(admin.agreements(type, "SYNTHETIC_STORE", 0, 50).rows()).isEmpty();
        assertThatThrownBy(
                        () ->
                                admin.publishTerms(
                                        new TermsChange(
                                                "BADTERMS:" + type,
                                                type,
                                                "V1",
                                                "ALTERED SAME VERSION",
                                                "CHANGE"),
                                        "OPS"))
                .hasMessage("POINTS_TERMS_VERSION_CONFLICT");
        assertThat(admin.terms(type).text()).isEqualTo("SYNTHETIC TERMS");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_inbox WHERE type_code=? AND"
                                                + " state='CONFIG_APPLIED'",
                                        Long.class,
                                        type))
                .isEqualTo(2);
    }

    private String type() {
        String type = "OC" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var request =
                new Create(
                        "CREATE:" + type,
                        new PointTypeService.CreateType(
                                type, "CN", new RedeemRate(1, 1), 10000, 365),
                        "CREATE");
        admin.create(request, "OPS");
        admin.create(request, "OPS");
        created.add(type);
        return type;
    }

    private PointsEarnPort.GrantCommand grant(String type, String source, long value) {
        return new PointsEarnPort.GrantCommand(
                source + type,
                source + type,
                "USER",
                type,
                "STORE",
                "TEST",
                "ONLINE",
                1,
                List.of(
                        new PointsEarnPort.EarnLine(
                                "ITEM",
                                "G",
                                "CAT",
                                1,
                                100,
                                0,
                                new PointsEarnPort.EarnRule(RuleMode.FIXED, value))));
    }

    private void fund(String type) {
        var proof = new EvidenceRef("CONFIG_FUNDS:" + type, "CNY", 200);
        posting.recordEvidence(proof);
        posting.post(
                new Voucher(
                        "CONFIG_FUNDS:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                new PointsMove(
                                        new AccountKey(
                                                type, HolderType.MINT, "PLATFORM", Bucket.ISSUED),
                                        -100),
                                new PointsMove(
                                        new AccountKey(
                                                type,
                                                HolderType.ISSUE,
                                                "PLATFORM",
                                                Bucket.ISSUABLE),
                                        100)),
                        List.of(new FundMove(FundBucket.U, 100), new FundMove(FundBucket.B, 100)),
                        List.of(),
                        0,
                        List.of(proof),
                        "{}"));
    }

    @Autowired ai.neargo.shop.pay.points.admin.PointsTreasuryService treasury;

    @Test
    void treasury_requires_a_distinct_checker_and_replays_the_original_review() {
        String type = type();
        fund(type);
        var request =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission(
                        "SUBMIT:" + type,
                        "REQ:" + type,
                        type,
                        ai.neargo.shop.pay.points.model.PoolRequest.Action.TRANSFER_U_TO_B,
                        40,
                        null,
                        null,
                        "SYNTHETIC_MOVE");
        treasury.submit(request, "MAKER");
        var review =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review(
                        "APPROVE:" + type, type, request.requestNo(), 0, true, "CHECKED");
        assertThatThrownBy(() -> treasury.review(review, "MAKER"))
                .hasMessageContaining("SELF_APPROVE");
        assertThatThrownBy(
                        () ->
                                treasury.review(
                                        new ai.neargo.shop.pay.points.admin.PointsTreasuryService
                                                .Review(
                                                "STALE:" + type,
                                                type,
                                                request.requestNo(),
                                                1,
                                                true,
                                                "STALE"),
                                        "CHECKER"))
                .hasMessageContaining("REVISION");
        var result = treasury.review(review, "CHECKER");
        assertThat(treasury.review(review, "CHECKER")).isEqualTo(result);
        assertThat(read.pool(type).u()).isEqualTo(60);
        assertThat(read.pool(type).b()).isEqualTo(140);
        assertThat(treasury.requests(type, 0, 50).rows())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.submittedBy()).isEqualTo("MAKER");
                            assertThat(r.approvedBy()).isEqualTo("CHECKER");
                            assertThat(r.state()).isEqualTo("APPROVED");
                        });
    }

    @Test
    void rejected_request_cannot_be_approved_or_change_any_money() {
        String type = type();
        var request =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission(
                        "SUBMIT:" + type,
                        "REQ:" + type,
                        type,
                        ai.neargo.shop.pay.points.model.PoolRequest.Action.TOPUP_U,
                        40,
                        "UNVERIFIED",
                        null,
                        "TEST");
        treasury.submit(request, "MAKER");
        assertThatThrownBy(
                        () ->
                                treasury.review(
                                        new ai.neargo.shop.pay.points.admin.PointsTreasuryService
                                                .Review(
                                                "NO_PROOF:" + type,
                                                type,
                                                request.requestNo(),
                                                0,
                                                true,
                                                "MISSING_PROOF"),
                                        "CHECKER"))
                .isInstanceOf(RuntimeException.class);
        treasury.review(
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review(
                        "REJECT:" + type, type, request.requestNo(), 0, false, "UNVERIFIED"),
                "CHECKER");
        assertThatThrownBy(
                        () ->
                                treasury.review(
                                        new ai.neargo.shop.pay.points.admin.PointsTreasuryService
                                                .Review(
                                                "AFTER_REJECT:" + type,
                                                type,
                                                request.requestNo(),
                                                1,
                                                true,
                                                "LATE"),
                                        "CHECKER"))
                .hasMessageContaining("STATE");
        assertThat(read.pool(type).u()).isZero();
    }

    @Autowired PointsPayoutPreparationTest.LostReplyFunds funds;

    @Test
    void treasury_unknown_withdrawal_queries_the_original_ticket_outside_the_pay_transaction() {
        String type = type();
        fund(type);
        var target =
                new ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext(
                        ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath.DIRECT,
                        "TEST",
                        "PLATFORM",
                        "PLATFORM",
                        "TEST_V1",
                        "TEST_PLATFORM",
                        null,
                        null,
                        0);
        var submit =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission(
                        "SUBMIT:" + type,
                        "REQ:" + type,
                        type,
                        ai.neargo.shop.pay.points.model.PoolRequest.Action.WITHDRAW_U,
                        20,
                        null,
                        target,
                        "TEST");
        treasury.submit(submit, "MAKER");
        treasury.review(
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review(
                        "REVIEW:" + type, type, submit.requestNo(), 0, true, "TEST"),
                "CHECKER");
        String ticket = treasury.requests(type, 0, 50).rows().getFirst().ticketNo();
        var command =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Withdrawal(
                        "EXEC:" + type, type, ticket, "RESOLVE");
        assertThat(treasury.executeWithdrawal(command, "CHECKER").status())
                .isEqualTo(ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(read.pool(type).u()).isEqualTo(100);
        assertThat(read.pool(type).lockedUMinor()).isEqualTo(20);
        assertThat(treasury.executeWithdrawal(command, "CHECKER").status())
                .isEqualTo(ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus.SUCCEEDED);
        assertThat(funds.sends.get(ticket)).isEqualTo(1);
        assertThat(funds.queries.get(ticket)).isEqualTo(1);
        assertThat(read.pool(type).u()).isEqualTo(80);
        assertThat(read.pool(type).lockedUMinor()).isZero();
    }

    @Test
    void treasury_plan_periods_wait_for_receipts_and_reject_stale_revisions() {
        String type = type();
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 16);
        clock.now =
                day.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        var plan =
                new ai.neargo.shop.pay.points.pool.TopupPlanService.Plan(
                        "PLAN:" + type,
                        type,
                        ai.neargo.shop.pay.points.model.PoolRequest.Action.TOPUP_B,
                        20,
                        day,
                        1,
                        true);
        treasury.savePlan(
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanChange(
                        "PLAN_SAVE:" + type, plan, -1, "TEST"),
                "MAKER");
        assertThatThrownBy(
                        () ->
                                treasury.savePlan(
                                        new ai.neargo.shop.pay.points.admin.PointsTreasuryService
                                                .PlanChange("PLAN_STALE:" + type, plan, -1, "TEST"),
                                        "MAKER"))
                .hasMessageContaining("REVISION");
        var generation =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.GeneratePeriods(
                        "GENERATE:" + type, type, 1, "TEST");
        treasury.generatePeriods(generation, "MAKER");
        treasury.generatePeriods(generation, "MAKER");
        var period =
                treasury.plans(type, 0, 50).rows().stream()
                        .filter(p -> !"CONFIG".equals(p.period()))
                        .findFirst()
                        .orElseThrow();
        assertThat(period.state()).isEqualTo("AWAITING_RECEIPT");
        assertThat(treasury.requests(type, 0, 50).rows()).isEmpty();
        var receipt =
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanReceipt(
                        "DECLARE:" + type,
                        type,
                        plan.planNo(),
                        day,
                        "UNVERIFIED",
                        period.version(),
                        "TEST");
        var declared = treasury.declareReceipt(receipt, "MAKER");
        assertThat(treasury.declareReceipt(receipt, "MAKER")).isEqualTo(declared);
        assertThat(treasury.requests(type, 0, 50).rows()).hasSize(1);
        assertThat(read.pool(type).b()).isZero();
    }

    @Test
    void treasury_generates_due_periods_using_server_clock_in_the_types_market_zone() {
        String type = type();
        clock.now = java.time.Instant.parse("2099-01-01T16:30:00Z").toEpochMilli();
        var day = java.time.LocalDate.of(2099, 1, 2);
        var plan =
                new ai.neargo.shop.pay.points.pool.TopupPlanService.Plan(
                        "PLAN:" + type,
                        type,
                        ai.neargo.shop.pay.points.model.PoolRequest.Action.TOPUP_B,
                        20,
                        day,
                        1,
                        true);
        treasury.savePlan(
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.PlanChange(
                        "SAVE:" + type, plan, -1, "TEST"),
                "MAKER");
        treasury.generatePeriods(
                new ai.neargo.shop.pay.points.admin.PointsTreasuryService.GeneratePeriods(
                        "GENERATE:" + type, type, 5, "TEST"),
                "MAKER");
        assertThat(treasury.plans(type, 0, 50).rows())
                .filteredOn(p -> !"CONFIG".equals(p.period()))
                .singleElement()
                .satisfies(p -> assertThat(p.period()).isEqualTo("2099-01-02"));
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(data);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({
        PointsPayoutPreparationTest.Config.class,
        PointsAdministrationServiceImpl.class,
        ai.neargo.shop.pay.points.admin.impl.PointsTreasuryServiceImpl.class,
        ai.neargo.shop.pay.points.admin.PointsOperatorJournal.class,
        ai.neargo.shop.pay.points.pool.impl.PoolServiceImpl.class,
        ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl.class,
        PointsConfigurationAppServiceImpl.class,
        PointTypeServiceImpl.class,
        AgreementServiceImpl.class
    })
    static class Config {
        @Bean
        MarketService markets() {
            var markets = mock(MarketService.class);
            when(markets.find("CN"))
                    .thenReturn(
                            Optional.of(
                                    new MarketService.MarketRow(
                                            "CN",
                                            "Synthetic",
                                            "CNY",
                                            2,
                                            "Asia/Shanghai",
                                            1,
                                            true,
                                            0)));
            return markets;
        }

        @Bean
        MerchantQueryPort merchants() {
            return mock(MerchantQueryPort.class);
        }

        @Bean
        StoreDirectoryPort stores() {
            return mock(StoreDirectoryPort.class);
        }
    }
}
