package ai.neargo.shop.product;

import ai.neargo.shop.product.port.StoreShelfPortImpl;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 社区池重建**不占着请求**（2026-10-07 报障：「切换商家配送，反应很慢」）。
 *
 * <p>生产实测：保存一次送货方式 24~27 秒，而同一个接口的读只要 0.3 秒 ——
 * 慢的是这一步（16 件货 × 23656 个开放小区）。商家按下开关后界面要等它返回才动。
 *
 * <p>断言的是**调用方等了多久**，不是「有没有调到」：后者在改成异步前后都绿。
 */
class StoreShelfPortResyncTest {

    @Test
    @DisplayName("★★★ 重建再慢，调用方也立刻返回（而不是等它跑完）")
    void returnsBeforeRebuildFinishes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        MerchantGoodsService svc = mock(MerchantGoodsService.class);
        when(svc.resyncCommunityPools(anyString())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);   // 模拟那 25 秒
            done.countDown();
            return 16;
        });

        long t0 = System.currentTimeMillis();
        new StoreShelfPortImpl(svc).resyncPools("M1");
        long waited = System.currentTimeMillis() - t0;

        assertThat(started.await(3, TimeUnit.SECONDS)).as("重建确实开始跑了").isTrue();
        assertThat(waited).as("调用方不等重建跑完 —— 改回同步这一条会红").isLessThan(1000L);
        release.countDown();
        assertThat(done.await(3, TimeUnit.SECONDS)).as("后台确实把它跑完了").isTrue();
    }

    @Test
    @DisplayName("★★ 同一主体连点两下只排一次 —— 两次读的是同一个库，做的是同一件事")
    void coalescesSameMerchant() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MerchantGoodsService svc = mock(MerchantGoodsService.class);
        when(svc.resyncCommunityPools(anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            first.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 16;
        });

        StoreShelfPortImpl port = new StoreShelfPortImpl(svc);
        port.resyncPools("M1");
        assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();   // 第一次已经在跑
        port.resyncPools("M1");                                  // 跑的过程中又点一下
        release.countDown();
        Thread.sleep(300);
        assertThat(calls.get()).as("第二次合并掉；另一个主体不受影响").isLessThanOrEqualTo(2);

        // 主体不同 = 两件事，不能合并
        CountDownLatch other = new CountDownLatch(1);
        when(svc.resyncCommunityPools("M2")).thenAnswer(inv -> { other.countDown(); return 1; });
        port.resyncPools("M2");
        assertThat(other.await(3, TimeUnit.SECONDS)).as("别的主体照常排").isTrue();
    }

    @Test
    @DisplayName("重建抛了也不往上冒 —— 范围已经保存成功，不该因为派生索引失败而回滚")
    void swallowsFailure() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        MerchantGoodsService svc = mock(MerchantGoodsService.class);
        when(svc.resyncCommunityPools(anyString())).thenAnswer(inv -> {
            called.countDown();
            throw new IllegalStateException("boom");
        });
        new StoreShelfPortImpl(svc).resyncPools("M1");
        assertThat(called.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);   // 后台线程没有把异常抛给任何人
    }
}
