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

    /** 手机号各用例互不相同 —— 幂等判据是人，共用一个号会让用例之间互相喂结果。 */
    private static String phone(int n) {
        return "1390000" + String.format("%04d", n);
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

    private MchEntity entity(String no) {
        return entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, no).last("LIMIT 1"));
    }

    private long entityCount() {
        return entityMapper.selectCount(Wrappers.<MchEntity>lambdaQuery());
    }
}
