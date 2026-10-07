package ai.neargo.shop.product.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 撤池行的分批边界（{@code MerchantGoodsServiceImpl.batches}）。
 *
 * <p><b>为什么单独测这一段。</b>撤池原先是 {@code for (row) deleteById(row.getId())} ——
 * 一行一次往返。线上一个社区两万三，一次门店级下架的差集是万级：2026-10-07 实测
 * 多门店主体在一家店下架一件货，差集 47312 行、用时 47.5 秒，端上（uni 默认 60 秒超时）
 * 等于点了没反应，连接池 10 条全被长事务占着。改成分批 IN 之后，
 * <b>唯一新增的出错面就是这个分批的边界</b>。
 *
 * <p>错法是**静默少删几行**：落到业务上是「下架了但某些社区还看得见」，不报任何错。
 * 而既有的场景用例（{@code StoreScopedVisibilityFlowTest} 那几条）撤的都是几十行，
 * 一条也跨不过 1000 这个边界 —— 它们对这段代码永远是绿的，所以边界要在这儿钉。
 */
class PoolWithdrawBatchTest {

    private static List<Integer> ids(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @ParameterizedTest(name = "{0} 行")
    @ValueSource(ints = {0, 1, 2, 999, 1000, 1001, 1999, 2000, 2001, 47312})
    @DisplayName("★★ 分批不丢不重：拼回来必须与原列表逐个相同")
    void everyRowSurvivesTheBatching(int n) {
        List<Integer> all = ids(n);

        List<List<Integer>> batches = MerchantGoodsServiceImpl.batches(all, 1000);

        assertThat(batches.stream().flatMap(List::stream).toList())
                .as("少一个 = 那一行的池没撤，买家还看得见这件货，而没有任何报错")
                .containsExactlyElementsOf(all);
        assertThat(batches).allSatisfy(b -> assertThat(b)
                .as("单批超过上限 = IN 列表无界，撞 max_allowed_packet")
                .hasSizeLessThanOrEqualTo(1000));
        assertThat(batches)
                .as("批数不是 ceil(n/size) = 边界算错了")
                .hasSize((n + 999) / 1000);
    }

    @Test
    @DisplayName("★ 空集不发语句 —— 发一条 `IN ()` 在各家方言下的下场各不相同")
    void emptyYieldsNoBatch() {
        assertThat(MerchantGoodsServiceImpl.batches(List.<Integer>of(), 1000)).isEmpty();
    }

    @Test
    @DisplayName("★ 整除时不留一个空批 —— 空批会让 deleteByIds 收到空集合")
    void exactMultipleLeavesNoEmptyTail() {
        assertThat(MerchantGoodsServiceImpl.batches(ids(3000), 1000))
                .hasSize(3)
                .allSatisfy(b -> assertThat(b).hasSize(1000));
    }
}
