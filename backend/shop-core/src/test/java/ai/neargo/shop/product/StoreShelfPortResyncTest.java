package ai.neargo.shop.product;

import ai.neargo.shop.product.port.StoreShelfPortImpl;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社区池重建这一跳的两条约定（2026-10-07 报障「切换商家配送，反应很慢」那一轮立的）。
 *
 * <p><b>它必须同步跑。</b>一度改成后台线程（当时这一步 24~27 秒），被两条场景测试拦下：
 * 放宽可见性晚一步无所谓，<b>收窄</b>晚一步就是「买家还搜得到、点进去、下单被拦」——
 * 停用门店那条正是出过事故才立起来的。慢要在慢的地方修，不是挪到看不见的地方。
 * 这里钉住「同步」，免得下一个人（包括我）又顺手把它挪走。
 */
class StoreShelfPortResyncTest {

    @Test
    @DisplayName("★★★ 同步跑完才返回 —— 收窄可见性（停用门店/关一路）不能晚一步")
    void rebuildsBeforeReturning() {
        boolean[] done = { false };
        MerchantGoodsService svc = mock(MerchantGoodsService.class);
        when(svc.resyncCommunityPools(anyString())).thenAnswer(inv -> {
            done[0] = true;
            return 16;
        });

        new StoreShelfPortImpl(svc).resyncPools("M1");

        assertThat(done[0]).as("返回时重建已经做完了 —— 挪到后台这一条会红").isTrue();
    }

    @Test
    @DisplayName("★★ 重建抛了也不往上冒：范围已经保存成功，不该因为派生索引失败而回滚")
    void swallowsFailure() {
        MerchantGoodsService svc = mock(MerchantGoodsService.class);
        when(svc.resyncCommunityPools(anyString())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> new StoreShelfPortImpl(svc).resyncPools("M1")).doesNotThrowAnyException();
        verify(svc).resyncCommunityPools("M1");
    }
}
