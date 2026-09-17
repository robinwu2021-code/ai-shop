package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.user.UserProvisionPort;
import ai.neargo.shop.support.TestPlan;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三期 · <b>运营代商家建主体与门店</b>。
 *
 * <p>本组用例存在的全部理由，是<b>守住二期那条豁免不外溢</b>。
 * 自营能跳过进件，靠的是「进件资料的意义是核验那个第三方是谁，而自营不存在第三方」。
 * 代填的时候<b>第三方是存在的</b> —— 论证不成立，豁免也就不成立。
 * 而这两条路在界面上长得几乎一模一样，最容易被后来的人合成一条。
 *
 * <p>所以下面每一条都是「代填<b>不</b>放宽某道闸」，而不是「代填能用」。
 * 能用是顺带的；不放宽才是本期要钉的东西。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("代商家建：只改「谁来填这张表」，一道闸都不放宽")
class OnBehalfMerchantFlowTest {

    @Autowired
    private OpsService opsService;

    @Autowired
    private SelfOperatedService selfOperated;

    @Autowired
    private UserProvisionPort userProvision;

    @Autowired
    private MchEntityMapper entityMapper;

    @Autowired
    private EntityPlanMapper planMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 本类专用前缀 <b>1595002</b>。
     *
     * <p>不要改成 139/138 段：{@code PersonBindFlowTest} 用 {@code 1390000}、
     * {@code SelfOperatedMerchantFlowTest} 用 {@code 1595001}，
     * 而本类经 {@code ensureUserByPhone} 会给这些号<b>建出真账号</b> ——
     * 撞号时对面「这个号还没有账号」的前置断言当场变假。
     *
     * <p>症状是<b>单独跑两个类都绿、只有全量跑才红</b>，且报错指向别人的用例。
     * 两个号都是生成函数拼出来的，grep 那串完整号码找不到任何东西 —— 要 grep 前缀。
     */
    /**
     * 审核要登录态（{@code SecurityUtils.requireUser}）—— 审核是能改变别人生意的操作，
     * 必须答得出「谁批的」。测试里塞一个运营身份，而不是把那行校验去掉。
     *
     * <p>{@code OPS_TEST} 这个号同时被 {@code submitterCannotAuditOwnApply} 当作
     * 「代填人」用 —— 那条要验的正是<b>同一个人</b>既填又审。
     */
    @org.junit.jupiter.api.BeforeEach
    void asOperator() {
        var user = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.OPERATOR, ai.neargo.auth.store.SubjectKind.OPS,
                "OPS_TEST", "测试运营", List.of("SUPER_ADMIN"), List.of("*"), null, null);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static String phone(int n) {
        return "1595002" + String.format("%04d", n);
    }

    /** 一份「资料齐全」的代填单：执照在里面，主体类型是要执照的那一档。 */
    private OpsService.SubmitApplyCommand full(String userNo, String name) {
        return new OpsService.SubmitApplyCommand(
                userNo, name, "ENTERPRISE",
                "老板", "13900001111", "水果", "社区水果店",
                null, List.of("CMT-OB-1"), List.of("https://example.com/l.jpg"),
                false, "RETAIL",
                List.of(new OpsService.QualificationItem(
                        "BUSINESS_LICENSE", "91440300MA5XXXXXXX",
                        "https://example.com/l.jpg", null, null)));
    }

    private Map<String, Object> applyRow(String applyNo) {
        return jdbc.queryForMap(
                "select status, submitted_by, agreed_at, user_no from mch_entity_apply"
                        + " where apply_no = ?", applyNo);
    }

