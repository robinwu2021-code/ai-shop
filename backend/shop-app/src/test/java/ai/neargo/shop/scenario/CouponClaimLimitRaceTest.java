package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.service.CouponService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每人限领 1 张的券，同一个人并发点 8 次领券：只落 1 张（新模型领券、老模型 grantTo 两边）。
 *
 * <p>限领判的是「先数他手上有几张、再插一张」。两次请求都数到 0 就都插了 —— 限领 1 张的券到手 2 张。
 * 修复把「数张数」改成在券行锁之后的<b>当前读</b>：可重复读下，普通读拿的是事务第一次读时的快照，
 * 锁等到了、数的却还是锁之前的旧数。
 *
 * <p>注：H2 测试库是读已提交，源围栏（requireWritable）在数张数之前已经锁了券行，这条在 H2 上修复前也是绿的 ——
 * 漏洞只在可重复读（生产 MariaDB 的默认隔离级别）下出现。它在这里守的是「并发领券仍只落一张」这条结果不回退。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponClaimLimitRaceTest {

    private static int seq = 7900;

    @Autowired private CouponService couponService;
    @Autowired private ai.neargo.shop.marketing.coupon.CouponService legacy;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 新模型：同一人并发领 8 次「限领 1 张」的券，只到手 1 张")
    void concurrentReceiveRespectsPerUserLimit() throws Exception {
        String e = "M-CLR-" + (++seq);
        String couponNo = couponService.save(e, new CouponSaveCmd(null, "限领一张" + seq, PmtCoupon.CASH, 100L,
                null, null, 0L, null, PmtCoupon.SCOPE_ALL, List.of(), null,
                PmtCoupon.RELATIVE, null, null, 7,
                PmtCoupon.ISSUE_CENTER, PmtCoupon.REDEEM_ORDER, 1, 100, 1, null), "OP").couponNo();
        String user = "U-CLR-" + seq;
        hammer(() -> couponService.receive(user, couponNo));
        assertThat(jdbc.queryForObject("select count(*) from pmt_user_coupon where coupon_no=? and user_no=?",
                Integer.class, couponNo, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select received_count from pmt_coupon where coupon_no=?",
                Integer.class, couponNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 老模型：同一人并发 grantTo 8 次，只到手 1 张")
    void concurrentLegacyGrantRespectsPerUserLimit() throws Exception {
        String couponNo = "MC-CLR-" + (++seq);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,total_count,start_at,"
                + "end_at,status,per_user_limit,tenant_no,created_at,updated_at) VALUES(?,'限领一张','FULL_CUT',100,"
                + "'MERCHANT','M1',100,?,?,'ACTIVE',1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                couponNo, now - 1000, now + 3_600_000L);
        String user = "U-CLR-" + seq;
        hammer(() -> legacy.grantTo(user, couponNo));
        assertThat(jdbc.queryForObject("select count(*) from mkt_user_coupon where coupon_no=? and user_no=?",
                Integer.class, couponNo, user)).isEqualTo(1);
    }

    /** 8 个线程同时起跑；被拒的（限领 / 锁等待）是预期结果，不算失败 */
    private static void hammer(Runnable claim) throws Exception {
        int n = 8;
        CyclicBarrier start = new CyclicBarrier(n);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    claim.run();
                } catch (Exception ignored) {
                    // 被拒是预期的
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
    }
}
