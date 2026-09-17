package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台自营商家入口：跳过进件与审核建出主体。
 *
 * <p><b>「自营」由两个字段共同成立</b>，这是本组用例最要紧的一点：
 * <pre>
 *   mch_entity.funds_mode   = AGGREGATED     钱先进平台账户
 *   mch_store.business_mode = SELF_OPERATED  平台是销售主体
 * </pre>
 * 两者的建表默认值<b>恰好</b>就是这两个（V81 / V23）。所以「不写也对」——
 * 而这正是要钉住它们的理由：默认值改一次，平台主体就悄悄变成第三方，
 * 表现是售后派给商家自己，而没有任何报错。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("平台自营商家：一个不需要进件资料的入口")
class SelfOperatedMerchantFlowTest {

    @Autowired
    private SelfOperatedService selfOperated;

    @Autowired
    private MchEntityMapper entityMapper;

    @Autowired
    private MchStoreMapper storeMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 三期改写 opsAddStoreForThirdPartyStillEatsQuota 时引入：要把额度按到 1 家 */
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    @Autowired
    private ai.neargo.shop.spi.user.QualificationPort qualificationPort;

    /**
     * 手机号各用例互不相同 —— 幂等判据是人，共用一个号会让用例之间互相喂结果。
     *
     * <p><b>前缀 1595001 是本类专用，不要改成常见的 139/138 段。</b>
     * 第一版用的是 {@code "1390000"+%04d}，而 {@code PersonBindFlowTest} 的
     * {@code phone()} 逐字相同（同前缀 + 自增序号）—— 本类经
     * {@code ensureUserByPhone} 给那几个号建了账号，那边随后拿到同一个号，
     * 它「这个号还没有账号」的前置断言当场就假了。
     *
     * <p>症状极具误导性：<b>单独跑两个类都绿</b>，只有全量跑才红，
     * 而报错指向的是别人的用例，与本类毫无字面关联
     * （实测就是这么被 check-head-compiles 抓到的）。
     * 新增造号的用例时，先 grep 一遍这个前缀有没有被别人用。
     */
    private static String phone(int n) {
        return "1595001" + String.format("%04d", n);
    }