    @Test
    @DisplayName("★★★ 代填的单子进审核队列，不是直接建出主体 —— 制单与审核必须分离")
    void onBehalfLandsInQueueNotActive() {
        String owner = userProvision.ensureUserByPhone(phone(1));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "代填水果店"), "OPS-BD-1");

        var row = applyRow(applyNo);
        assertThat(row.get("status")).isEqualTo("PENDING");
        // 挂在商户本人名下，不是运营名下 —— 填错这里就是把一家店挂给别人
        assertThat(row.get("user_no")).isEqualTo(owner);

        /*
         * ★ 这一条才是「没有填完即激活」的真判据。
         * 只断言 status=PENDING 是不够的：主体可能已经在别处被建出来了，
         * 那时队列里躺着一张单、库里却已经有一家能卖货的店。
         */
        assertThat(DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getOwnerUserNo, owner)))).isEmpty();
    }

    @Test
    @DisplayName("★★★ 代填人留痕，且协议那一栏留空 —— 运营不能替商户勾")
    void recordsSubmitterAndLeavesAgreementBlank() {
        String owner = userProvision.ensureUserByPhone(phone(2));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "留痕水果店"), "OPS-BD-2");

        var row = applyRow(applyNo);
        assertThat(row.get("submitted_by")).isEqualTo("OPS-BD-2");
        /*
         * agreed_at 必须为空。写上「反正是运营代他同意的」就是凭空造一条法律事实 ——
         * 而这条断言撤掉之后不会有任何地方报错，界面上也看不出区别。
         */
        assertThat(row.get("agreed_at")).isNull();
    }

    @Test
    @DisplayName("★★ 自填的单子 submitted_by 为空 —— 两条路在数据上要分得开")
    void selfFiledLeavesSubmitterNull() {
        String owner = userProvision.ensureUserByPhone(phone(3));
        String applyNo = opsService.createApply(full(owner, "自填水果店"));

        // 这是上一条的对照量：没有它，「代填记了名字」也可能只是「所有单子都记了名字」
        assertThat(applyRow(applyNo).get("submitted_by")).isNull();
    }

    @Test
    @DisplayName("★★★ 代填仍然要执照 —— 这条是防豁免外溢的那一条")
    void onBehalfStillNeedsLicense() {
        String owner = userProvision.ensureUserByPhone(phone(4));
        var noLicense = new OpsService.SubmitApplyCommand(
                owner, "没执照水果店", "ENTERPRISE",
                "老板", "13900001111", "水果", null,
                null, List.of("CMT-OB-1"), List.of(),
                false, "RETAIL",
                List.of());   // 非 null 的空表 = 「这个端懂结构化资质」，于是闸门生效

        assertThatThrownBy(() -> opsService.createApplyOnBehalf(noLicense, "OPS-BD-3"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 代填人为空当场拒 —— 一张分辨不出来源的单子比没有更糟")
    void submitterIsRequired() {
        String owner = userProvision.ensureUserByPhone(phone(5));
        assertThatThrownBy(() -> opsService.createApplyOnBehalf(full(owner, "无主水果店"), " "))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 第三方开店照吃订阅额度 —— 代开不该让他凭空多一家店")
    void thirdPartyStoreEatsQuota() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(6), "额度水果店", List.of("CMT-OB-1"), null, "主营水果"), "OPS");

        /*
         * **只翻 self_operated 这一个开关**，别的一律不动 —— 消融要一次只变一样东西。
         * 建主体那条路上没有「建第三方」的入口（第三方走审核通过），
         * 所以这里用同一个主体改一个字段，两支比较的才是同一个东西。
         */
        jdbc.update("update mch_entity set self_operated = 0 where entity_no = ?", r.merchantNo());
        TestPlan.grantQuota(planMapper, r.merchantNo(), 2);   // 上限 2 家，已有 1 家

        // 第 2 家在额度内
        selfOperated.addStore(new SelfOperatedService.AddStoreCommand(
                r.merchantNo(), "额度水果店·二店", null, null), "OPS-BD-4");

        // 第 3 家超额。**报的必须是额度错**，不是 BAD_REQUEST ——
        // 后者会让 BD 站在店里反复改店名，而无论怎么改都一样被拒
        assertThatThrownBy(() -> selfOperated.addStore(new SelfOperatedService.AddStoreCommand(
                r.merchantNo(), "额度水果店·三店", null, null), "OPS-BD-4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("STORE_QUOTA_EXCEEDED");
    }

    @Test
    @DisplayName("★★★ 自营开店仍然不吃额度 —— 二期那条不能被三期改坏")
    void selfOperatedStoreStillSkipsQuota() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(7), "自营水果店", List.of("CMT-OB-1"), null, "主营水果"), "OPS");

        // 额度按到 1 家（已经有默认店了）。自营这一支应当<b>看都不看</b>它
        TestPlan.grantQuota(planMapper, r.merchantNo(), 1);

        var st = selfOperated.addStore(new SelfOperatedService.AddStoreCommand(
                r.merchantNo(), "自营水果店·二店", null, null), "OPS");
        assertThat(st.storeNo()).isNotBlank();
    }

    @Test
    @DisplayName("★★★ 代填的人不能审自己填的那一张 —— 光靠角色配置挡不住")
    void submitterCannotAuditOwnApply() {
        String owner = userProvision.ensureUserByPhone(phone(9));
        /*
         * 代填人写成**当前登录人**：测试里 SecurityUtils.currentUserNo() 取到谁，
         * 就让谁去填那张单 —— 这样"同一个人"才真的成立。
         * 写死一个常量的话，这条断言测的是"某个不存在的人不能审"，恒真且无意义。
         */
        String me = ai.neargo.shop.auth.SecurityUtils.currentUserNo();
        String applyNo = opsService.createApplyOnBehalf(full(owner, "自审水果店"), me);

        assertThatThrownBy(() -> opsService.auditApply(applyNo, true, null,
                "COMMUNITY", List.of("CMT-OB-1"), null))
                .isInstanceOf(BizException.class);

        /*
         * ★ **对照量**：换个人填的单子，同一个人审得动。
         * 没有这一条，上面那条也可能只是「auditApply 在本测试环境里根本跑不通」——
         * 而那会让这道闸看起来一直有效，其实从没被触发过。
         */
        String other = userProvision.ensureUserByPhone(phone(10));
        String byOthers = opsService.createApplyOnBehalf(full(other, "他人填的店"), "OPS-BD-9");
        opsService.auditApply(byOthers, true, null, "COMMUNITY", List.of("CMT-OB-1"), null);
    }

    @Test
    @DisplayName("★★★ 商户本人补勾之后 agreed_at 才有值，且 submitted_by 不被抹掉")
    void merchantAcceptsAgreementLater() {
        String owner = userProvision.ensureUserByPhone(phone(11));
        String applyNo = opsService.createApplyOnBehalf(full(owner, "补勾水果店"), "OPS-BD-6");
        assertThat(applyRow(applyNo).get("agreed_at")).as("代填时必须是空的").isNull();

        long at = opsService.acceptAgreement(owner);
        assertThat(at).isPositive();

        var row = applyRow(applyNo);
        assertThat(row.get("agreed_at")).isNotNull();
        /*
         * ★ 代填留痕**不随补勾消失**。它是历史不是当前状态 ——
         * 抹掉之后「这家店当初是谁录的」就再也答不出来，而那正是代填唯一的风险点。
         */
        assertThat(row.get("submitted_by")).isEqualTo("OPS-BD-6");

        // 幂等：再勾一次拿到同一个时刻，不刷新成现在
        assertThat(opsService.acceptAgreement(owner)).isEqualTo(at);
    }

    @Test
    @DisplayName("★★ 没申请过的人补勾返回 0，不报错 —— 没申请过是正常状态")
    void acceptWithoutApplyIsNotAnError() {
        String stranger = userProvision.ensureUserByPhone(phone(12));
        assertThat(opsService.acceptAgreement(stranger)).isZero();
    }

    @Test
    @DisplayName("★★★ 代建出来的主体 self_operated 恒为 0 —— 没有任何路径能把它置 1")
    void onBehalfNeverMarksSelfOperated() {
        String owner = userProvision.ensureUserByPhone(phone(8));
        opsService.createApplyOnBehalf(full(owner, "第三方水果店"), "OPS-BD-5");

        /*
         * 申请单上根本没有这一列，所以这条验的是「日后有人加了也会被发现」：
         * 代填路径走完之后，这个人名下不该出现任何 self_operated=1 的主体。
         */
        assertThat(DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getOwnerUserNo, owner)
                        .eq(MchEntity::getSelfOperated, 1)))).isEmpty();
    }
}
