package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers;
import ai.neargo.shop.marketing.port.CouponPortImpl;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers;
import ai.neargo.shop.promotion.service.CouponAllocService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★★ 一券两用（《分布式集群与高可用方案》§5 P0）：两笔订单同时用同一张券，恰好一笔成功。
 *
 * <p>此前用券是「读 → 校验 → updateById」：两笔都读到 UNUSED、都校验通过，后写的那笔被 @Version
 * 乐观锁拒成 0 行却<b>不报错</b> —— 两单都享受了优惠。新模型的次卡计数同理会丢次数。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("一券两用")
class CouponDoubleUseTest {

    @Autowired
    private CouponPortImpl legacy;
    @Autowired
    private CouponAllocService promo;
    @Autowired
    private CouponMappers.CouponMapper mktCouponMapper;
    @Autowired
    private CouponMappers.UserCouponMapper mktUserCouponMapper;
    @Autowired
    private PromotionMappers.CouponMapper pmtCouponMapper;
    @Autowired
    private PromotionMappers.UserCouponMapper pmtUserCouponMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 老模型券：两线程同时用 → 恰一笔成功，另一笔 COUPON_NOT_APPLICABLE")
    void legacyCouponUsedOnce() throws Exception {
        String uc = legacyCoupon("U-DBL-1");
        List<Object> results = race(() -> {
            legacy.markUsed("U-DBL-1", uc, "O-DBL-A", null);
            return "A";
        }, () -> {
            legacy.markUsed("U-DBL-1", uc, "O-DBL-B", null);
            return "B";
        });
        assertOneWinner(results);
        assertThat(jdbc.queryForObject("select status from mkt_user_coupon where user_coupon_no = ?", String.class, uc))
                .isEqualTo("USED");
    }

    @Test
    @DisplayName("★★★ 新模型券（单次）：两线程同时用 → 恰一笔成功；次数只加 1")
    void promoCouponUsedOnce() throws Exception {
        String uc = promoCoupon("U-DBL-2", 1);
        List<Object> results = race(() -> {
            promo.markUsed("U-DBL-2", uc, "O-DBL-C", null);
            return "C";
        }, () -> {
            promo.markUsed("U-DBL-2", uc, "O-DBL-D", null);
            return "D";
        });
        assertOneWinner(results);
        assertThat(jdbc.queryForObject("select times_used from pmt_user_coupon where user_coupon_no = ?", Integer.class, uc))
                .isOne();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertOneWinner(List<Object> results) {
        long ok = results.stream().filter(r -> r instanceof String).count();
        assertThat(ok).as("两笔都成功 = 一券两用；结果 %s", results).isOne();
        assertThat(results).filteredOn(r -> r instanceof BizException)
                .singleElement().extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
    }

    /** 两个任务同时起跑；结果是返回值或抛出的异常 */
    private static List<Object> race(Callable<String> a, Callable<String> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<String> c : List.of(a, b)) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    return c.call();
                } catch (BizException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    private String legacyCoupon(String userNo) {
        long now = System.currentTimeMillis();
        MktCoupon c = new MktCoupon();
        c.setCouponNo("CP-DBL-" + now);
        c.setTitle("并发券");
        c.setType(MktCoupon.FULL_CUT);
        c.setFaceMinor(500L);
        c.setDiscountRate(0);
        c.setThresholdMinor(0L);
        c.setMaxDiscountMinor(0L);
        c.setFunder("PLATFORM");
        c.setTotalCount(10);
        c.setReceivedCount(1);
        c.setPerUserLimit(1);
        c.setStartAt(now - 86_400_000L);
        c.setEndAt(now + 86_400_000L);
        c.setStatus("ACTIVE");
        mktCouponMapper.insert(c);
        MktUserCoupon uc = new MktUserCoupon();
        uc.setUserCouponNo("UC-DBL-" + now);
        uc.setCouponNo(c.getCouponNo());
        uc.setUserNo(userNo);
        uc.setStatus(MktUserCoupon.UNUSED);
        uc.setReceivedAt(now);
        mktUserCouponMapper.insert(uc);
        return uc.getUserCouponNo();
    }

    private String promoCoupon(String userNo, int timesTotal) {
        long now = System.currentTimeMillis();
        PmtCoupon c = new PmtCoupon();
        c.setCouponNo("PC-DBL-" + now);
        c.setEntityNo("M0001");
        c.setFunder(PmtCoupon.BY_MERCHANT);
        c.setTitle("并发券");
        c.setBenefitMode(PmtCoupon.CASH);
        c.setBenefitValue(500L);
        c.setMinAmountMinor(0L);
        c.setScopeType(PmtCoupon.SCOPE_ALL);
        c.setValidityMode(PmtCoupon.ABSOLUTE);
        c.setIssueMode(PmtCoupon.ISSUE_TARGETED);
        c.setRedeemMode(PmtCoupon.REDEEM_ORDER);
        c.setTimesTotal(timesTotal);
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        c.setStatus(PmtCoupon.ACTIVE);
        pmtCouponMapper.insert(c);
        PmtUserCoupon uc = new PmtUserCoupon();
        uc.setUserCouponNo("PU-DBL-" + now);
        uc.setCouponNo(c.getCouponNo());
        uc.setUserNo(userNo);
        uc.setEntityNo("M0001");
        uc.setStatus(PmtUserCoupon.UNUSED);
        uc.setTimesUsed(0);
        uc.setReceivedAt(now);
        uc.setExpireAt(now + 7 * 86_400_000L);
        pmtUserCouponMapper.insert(uc);
        return uc.getUserCouponNo();
    }
}
