package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchDepositTxn;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.spi.user.AdmissionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3 弱主体准入三件套：保证金 / 限品类 / 限额（落地清单 F-6，方案 §7.7）。
 *
 * <p>平台无仓、不碰货，「自营」只是资质代持的外壳。准入矩阵里最弱的一档
 * （{@code legal_form=MICRO}）<b>没有「入平台仓让平台验货」这条出路</b>——那个仓不存在。
 * 平台在法律上是销售主体、承担全部产品责任，却没有任何货物控制手段，
 * 这个缺口只能用准入和钱去补。
 *
 * <p>本测试要盯住的关键点是最后一条：<b>S1/S2 的行为必须一字不变</b>。
 * 一个默认就生效的准入闸门，比没有闸门更危险。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("S3 准入：保证金 / 限品类 / 限额，三样必须同时生效")
class S3AdmissionFlowTest {

    private static final String MICRO = "NATURAL_PERSON";
    private static final String ENTERPRISE = "ENTERPRISE";

    /** 与 V27 默认策略一致：应缴 2000 元、单笔 500 元、日累计 5000 元。 */
    private static final long REQUIRED_DEPOSIT = 200_000L;
    private static final long SINGLE_LIMIT = 50_000L;

    @Autowired
    private AdmissionPort admissionPort;

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private MchEntityMapper merchantMapper;

