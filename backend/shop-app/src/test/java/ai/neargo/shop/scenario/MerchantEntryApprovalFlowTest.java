package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.api.ops.OpsPlatformController;
import ai.neargo.shop.platform.approval.MerchantEntryApprovalSubject;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * T10 · 入驻审核接入审批（方案-第一批审批工作流 T10 / Q7）：启用后「通过」只带拟定结果送审、不建主体；
 * 审批通过（执行节点）才按冻结的拟定结果建主体；申请单变了判 STALE；驳回仍当场生效。审批端口用替身记录送审。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("入驻审核接入审批：通过 = 带拟定结果送审，审批通过才建主体")
class MerchantEntryApprovalFlowTest {

    /** 记录送审的审批端口替身：只对入驻启用。 */
    static final List<Object[]> SUBMITS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeApprovals {
        @Bean
        ApprovalPort fakeApprovalPort() {
            return new ApprovalPort() {
                @Override public boolean enabled(String type) { return MerchantEntryApprovalSubject.SUBJECT_TYPE.equals(type); }
                @Override public void submit(String type, String ref, long revision, Submitter submitter) { submit(type, ref, revision, submitter, Map.of()); }
                @Override public void submit(String type, String ref, long revision, Submitter submitter, Map<String, Object> proposal) {
                    SUBMITS.add(new Object[]{type, ref, revision, submitter.staffNo(), proposal});
                }
                @Override public Optional<Link> latest(String type, String ref) {
                    return SUBMITS.stream().filter(s -> s[1].equals(ref)).reduce((a, b) -> b)
                            .map(s -> new Link(type, ref, (Long) s[2], "shop_merchant_entry", null, null, "PENDING", null));
                }
            };
        }
    }

    @Autowired OpsService opsService;
    @Autowired OpsPlatformController opsController;
    @Autowired MerchantEntryApprovalSubject subject;
    @Autowired UserProvisionPort userProvision;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void asOperator() {
        SUBMITS.clear();
        var user = new LoginUser(Realm.OPERATOR, SubjectKind.OPS, "OPS_APV", "审核运营", List.of("SUPER_ADMIN"), List.of("*"), null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★★ 通过 → 只送审（申请单 REVIEWING、无主体）；审批通过 → 按拟定结果建主体；重投幂等")
    void approveSubmitsThenApplyCreatesEntity() {
        String owner = userProvision.ensureUserByPhone(phone(1));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "审批入驻店"), "OPS_APV");
        var vo = opsController.auditApply(applyNo, new OpsPlatformController.AuditReq(true, "资料齐全", "COMMUNITY", List.of("CMT-OB-1"), null));
        assertThat(vo.status()).isEqualTo("REVIEWING");
        assertThat(jdbc.queryForObject("select count(*) from mch_entity where owner_user_no = ?", Integer.class, owner)).isZero();
        assertThat(SUBMITS).hasSize(1);
        long revision = (Long) SUBMITS.get(0)[2];
        assertThat(revision).isEqualTo(jdbc.queryForObject("select version from mch_entity_apply where apply_no = ?", Long.class, applyNo));
        assertThat(SUBMITS.get(0)[3]).isEqualTo("OPS_APV");
        @SuppressWarnings("unchecked") Map<String, Object> proposal = (Map<String, Object>) SUBMITS.get(0)[4];
        assertThat(proposal).containsEntry("serviceScope", "COMMUNITY").containsEntry("communityNos", List.of("CMT-OB-1")).containsEntry("reason", "资料齐全");
        assertThat(subject.snapshot(applyNo, proposal).facts()).anySatisfy(f -> assertThat(f.label()).isEqualTo("服务范围"));

        SecurityContextHolder.clearContext(); // 回调线程没有登录人
        subject.apply(applyNo, revision, proposal);
        assertThat(jdbc.queryForObject("select status from mch_entity_apply where apply_no = ?", String.class, applyNo)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from mch_entity where owner_user_no = ?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select audited_by from mch_entity_apply where apply_no = ?", String.class, applyNo)).isEqualTo("OPSKIT");

        subject.apply(applyNo, revision, proposal); // OpsKit 重投
        assertThat(jdbc.queryForObject("select count(*) from mch_entity where owner_user_no = ?", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 申请单在审批期间变了 → STALE，不建主体；同一版本重复送审 → CONFLICT")
    void staleRevisionAndDuplicateSubmit() {
        String owner = userProvision.ensureUserByPhone(phone(2));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "过期送审店"), "OPS_APV");
        opsController.auditApply(applyNo, new OpsPlatformController.AuditReq(true, null, "COMMUNITY", List.of("CMT-OB-1"), null));
        long revision = (Long) SUBMITS.get(0)[2];
        assertThatThrownBy(() -> opsController.auditApply(applyNo, new OpsPlatformController.AuditReq(true, null, "COMMUNITY", List.of("CMT-OB-1"), null)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> subject.apply(applyNo, revision - 1, Map.of("serviceScope", "COMMUNITY")))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        assertThat(jdbc.queryForObject("select count(*) from mch_entity where owner_user_no = ?", Integer.class, owner)).isZero();
    }

    @Test
    @DisplayName("驳回不走审批：当场 REJECTED，不送审")
    void rejectStaysImmediate() {
        String owner = userProvision.ensureUserByPhone(phone(3));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "驳回店"), "OPS_APV");
        var vo = opsController.auditApply(applyNo, new OpsPlatformController.AuditReq(false, "证照不清晰", null, null, null));
        assertThat(vo.status()).isEqualTo("REJECTED");
        assertThat(SUBMITS).isEmpty();
    }

    private static String phone(int n) {
        return "1595009" + String.format("%04d", n);
    }

    private OpsService.SubmitApplyCommand full(String userNo, String name) {
        return new OpsService.SubmitApplyCommand(
                userNo, name, "ENTERPRISE",
                "老板", "13900001111", null, "水果", "社区水果店",
                null, List.of("CMT-OB-1"), List.of("https://example.com/l.jpg"),
                false, "RETAIL", null,
                List.of(new OpsService.QualificationItem(
                        "BUSINESS_LICENSE", "91440300MA5XXXXXXX",
                        "https://example.com/l.jpg", null, null)));
    }
}
