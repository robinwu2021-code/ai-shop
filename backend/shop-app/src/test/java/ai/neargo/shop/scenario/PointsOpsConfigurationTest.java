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

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(data);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({
        PointsPayoutPreparationTest.Config.class,
        PointsAdministrationServiceImpl.class,
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
