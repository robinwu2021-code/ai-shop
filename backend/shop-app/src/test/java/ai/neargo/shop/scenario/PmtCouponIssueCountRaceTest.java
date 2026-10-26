package ai.neargo.shop.scenario;

import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.support.InterleavedRace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * 券的 {@code received_count} 不丢更新、不超发。
 *
 * <p>领券走的是带条件的 SQL 累加，而定向发放、改券、改状态三处是「读整行 → 改 → updateById 整行写回」：
 * <ul>
 *   <li>两批定向发放同时跑，后一批按<b>加锁之前</b>读到的余量发 —— 余量 5 张发出去 8 张；</li>
 *   <li>改券 / 暂停的那一刻有人领券 —— 写回的旧计数把这一张抹掉，余量凭空多出一张。</li>
 * </ul>
 * 用例把竞态<b>确定地</b>摆出来（{@link InterleavedRace}）：被测方法读完券行的那一刻，另一个线程把
 * 同一件事做完，被测方法再接着做。不靠一堆线程赌时序 —— 赌的话修复前那条也是绿的。
 */
@SpringBootTest
@ActiveProfiles("test")
class PmtCouponIssueCountRaceTest {

    private static int seq = 7400;

    @Autowired private CouponService couponService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CoreMarketingDocumentNumbers numbers;
    @MockitoSpyBean private MemberQueryPort members;

    @AfterEach
    void unhook() {
        reset(numbers, members);
    }

    @Test
    @DisplayName("★★★ 两批定向发放同时跑、余量 5 张、各 4 人：合计发出不超过 5 张，计数与实发一致")
    void concurrentTargetedBatchesNeverOversell() throws Exception {
        String e = "M-CNT-" + (++seq);
        String couponNo = couponService.save(e, targeted(5), "OP").couponNo();
        doReturn(audience("A", 4)).when(members).resolveSegment(e, "SEG-A");
        doReturn(audience("B", 4)).when(members).resolveSegment(e, "SEG-B");

        InterleavedRace.Racer[] other = new InterleavedRace.Racer[1];
        // 第一批读完券行（余量 5）、还没加锁的那一刻，第二批整批跑一遍
        hookAfterTemplateRead("issueNumbered",
                () -> other[0] = InterleavedRace.runAlongside(() -> couponService.issue(e, couponNo, "SEG-B", "OP-B")));

        Throwable first = null;
        try {
            couponService.issue(e, couponNo, "SEG-A", "OP-A");
        } catch (RuntimeException ex) {
            first = ex;   // 余量不够整批拒（不部分发放）也是对的结果
        }
        Throwable second = other[0].join();
        assertThat(first == null || second == null).as("两批至少成一批").isTrue();

        int rows = jdbc.queryForObject("select count(*) from pmt_user_coupon where coupon_no=?",
                Integer.class, couponNo);
        assertThat(rows).as("★ 余量 5 张发出去 %d 张", rows).isLessThanOrEqualTo(5);
        assertThat(receivedOf(couponNo)).as("计数与实发对不上 —— 丢了更新").isEqualTo(rows);
    }

    @Test
    @DisplayName("★★★ 改券、暂停的同一刻有人领券：已领计数不被写回的旧值抹掉")
    void saveAndSetStatusDoNotOverwriteReceivedCount() throws Exception {
        String e = "M-CNT-" + (++seq);
        CouponSaveCmd center = center(10);
        String couponNo = couponService.save(e, center, "OP").couponNo();

        InterleavedRace.Racer[] other = new InterleavedRace.Racer[1];
        hookAfterTemplateRead("saveNumbered",
                () -> other[0] = InterleavedRace.runAlongside(() -> couponService.receive("U-CNT-" + (++seq), couponNo)));
        couponService.save(e, withNo(center, couponNo), "OP");
        assertThat(other[0].join()).isNull();
        assertThat(receivedOf(couponNo)).as("★ 改券把刚领走的那一张抹掉了").isEqualTo(1);

        reset(numbers);
        hookAfterTemplateRead("setStatus",
                () -> other[0] = InterleavedRace.runAlongside(() -> couponService.receive("U-CNT-" + (++seq), couponNo)));
        couponService.setStatus(e, couponNo, PmtCoupon.PAUSED);
        assertThat(other[0].join()).isNull();
        assertThat(receivedOf(couponNo)).as("★ 暂停把刚领走的那一张抹掉了").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from pmt_user_coupon where coupon_no=?",
                Integer.class, couponNo)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 夹具

    /** 被测方法（只认这一帧）读完券模板那一行之后，跑一次 {@code then}。只触发一次 */
    private void hookAfterTemplateRead(String frame, Runnable then) {
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(inv -> {
            Object row = inv.callRealMethod();
            if (InterleavedRace.inFrame("PmtCouponServiceImpl", frame) && fired.compareAndSet(false, true)) {
                then.run();
            }
            return row;
        }).when(numbers).loadOne(eq(DocumentType.MARKETING_COUPON_TEMPLATE), any(), any(), any(), any(), any());
    }

    private static MemberQueryPort.SegmentAudience audience(String tag, int n) {
        var people = IntStream.rangeClosed(1, n)
                .mapToObj(i -> new MemberQueryPort.Audience("MB-" + tag + i + "-" + seq, "U-" + tag + i + "-" + seq))
                .toList();
        return new MemberQueryPort.SegmentAudience(n, people);
    }

    private int receivedOf(String couponNo) {
        return jdbc.queryForObject("select received_count from pmt_coupon where coupon_no=?",
                Integer.class, couponNo);
    }

    private static CouponSaveCmd targeted(int total) {
        return new CouponSaveCmd(null, "定向券" + (++seq), PmtCoupon.CASH, 100L, null, null,
                0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_TARGETED, PmtCoupon.REDEEM_ORDER, 1, total, 1, null);
    }

    private static CouponSaveCmd center(int total) {
        return new CouponSaveCmd(null, "领券中心券" + (++seq), PmtCoupon.CASH, 100L, null, null,
                0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_CENTER, PmtCoupon.REDEEM_ORDER, 1, total, 1, null);
    }

    private static CouponSaveCmd withNo(CouponSaveCmd c, String couponNo) {
        return new CouponSaveCmd(couponNo, c.title() + "·改", c.benefitMode(), c.benefitValue(),
                c.benefitCapMinor(), c.benefitRef(), c.minAmountMinor(), c.minQty(), c.scopeType(),
                c.scopeRefs(), c.scopeDesc(), c.validityMode(), c.startAt(), c.endAt(), c.validDays(),
                c.issueMode(), c.redeemMode(), c.timesTotal(), c.totalCount(), c.perUserLimit(), c.budgetMinor());
    }
}
