package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundableLine;
import ai.neargo.shop.trade.order.policy.FulfillStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 售后·退款的规则对象（第 5 步对象模型 §2，已拍板的规则逐条钉死）。 */
class RefundPoliciesTest {

    private static final long DAY = 86_400_000L;

    @Test
    @DisplayName("★★ 售后期：未完成不限；完成后 7 天；质量类 15 天；边界含当刻")
    void window() {
        AfterSaleWindowPolicy p = new DefaultAfterSaleWindowPolicy(7, 15);
        assertThat(p.deadlineAt(null, "NOT_WANTED")).isNull();
        assertThat(p.isOpen(null, "NOT_WANTED", 999L)).isTrue();
        long done = 1_000_000L;
        assertThat(p.isOpen(done, "NOT_WANTED", done + 7 * DAY)).isTrue();
        assertThat(p.isOpen(done, "NOT_WANTED", done + 7 * DAY + 1)).isFalse();
        assertThat(p.isOpen(done, "QUALITY", done + 15 * DAY)).isTrue();
        assertThat(p.isOpen(done, "DAMAGED", done + 15 * DAY + 1)).isFalse();
    }

    @Test
    @DisplayName("★★★ 运费：未发货且退完剩余 → 退；已发货仅商家责任四类退；已退过不再退")
    void freight() {
        FreightRefundPolicy p = new DefaultFreightRefundPolicy();
        assertThat(p.freightFor(500, false, true, "NOT_WANTED")).isEqualTo(500);
        assertThat(p.freightFor(500, false, false, "NOT_WANTED")).as("未发货但只退部分").isZero();
        assertThat(p.freightFor(500, true, true, "NOT_WANTED")).as("已发货、买家原因").isZero();
        for (String r : List.of("DAMAGED", "QUALITY", "WRONG_ITEM", "MISSING")) {
            assertThat(p.freightFor(500, true, false, r)).as(r).isEqualTo(500);
        }
        assertThat(p.freightFor(0, true, true, "DAMAGED")).as("运费已退过").isZero();
    }

    @Test
    @DisplayName("★★ 行金额：按件比例向下取整；退到本行最后一件吃尾差")
    void lineAmount() {
        LineAmountPolicy p = new DefaultLineAmountPolicy();
        RefundableLine line = new RefundableLine(9L, "米", 3, 0, 10000L, 0L);
        assertThat(p.amountFor(line, 1)).isEqualTo(3333L);
        RefundableLine after2 = new RefundableLine(9L, "米", 3, 2, 10000L, 6666L);
        assertThat(p.amountFor(after2, 1)).as("最后一件 = 剩余").isEqualTo(3334L);
        assertThat(p.amountFor(line, 3)).isEqualTo(10000L);
    }

    @Test
    @DisplayName("★★ 核定额分回：先抵货款（按行比例）再抵运费；Σ 恰等于核定额；不超过申请额")
    void approvedAllocation() {
        ApprovedAmountAllocator a = new DefaultApprovedAmountAllocator();
        RefundPlan req = new RefundPlan("S1", List.of(new RefundLine(1, 1, 6000), new RefundLine(2, 1, 3000)), 500);
        RefundPlan got = a.allocate(req, 4500);
        assertThat(got.totalMinor()).isEqualTo(4500);
        assertThat(got.freightMinor()).as("货款没抵完前不动运费").isZero();
        assertThat(got.lines()).extracting(RefundLine::amountMinor).containsExactly(3000L, 1500L);
        assertThat(a.allocate(req, 9200).freightMinor()).isEqualTo(200);
        assertThat(a.allocate(req, 99999)).as("核定额超过申请额按申请额").isEqualTo(req);
    }

    @Test
    @DisplayName("渠道能力：线上可部分退；线下 / 0 元不能原路部分退")
    void channel() {
        ChannelRefundCapability c = new DefaultChannelRefundCapability();
        assertThat(c.supportsPartial("WECHAT")).isTrue();
        assertThat(c.supportsPartial("ALIPAY")).isTrue();
        assertThat(c.supportsPartial("OFFLINE")).isFalse();
        assertThat(c.supportsPartial("FREE")).isFalse();
    }

    @Test
    @DisplayName("抵扣积分按退款比例退回（四舍五入）")
    void points() {
        PointsRefundPolicy p = new DefaultPointsRefundPolicy();
        assertThat(p.pointsBackMinor(900, 9000, 3000)).isEqualTo(300);
        assertThat(p.pointsBackMinor(100, 3000, 1000)).isEqualTo(33);
        assertThat(p.pointsBackMinor(0, 3000, 1000)).isZero();
        assertThat(p.pointsBackMinor(100, 0, 1000)).as("实付为 0 不除零").isZero();
    }

    @Test
    @DisplayName("券：支付单下全部子单都已退款或已取消才退")
    void coupon() {
        CouponReturnPolicy p = new DefaultCouponReturnPolicy();
        assertThat(p.shouldReturn(List.of("REFUNDED", "CANCELLED"))).isTrue();
        assertThat(p.shouldReturn(List.of("REFUNDED", "FULFILLING"))).isFalse();
    }

    @Test
    @DisplayName("生鲜规则：已揽收 / 已签收的生鲜仅质量类")
    void fresh() {
        FreshQualityRule r = new FreshQualityRule();
        assertThat(r.check(FulfillStage.SHIPPED, true, "NOT_WANTED")).contains(ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY);
        assertThat(r.check(FulfillStage.SHIPPED, true, "QUALITY")).isEmpty();
        assertThat(r.check(FulfillStage.SHIPPED, false, "NOT_WANTED")).isEmpty();
    }
}
