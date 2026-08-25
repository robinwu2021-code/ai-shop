package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.service.CouponRedeemService;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.user.service.PersonService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 到店核销（P6）。
 *
 * <p><b>三条硬规则</b>：{@code STORE_CODE} 券在下单算价里取不到（一张券两条路
 * 一定会被用两次）、核销一次成功而并发的第二次被拒、次卡扣到 0 才转已用完。
 *
 * <p>还有一条同样重要但反直觉的：<b>3 秒内重复提交要返回上一次的结果，不能报错</b>。
 * 报错会让店员以为刚才那下没成功，于是再按一次 —— 真正的重复核销往往这么来。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreCodeRedeemTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRedeemService redeemService;

    @Autowired
    private CouponPort couponPort;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberSegmentService segmentService;

    @Autowired
    private PersonService personService;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private ApplyMapper applyMapper;

    private static int seq = 6100;

    /** 建一张到店核销券并发给一个真实注册过的会员，返回 {主体号, 核销码, 用户券号} */
    private String[] giveStoreCodeCoupon(int timesTotal) {
        String e = "M-RDM-" + (++seq);
        String phone = "1360000" + (++seq);
        String userNo = "U-RDM-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        memberService.onOrderPaid("SUB-RDM-" + seq, userNo, personNo, e, "ST-1", 5_000,
                System.currentTimeMillis());

        String seg = segmentService.save(e, null, "全部" + seq, null,
                new MemberQuery(null, null, null, null, null, List.of(),
                        null, null, null, null, 1, 0)).segmentNo();

        CouponVO c = couponService.save(e, new CouponSaveCmd(null, "到店领鸡蛋" + seq,
                PmtCoupon.CASH, 300L, null, null, 0L, null,
                PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 30,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_STORE_CODE, timesTotal,
                50, 1, null), "OP");
        couponService.issue(e, c.couponNo(), seg, "OP");

        PmtUserCoupon uc = userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getCouponNo, c.couponNo()).last("limit 1"));
        assertThat(uc.getRedeemCode()).as("到店核销的券必须有码 —— 没码买家出示不了任何东西")
                .isNotBlank();
        return new String[]{e, uc.getRedeemCode(), uc.getUserCouponNo(), uc.getUserNo()};
    }

    @Test
    @DisplayName("★★★ 到店核销的券在下单算价里取不到 —— 一张券两条路一定会被用两次")
    void storeCodeCouponIsInvisibleAtCheckout() {
        String[] g = giveStoreCodeCoupon(1);
        assertThatThrownBy(() -> couponPort.allocate(g[3], g[2],
                List.of(new CouponPort.MerchantAmount(g[0], 9_000))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_APPLICABLE.name());
    }

    @Test
    @DisplayName("★★★ 核销一次成功，记下门店与店员；那一行不可撤销")
    void redeemOnceLeavesAnIrreversibleTrail() {
        String[] g = giveStoreCodeCoupon(1);

        CouponRedeemService.RedeemView view = redeemService.peek(g[0], g[1]);
        assertThat(view.redeemable()).as("先看后核：扫完直接扣的话，扫错一张没有回头路").isTrue();
        assertThat(view.remaining()).isEqualTo(1);

        CouponRedeemService.RedeemResult r = redeemService.redeem(g[0], g[1], "ST-9", "STAFF-1");
        assertThat(r.usedUp()).isTrue();
        assertThat(r.remaining()).isZero();
        assertThat(r.duplicated()).isFalse();

        PmtApply row = applyMapper.selectOne(Wrappers.<PmtApply>lambdaQuery()
                .eq(PmtApply::getPromoNo, g[2]).last("limit 1"));
        assertThat(row.getStoreNo()).as("对账要知道货从哪家店出").isEqualTo("ST-9");
        assertThat(row.getOperatorNo()).as("不可逆动作必须记名").isEqualTo("STAFF-1");
        assertThat(row.getRevertedAt()).as("线下核销不可撤销，这一列恒为空").isNull();
    }

    @Test
    @DisplayName("★★★ 3 秒内连点第二下：返回上一次的结果，不报错 —— 报错他会再按一次")
    void doubleTapWithinWindowReturnsSameResult() {
        String[] g = giveStoreCodeCoupon(1);
        redeemService.redeem(g[0], g[1], "ST-9", "STAFF-1");

        CouponRedeemService.RedeemResult again = redeemService.redeem(g[0], g[1], "ST-9", "STAFF-1");
        assertThat(again.duplicated()).isTrue();
        assertThat(again.timesUsed()).as("没有扣第二次").isEqualTo(1);

        // 只留一行核销记录：连点不该在对账里变成两次
        assertThat(applyMapper.selectList(Wrappers.<PmtApply>lambdaQuery()
                .eq(PmtApply::getPromoNo, g[2]))).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 次卡 3 次：扣到 0 才转已用完，中间两次都还能用")
    void punchCardCountsDownToZero() {
        String[] g = giveStoreCodeCoupon(3);

        for (int i = 1; i <= 3; i++) {
            // 每次之间要越过 3 秒幂等窗口 —— 这里直接把上一行的时间往前挪
            applyMapper.update(null, Wrappers.<PmtApply>lambdaUpdate()
                    .eq(PmtApply::getPromoNo, g[2])
                    .set(PmtApply::getAppliedAt, System.currentTimeMillis() - 10_000));
            CouponRedeemService.RedeemResult r = redeemService.redeem(g[0], g[1], "ST-9", "STAFF-1");
            assertThat(r.timesUsed()).isEqualTo(i);
            assertThat(r.usedUp()).as("第 %d 次", i).isEqualTo(i == 3);
        }

        PmtUserCoupon uc = userCouponMapper.selectOne(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getUserCouponNo, g[2]).last("limit 1"));
        assertThat(uc.getStatus()).isEqualTo(PmtUserCoupon.USED);

        // 用完之后再来一次：明确拒绝
        applyMapper.update(null, Wrappers.<PmtApply>lambdaUpdate()
                .eq(PmtApply::getPromoNo, g[2])
                .set(PmtApply::getAppliedAt, System.currentTimeMillis() - 10_000));
        assertThatThrownBy(() -> redeemService.redeem(g[0], g[1], "ST-9", "STAFF-1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 别家的码在这家店查不到 —— 码是短的，不限主体就成了枚举工具")
    void codeFromAnotherMerchantIsNotFound() {
        String[] g = giveStoreCodeCoupon(1);
        assertThatThrownBy(() -> redeemService.redeem("M-OTHER", g[1], "ST-9", "STAFF-1"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_CODE_NOT_FOUND.name());
    }
}