    @Test
    @DisplayName("★★★ 建出来的主体归集 + 门店自营 —— 少任何一个，售后就派给商家自己")
    void bothAxesAreSelfOperated() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(1), "虹选鲜果", List.of("CMT-SO-1"), null, "主营水果"), "OPS");

        assertThat(r.created()).isTrue();
        // 回读，不看入参：判据要来自库
        assertThat(r.fundsMode()).isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
        assertThat(r.businessMode()).isEqualTo(MchStore.SELF_OPERATED);

        MchEntity m = entity(r.merchantNo());
        assertThat(m.getFundsMode()).isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
        // 税务口径（V87 三分）。企业才开得出票，也才允许走归集
        assertThat(m.getLegalForm()).isEqualTo("ENTERPRISE");

        MchStore s = storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getStoreNo, r.storeNo()).last("LIMIT 1"));
        assertThat(s.getBusinessMode()).isEqualTo(MchStore.SELF_OPERATED);
        assertThat(s.getEntityNo()).isEqualTo(r.merchantNo());
    }

    @Test
    @DisplayName("★★★ 分账主体与覆盖范围一并建好 —— 这两条正是「复用 Port」要保住的")
    void createsPaymentSubjectAndCoverage() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(2), "虹选鲜果·分账", List.of("CMT-SO-2", "CMT-SO-3"), null, null), "OPS");

        // 少了分账主体，第一笔订单就分不了账（ADR-002），而建店那一刻看不出来
        Integer pay = jdbc.queryForObject(
                "select count(*) from mch_payment_merchant where entity_no = ?", Integer.class, r.merchantNo());
        assertThat(pay).isEqualTo(1);

        // 少了覆盖范围，商家上着架却对谁都不可见（ADR-009），且不报错
        Integer cov = jdbc.queryForObject(
                "select count(*) from mch_entity_community where entity_no = ? and deleted = 0",
                Integer.class, r.merchantNo());
        assertThat(cov).isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 没有覆盖社区就拒 —— 不是「先建了再说」，那会产出一个隐形商家")
    void rejectsEmptyCommunities() {
        assertThatThrownBy(() -> selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(3), "看不见的店", List.of(), null, null), "OPS"))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(3), "看不见的店", null, null, null), "OPS"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ CITY 档不要求勾社区 —— 但「不要求」不等于「就可见了」")
    void cityScopeNeedsNoCommunitiesButMayStillReachNobody() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(6), "虹选鲜果·深圳", "CITY", List.of(), null, "主营水果"), "OPS");

        assertThat(r.serviceScope()).isEqualTo("CITY");
        MchEntity m = entity(r.merchantNo());
        assertThat(m.getServiceScope()).isEqualTo("CITY");

        /*
         * 这条断言要的不是某个具体数字，是**这个数字真的被算过**。
         *
         * 建完时可达可能就是 0 —— 库里一个小区都没有的时候，CITY 档同样是 0，
         * 而那正是本入口最需要当场说出来的事实（ADR-009 的必填规则拦不住它）。
         * 所以只断言它与可见性的唯一出口一致，不断言它大于零。
         */
        assertThat(r.reachableCommunities())
                .isEqualTo(merchantQueryPort.reachableCommunities(r.merchantNo()).size());
    }

    @Test
    @DisplayName("★★ 没开放的经营范围档要拒 —— 写进去不报错，只是这家店按范围查时被静默漏掉")
    void rejectsScopeOutsideWhitelist() {
        assertThatThrownBy(() -> selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(7), "范围不存在", "ABC", List.of("CMT-SO-7"), null, null), "OPS"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 手机号格式不对就拒 —— 位数够但号段不存在的那种最难查")
    void rejectsBadPhone() {
        assertThatThrownBy(() -> selfOperated.create(new SelfOperatedService.CreateCommand(
                "12345678901", "号段不存在", List.of("CMT-SO-9"), null, null), "OPS"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 同一个手机号连调两次 = 同一个主体 —— 否则买家会看到两家同名店")
    void idempotentByOwner() {
        long before = entityCount();
        var first = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(4), "虹选鲜果·幂等", List.of("CMT-SO-4"), null, null), "OPS");
        var second = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(4), "虹选鲜果·幂等", List.of("CMT-SO-4"), null, null), "OPS");

        assertThat(second.merchantNo()).isEqualTo(first.merchantNo());
        assertThat(second.ownerUserNo()).isEqualTo(first.ownerUserNo());
        assertThat(first.created()).isTrue();
        // created 要分得开：两次都说「建好了」，运营会以为自己建出了两家店
        assertThat(second.created()).isFalse();
        assertThat(entityCount() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 不产生申请单 —— 否则待审队列里会多一条永远 APPROVED 的假记录")
    void leavesNoApplicationRecord() {
        Integer before = jdbc.queryForObject("select count(*) from mch_entity_apply", Integer.class);
        selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(5), "虹选鲜果·无申请单", List.of("CMT-SO-5"), null, null), "OPS");
        Integer after = jdbc.queryForObject("select count(*) from mch_entity_apply", Integer.class);

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ 自营主体不问证件 —— 它的证件就是平台自己的证件")
    void selfOperatedNeedsNoQualification() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(8), "虹选鲜果·免证", "CITY", List.of(), null, null), "OPS");

        assertThat(r.selfOperated()).isTrue();
        assertThat(entity(r.merchantNo()).getSelfOperated()).isEqualTo(1);
        // 一张证都没登记
        Integer quals = jdbc.queryForObject(
                "select count(*) from mch_qualification where entity_no = ?",
                Integer.class, r.merchantNo());
        assertThat(quals).isZero();

        assertThat(qualificationPort.hasValidQualification(
                r.merchantNo(), ai.neargo.shop.spi.user.QualificationPort.BUSINESS_LICENSE))
                .as("自营主体无证件也应放行").isTrue();
    }

    @Test
    @DisplayName("★★★ 代销主体仍然要证件 —— 这条是防静默放宽的那一条")
    void aggregatedButNotSelfOperatedStillNeedsQualification() {
        /*
         * `funds_mode=AGGREGATED` 的定义原文是「归集…平台是销售主体（**代销**）」，
         * 它同时盖着平台自营与代销第三方的货。代销的货来自第三方，
         * 那个第三方仍然要被核验（ADR-017 §3.4：平台先担责、再向商家追偿）。
         *
         * 所以：把豁免判据从 self_operated 换成 funds_mode，这条用例必须变红。
         * 它红不了，就说明豁免已经悄悄盖到代销头上了 —— 而那种放宽没有任何报错。
         */
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(9), "代销主体", "CITY", List.of(), null, null), "OPS");
        // 把自营标记摘掉，只留归集 —— 这正是一个代销主体的样子
        jdbc.update("update mch_entity set self_operated = 0 where entity_no = ?", r.merchantNo());

        assertThat(entity(r.merchantNo()).getFundsMode()).isEqualTo("AGGREGATED");
        assertThat(qualificationPort.hasValidQualification(
                r.merchantNo(), ai.neargo.shop.spi.user.QualificationPort.BUSINESS_LICENSE))
                .as("归集但非自营（代销）仍然要证件").isFalse();
    }

    @Test
    @DisplayName("★★★ 运营给自营主体开店：不吃订阅额度、不需要进件、经营模式仍是自营")
    void opsCanAddSelfOperatedStore() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(10), "虹选鲜果·多店", "CITY", List.of(), null, null), "OPS");

        var st = selfOperated.addStore(new SelfOperatedService.AddStoreCommand(
                r.merchantNo(), "虹选鲜果·南山店", "深圳市南山区", List.of()), "OPS");

        assertThat(st.businessMode()).isEqualTo(MchStore.SELF_OPERATED);
        // 自营门店不进件，收款号为空是正常的
        assertThat(st.payMerchantNo()).isNull();
        assertThat(st.storeNo()).isNotEqualTo(r.storeNo());

        Integer n = jdbc.queryForObject(
                "select count(*) from mch_store where entity_no = ? and deleted = 0",
                Integer.class, r.merchantNo());
        assertThat(n).as("默认店 + 新开的那家").isEqualTo(2);
    }

    /**
     * <b>本用例在三期被改写过，这段说明是它的全部理由。</b>
     *
     * <p>二期这里断言的是「非自营主体一律拒」，理由写的是
     * 「否则运营能绕过商家吃掉他买的额度」。三期要让 BD 能替商家开第二家店，
     * 于是那个理由<b>交还给额度闸本身</b>：挡住超额的应该是额度，而不是「不许代开」。
     *
     * <p>照着新实现把断言改成「不抛了」是不行的 —— 那样这条用例就只是在给
     * 当前实现背书。**它要钉的东西没变**：运营不能凭空给第三方多开一家店。
     * 只是判据从「拒绝这个动作」变成「这个动作照吃他买的额度」。
     * 额度到顶仍然拒，而这正是下面断言的那一条（第三方那一支的完整用例在
     * {@code OnBehalfMerchantFlowTest#thirdPartyStoreEatsQuota}）。
     */
    @Test
    @DisplayName("★★★ 第三方主体由运营开店照吃额度 —— 额度到顶仍然拒（三期改写）")
    void opsAddStoreForThirdPartyStillEatsQuota() {
        var r = selfOperated.create(new SelfOperatedService.CreateCommand(
                phone(11), "第三方主体", "CITY", List.of(), null, null), "OPS");
        jdbc.update("update mch_entity set self_operated = 0 where entity_no = ?", r.merchantNo());
        // 额度按到 1 家，而默认店已经占掉了那一家
        ai.neargo.shop.support.TestPlan.grantQuota(planMapper, r.merchantNo(), 1);

        assertThatThrownBy(() -> selfOperated.addStore(new SelfOperatedService.AddStoreCommand(
                r.merchantNo(), "不该建出来的店", null, List.of()), "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("STORE_QUOTA_EXCEEDED");
    }

    private MchEntity entity(String no) {
        return entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, no).last("LIMIT 1"));
    }

    private long entityCount() {
        return entityMapper.selectCount(Wrappers.<MchEntity>lambdaQuery());
    }
}
