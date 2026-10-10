package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.spi.reach.ConsumerProfile;

import java.util.Map;

/**
 * 「这个消费者命中了哪些门店的哪几条范围项」（ADR-034）。
 *
 * <p>两种实现、一个语义：{@link DbHitFinder} 用索引点查（{@code ref_code IN (祖先码/聚落号)}、
 * {@code cell_id IN (token)}），服务目录等消费者侧请求；{@link InMemoryHitFinder} 对一家店的范围项做同样的
 * 集合成员判断，服务反向展开与预览。两者必须给出相同结果 —— 由 {@code HitFinderParityTest} 守着。
 *
 * <p>只找命中，不判可见：排除先算、路类型、fail-closed 都在 {@link ReachRule} 一处。
 */
public interface HitFinder {

    /**
     * @param profile 消费者画像
     * @param storeNo 为空 = 全平台；非空 = 只看这一家（单店判定）
     * @return 门店号 → 命中结果；没命中任何项的店不在 map 里
     */
    Map<String, StoreHits> find(ConsumerProfile profile, String storeNo);
}
