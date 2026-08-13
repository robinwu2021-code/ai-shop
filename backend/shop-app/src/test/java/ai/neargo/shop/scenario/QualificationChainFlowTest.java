package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.OpsService.QualificationItem;
import ai.neargo.shop.platform.OpsService.SubmitApplyCommand;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入驻资质链路：商家传的执照，要真的进到主体档案上。
 *
 * <p><b>这条链此前是断的</b>：入驻收的 licenses 停在
 * {@code mch_entity_apply.qualifications}（纯 URL 数组），审核通过时没有任何一处转存；
 * 而上架的两个闸门（资质过期、类目授权）读的是 {@code mch_qualification} ——
 * 那张表**实测 0 行**，于是两个闸门都写好了、都从不触发。
 *
 * <p>断得很隐蔽：没有报错，没有告警，功能「看着都在」。
 * 所以这里断言的不是「能不能调通」，而是**数据真的落到了闸门读的那张表上**。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("入驻资质链路：传的执照要进到闸门读的那张表")
class QualificationChainFlowTest {

    @Autowired
    private OpsService opsService;

    @Autowired
    private QualificationMapper qualificationMapper;

    @Autowired
    private MerchantApplyMapper applyMapper;

    /**
     * auditApply 要登录态（`SecurityUtils.requireUser`）—— 审核是能改变别人生意的操作，
     * 必须答得出「谁批的」。测试里手动塞一个运营身份，而不是把那行校验去掉。
     */
    @BeforeEach
    void asOperator() {
        var user = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.OPERATOR, "OPS_TEST", "测试运营",
                List.of("SUPER_ADMIN"), List.of("*"), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★ 审核通过后，资质进入 mch_qualification —— 闸门读的就是它")
    void approvedApplyTransfersQualifications() {
        String applyNo = submit("ENTERPRISE", List.of(
                new QualificationItem("BUSINESS_LICENSE", "91330106MA2XXXXX01", "https://x/a.jpg", null, null)));

        String entityNo = approve(applyNo);

        var rows = qualsOf(entityNo);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getQualType()).isEqualTo("BUSINESS_LICENSE");
        assertThat(rows.get(0).getQualNumber()).isEqualTo("91330106MA2XXXXX01");
        // 名字要与 sys_auth_code.required_qualification 同一套字面量，
        // 否则类目授权比对不上 —— 而两边都不报错
        assertThat(rows.get(0).getQualName()).isEqualTo("营业执照");
    }

    @Test
    @DisplayName("★★ 需要执照的档位，提交时没传执照就拒 —— 此前一路放行到进件才拦")
    void licenseRequiredAtSubmit() {
        assertThatThrownBy(() -> submit("ENTERPRISE", List.of()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★ 免执照档位不要求执照 —— 对自然人要执照本来就是错的")
    void licenseNotRequiredForExemptForm() {
        String applyNo = submit("NATURAL_PERSON", List.of());
        assertThat(applyNo).isNotBlank();
    }

    @Test
    @DisplayName("★ 重复审核不会写重复资质 —— 「这家店有几张执照」不能是个假数字")
    void transferIsIdempotent() {
        String applyNo = submit("ENTERPRISE", List.of(
                new QualificationItem("BUSINESS_LICENSE", "91330106MA2XXXXX02", "https://x/b.jpg", null, null)));
        String entityNo = approve(applyNo);
        // 再审一次（真实场景是运营重复点击 / 接口重放）
        try {
            opsService.auditApply(applyNo, true, null, null, null);
        } catch (RuntimeException ignored) {
            // 已终态的申请再审会被状态机拒 —— 那也是对的，不影响本断言
        }

        assertThat(qualsOf(entityNo)).hasSize(1);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 解数据域再查。
     *
     * <p>不解的话 where 会被追加成匹配不到任何行，而 <b>selectList 返回空不报错</b> ——
     * 断言会红在「资质没转存」上，而真实原因是这条查询本身被过滤空了。
     * 这两件事的排查成本差很远。
     */
    private List<MchQualification> qualsOf(String entityNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                qualificationMapper.selectList(Wrappers.<MchQualification>lambdaQuery()
                        .eq(MchQualification::getEntityNo, entityNo)));
    }

    private String submit(String subject, List<QualificationItem> items) {
        String phone = "138" + (System.nanoTime() % 100_000_000L);
        return opsService.createApply(new SubmitApplyCommand(
                "U" + System.nanoTime() % 100_000_000L, "资质链路测试店", subject,
                "张三", phone, "FRESH_VEG", "测试",
                "COMMUNITY", List.of("C001"),
                List.of(), false, "RETAIL", items));
    }

    private String approve(String applyNo) {
        opsService.acceptApply(applyNo);
        opsService.auditApply(applyNo, true, null, "COMMUNITY", List.of("C001"));
        MchEntityApply a = applyMapper.selectOne(Wrappers.<MchEntityApply>lambdaQuery()
                .eq(MchEntityApply::getApplyNo, applyNo).last("LIMIT 1"));
        return a.getEntityNo();
    }
}
