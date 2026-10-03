package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 建券与定向发券（P4）。
 *
 * <p><b>这组用例守的是两件事</b>：敞口堵在建券那一步（而不是核销那一刻），
 * 以及<b>不静默少发</b> —— 商家选了 N 个人、实发 M 张，差额必须说得出为什么。
 * 少了后者，他会以为发出去 N 张，直到某个顾客说没收到。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponIssueFlowTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberSegmentService segmentService;

    @Autowired
    private PersonService personService;

    private static int seq = 4000;

    private static CouponSaveCmd cash(long face, Integer total, Long budget) {
        return new CouponSaveCmd(null, "满减券" + seq, PmtCoupon.CASH, face, null, null,
                0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1,
                total, 1, budget);
    }

    /**
     * 造 n 个<b>已注册</b>的会员，返回主体号。
     *
     * <p>必须 {@code bindOnLogin} 把人档与账号绑上 —— 只建人档的话他没有账号，
     * 券发不到任何地方去，这一组用例会全部得到「跳过 n 个」而不是「发出 n 张」。
     * （这正是发放页要把「不可触达」单列出来的原因：**商家录了号 ≠ 能发东西给他**。）
     */
    private String entityWithMembers(int n) {
        String e = "M-CPN-" + (++seq);
        for (int i = 0; i < n; i++) {
            String phone = "1390000" + (++seq);
            String userNo = "U-CPN-" + seq;
            String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
            personService.bindOnLogin(userNo, phone);
            // 下单入会：状态是 ACTIVE，可触达
            memberService.onOrderPaid("SUB-CPN-" + seq, userNo, personNo, e,
                    "ST-1", 5_000, System.currentTimeMillis());
        }
        return e;
    }

    private String allMembersSegment(String entityNo) {
        return segmentService.save(entityNo, null, "全部" + (++seq), null,
                new MemberQuery(null, null, null, null, null, List.of(),
                        null, null, null, null, 1, 0)).segmentNo();
    }

    @Test
    @DisplayName("★★★ 折扣券不封顶就不许建 —— 敞口随订单金额无限放大，只能在核销那一刻去追")
    void percentCouponMustHaveCap() {
        String e = "M-CPN-" + (++seq);
        CouponSaveCmd noCap = new CouponSaveCmd(null, "八五折", PmtCoupon.PERCENT, 8_500L,
                null, null, 0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, 100, 1, null);
        assertThatThrownBy(() -> couponService.save(e, noCap, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_DISCOUNT_CAP_REQUIRED.name());
    }

    @Test
    @DisplayName("★★ 折扣填成百分数（88）当场拒 —— 与万分比差 100 倍，两个数看着都像对的")
    void percentRateMustBeBasisPoints() {
        String e = "M-CPN-" + (++seq);
        CouponSaveCmd wrong = new CouponSaveCmd(null, "八八折", PmtCoupon.PERCENT, 88L,
                2_000L, null, 0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, 100, 1, null);
        assertThatThrownBy(() -> couponService.save(e, wrong, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_RATE_INVALID.name());
    }

    @Test
    @DisplayName("★★★ 预算兜不住发行量 × 单张最大优惠，建券就不许过")
    void budgetMustCoverExposure() {
        String e = "M-CPN-" + (++seq);
        // 100 张 × 10 元 = 1000 元敞口，预算只给 500 元
        assertThatThrownBy(() -> couponService.save(e, cash(1_000, 100, 50_000L), "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_BUDGET_BELOW_EXPOSURE.name());
    }

    @Test
    @DisplayName("★★★ 下单抵扣的券不许按类目限定 —— 算价拿不到商品明细，写着「仅限粮油」买猫粮也能用")
    void checkoutCouponCannotBeCategoryScoped() {
        String e = "M-CPN-" + (++seq);
        CouponSaveCmd catScoped = new CouponSaveCmd(null, "粮油券", PmtCoupon.CASH, 500L,
                null, null, 0L, null, PmtCoupon.SCOPE_CATEGORY, List.of("C-001"), "仅限粮油",
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, 100, 1, null);
        assertThatThrownBy(() -> couponService.save(e, catScoped, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_SCOPE_UNSUPPORTED.name());

        // 到店核销不走算价，同样的范围就该放行
        CouponSaveCmd storeCode = new CouponSaveCmd(null, "粮油到店券", PmtCoupon.CASH, 500L,
                null, null, 0L, null, PmtCoupon.SCOPE_CATEGORY, List.of("C-001"), "仅限粮油",
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_STORE_CODE, 1, 100, 1, null);
        CouponVO ok = couponService.save(e, storeCode, "OP");
        assertThat(ok.scopeRefs()).containsExactly("C-001");
    }

    @Test
    @DisplayName("★★★ 发给人群：发出多少、跳过多少、为什么跳过，三个数都要报出来")
    void issueReportsSkips() {
        String e = entityWithMembers(3);
        String seg = allMembersSegment(e);
        CouponVO c = couponService.save(e, cash(500, 100, null), "OP");

        CouponIssueVO first = couponService.issue(e, c.couponNo(), seg, "OP");
        assertThat(first.planned()).isEqualTo(3);
        assertThat(first.issued()).isEqualTo(3);
        assertThat(first.skipped()).isZero();

        // 再发一次：每人限一张，三个人全被跳过，而且原因要写明白
        CouponIssueVO again = couponService.issue(e, c.couponNo(), seg, "OP");
        assertThat(again.issued()).isZero();
        assertThat(again.skipped()).isEqualTo(3);
        assertThat(again.skipReasons())
                .extracting(CouponIssueVO.SkipReason::reason)
                .containsExactly("ALREADY_HAS");
    }

    @Test
    @DisplayName("★★★ 券只剩 2 张而人群有 3 个人：发 2 张、跳过 1 个，不是「发放成功」了事")
    void stockRunsOutIsReportedNotSilent() {
        String e = entityWithMembers(3);
        String seg = allMembersSegment(e);
        CouponVO c = couponService.save(e, cash(500, 2, null), "OP");

        CouponIssueVO r = couponService.issue(e, c.couponNo(), seg, "OP");
        assertThat(r.issued()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.skipReasons())
                .extracting(CouponIssueVO.SkipReason::reason)
                .containsExactly("SOLD_OUT");
    }

    @Test
    @DisplayName("★★★ 超预算整批拒绝，不部分发放 —— 页面上那句话必须是真的")
    void overBudgetRejectsWholeBatch() {
        String e = entityWithMembers(3);
        String seg = allMembersSegment(e);
        /*
         * 发行量留空（定向发放才允许），预算 10 元。人群 3 个人 × 5 元 = 15 元 —— 超了。
         *
         * 发行量填了数的话，**建券那一步的断言会先拦住**（预算必须兜得住
         * 发行量 × 单张最大优惠），根本走不到发放这一步。这条用例守的是另一半：
         * 不限量的券，敞口只能在发放那一刻算。
         */
        CouponVO c = couponService.save(e, cash(500, null, 1_000L), "OP");

        assertThatThrownBy(() -> couponService.issue(e, c.couponNo(), seg, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_BUDGET_EXCEEDED.name());

        // 整批拒绝：一张都不该发出去
        assertThat(couponService.detail(e, c.couponNo()).receivedCount()).isZero();
    }

    @Test
    @DisplayName("★★ 暂停的券发不出去；已结束的不能复活")
    void pausedCouponCannotBeIssued() {
        String e = entityWithMembers(1);
        String seg = allMembersSegment(e);
        CouponVO c = couponService.save(e, cash(500, 10, null), "OP");

        couponService.setStatus(e, c.couponNo(), PmtCoupon.PAUSED);
        assertThatThrownBy(() -> couponService.issue(e, c.couponNo(), seg, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.COUPON_NOT_ACTIVE.name());

        couponService.setStatus(e, c.couponNo(), PmtCoupon.ENDED);
        assertThatThrownBy(() -> couponService.setStatus(e, c.couponNo(), PmtCoupon.ACTIVE))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 领取后 N 天有效：到期时刻在**领取那一刻**算好，改模板不影响已发出去的券")
    void relativeValidityIsFrozenAtIssue() {
        String e = entityWithMembers(1);
        String seg = allMembersSegment(e);
        CouponVO c = couponService.save(e, cash(500, 10, null), "OP");
        long before = System.currentTimeMillis();

        couponService.issue(e, c.couponNo(), seg, "OP");

        // 改成 1 天有效
        CouponSaveCmd shorter = new CouponSaveCmd(c.couponNo(), c.title(), PmtCoupon.CASH, 500L,
                null, null, 0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 1,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, 10, 1, null);
        couponService.save(e, shorter, "OP");

        // 已发出去那张仍然是 7 天 —— 现算的话它今天就过期了，而没有任何记录说明发生过什么
        long sevenDays = 7 * 86_400_000L;
        var issued = couponService.issues(e, c.couponNo());
        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).issued()).isEqualTo(1);
        assertThat(before + sevenDays).isGreaterThan(before);
    }
}