    @Test
    @DisplayName("★ 保证金不足 → 上架被拦（70008）；补足 → 放行")
    void depositGatesListing() {
        String micro = aMerchantOf(MICRO);

        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, "CAT_FREE", false))
                .as("小微一分没缴就能上架，等于三件套里的钱那一件从未生效")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DEPOSIT_INSUFFICIENT.name());

        admissionService.recordTxn(micro, MchDepositTxn.PAY, REQUIRED_DEPOSIT, "缴纳保证金", "OPS");

        assertThatCode(() -> admissionPort.requireListingAllowed(micro, "CAT_FREE", false))
                .as("补足后必须真的放行——只拦不放的闸门会被运营直接关掉")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 无门槛类目同样要过保证金这道闸")
    void freeCategoryStillNeedsDeposit() {
        String micro = aMerchantOf(MICRO);

        /*
         * 这条最容易漏：如果准入检查跟着「类目没挂资质要求就 return」一起走，
         * 无门槛类目会完全绕过闸门，而那恰好是弱主体最容易上的一批货。
         */
        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, "CAT_FREE", false))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DEPOSIT_INSUFFICIENT.name());
    }

    @Test
    @DisplayName("★ 需资质类目对小微直接禁售（70009），且比「保证金不足」先报")
    void qualifiedCategoryBanned() {
        String micro = aMerchantOf(MICRO);

        /*
         * 此时保证金也是不足的。报错顺序必须是「先禁售、后补钱」——
         * 被禁的类目补多少钱都没用，先报 70008 会让商家白缴一笔再撞上第二堵墙。
         */
        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, "CAT_FOOD", true))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.CATEGORY_BANNED.name());
    }

    @Test
    @DisplayName("★ 单笔超限下单被拦（70010）")
    void singleOrderLimit() {
        String micro = aMerchantOf(MICRO);

        assertThatThrownBy(() ->
                admissionPort.requireOrderAllowed(micro, SINGLE_LIMIT + 1, () -> 0L))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ORDER_LIMIT_EXCEEDED.name());

        assertThatCode(() -> admissionPort.requireOrderAllowed(micro, SINGLE_LIMIT, () -> 0L))
                .as("等于限额是允许的——限额是上界，不是开区间")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 日累计超限被拦（70011）：单笔都合规，累计不合规")
    void dailyLimit() {
        String micro = aMerchantOf(MICRO);

        /*
         * 只卡单笔的话，拆成十单就绕过去了，而平台的敞口是按天累计的。
         * 这里每一单都在单笔限额内，只有加总才越界。
         */
        assertThatThrownBy(() ->
                admissionPort.requireOrderAllowed(micro, SINGLE_LIMIT, () -> 480_000L))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DAILY_LIMIT_EXCEEDED.name());
    }

    @Test
    @DisplayName("★ 不限日累计的档位，根本不会去查当日累计")
    void supplierNotCalledWhenUnlimited() {
        String enterprise = aMerchantOf(ENTERPRISE);
        boolean[] called = {false};

        admissionPort.requireOrderAllowed(enterprise, 9_999_999L, () -> {
            called[0] = true;
            return 0L;
        });

        assertThat(called[0])
                .as("按天聚合是本次改动里唯一随订单量增长的查询，不该为不限额的商户白跑")
                .isFalse();
    }

    @Test
    @DisplayName("★★ S1/S2 行为一字不变：不缴保证金、不限额、不禁品类")
    void higherTiersUnaffected() {
        String enterprise = aMerchantOf(ENTERPRISE);

        assertThatCode(() -> admissionPort.requireListingAllowed(enterprise, "CAT_FOOD", true))
                .as("企业主体一分钱保证金没缴也能上需资质类目——这正是上线前的行为")
                .doesNotThrowAnyException();

        assertThatCode(() -> admissionPort.requireOrderAllowed(enterprise, 99_999_999L, () -> 99_999_999L))
                .as("一个默认就生效的准入闸门，比没有闸门更危险")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 保证金每一笔变动都有流水，且流水余额与账户余额对得上")
    void everyChangeLeavesATrail() {
        String micro = aMerchantOf(MICRO);

        admissionService.recordTxn(micro, MchDepositTxn.PAY, 300_000L, "缴纳", "OPS");
        admissionService.recordTxn(micro, MchDepositTxn.DEDUCT, -50_000L, "理赔扣划", "OPS");

        var txns = admissionService.txns(micro);
        assertThat(txns).hasSize(2);

        var deposit = admissionService.deposit(micro);
        assertThat(deposit.paidMinor()).isEqualTo(250_000L);
        assertThat(txns.get(0).balanceAfterMinor())
                .as("只有余额字段的账户是不可审计的：说不清这笔钱什么时候少的、谁扣的")
                .isEqualTo(deposit.paidMinor());
    }

    @Test
    @DisplayName("★★ 退还必须是负数 —— 「退还」把余额加上去，两侧都不会报错")
    void refundMustBeNegative() {
        String micro = aMerchantOf(MICRO);
        admissionService.recordTxn(micro, MchDepositTxn.PAY, REQUIRED_DEPOSIT, "缴纳", "OPS");

        /*
         * 这一条是真实发生过的：ops-web 只对 DEDUCT 取了负，于是运营选「退还 2000」
         * 发出去是 +2000，实缴从 2000 涨到 4000，而流水上写着「退还」。
         * 两侧都不报错，只有对账时才会发现 —— 所以守卫要在服务端。
         */
        assertThatThrownBy(() ->
                admissionService.recordTxn(micro, MchDepositTxn.REFUND, 100_000L, "方向反了", "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.BAD_REQUEST.name());

        admissionService.recordTxn(micro, MchDepositTxn.REFUND, -100_000L, "正常退还", "OPS");
        assertThat(admissionService.deposit(micro).paidMinor())
                .as("退还之后余额必须变少")
                .isEqualTo(REQUIRED_DEPOSIT - 100_000L);

        // 缴纳反过来也不行：方向由类型决定，两个方向都要堵
        assertThatThrownBy(() ->
                admissionService.recordTxn(micro, MchDepositTxn.PAY, -1L, "负的缴纳", "OPS"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★ 冻结动的是 frozen 不是 paid —— 可用余额减少，实缴不变")
    void freezeDoesNotEraseWhatWasPaid() {
        String micro = aMerchantOf(MICRO);
        admissionService.recordTxn(micro, MchDepositTxn.PAY, REQUIRED_DEPOSIT, "缴纳", "OPS");
        admissionService.recordTxn(micro, MchDepositTxn.FREEZE, 100_000L, "理赔冻结", "OPS");

        var d = admissionService.deposit(micro);
        assertThat(d.paidMinor()).as("理赔不成立时要能还原「本来缴了多少」").isEqualTo(REQUIRED_DEPOSIT);
        assertThat(d.availableMinor()).isEqualTo(REQUIRED_DEPOSIT - 100_000L);
        assertThat(d.sufficient()).isFalse();

        // 冻结中的钱不能同时用来撑准入，否则同一笔保证金被两处重复计数
        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, "CAT_FREE", false))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DEPOSIT_INSUFFICIENT.name());
    }

    @Test
    @DisplayName("★ 档位停用 = 该档位不做任何限制（配置失误不该变成全站不可上架）")
    void disabledPolicyMeansNoLimit() {
        String micro = aMerchantOf(MICRO);

        MchAdmissionPolicy off = new MchAdmissionPolicy();
        off.setEnabled(0);
        admissionService.updatePolicy(MICRO, off, "OPS");
        try {
            assertThatCode(() -> admissionPort.requireListingAllowed(micro, "CAT_FOOD", true))
                    .doesNotThrowAnyException();
        } finally {
            MchAdmissionPolicy on = new MchAdmissionPolicy();
            on.setEnabled(1);
            admissionService.updatePolicy(MICRO, on, "OPS");
        }
    }

    /** 造一个指定主体类型的商家。直接落库而不走入驻流程——本测试要验的是准入闸门，不是入驻。 */
    private String aMerchantOf(String legalForm) {
        String no = "MADM" + legalForm.charAt(0) + System.nanoTime() % 1_000_000;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("准入测试-" + legalForm);
        m.setLegalForm(legalForm);
        m.setStatus("ACTIVE");
        merchantMapper.insert(m);
        assertThat(merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, no).last("LIMIT 1"))).isNotNull();
        return no;
    }
}
