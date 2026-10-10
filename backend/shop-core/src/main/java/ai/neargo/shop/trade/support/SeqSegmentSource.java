package ai.neargo.shop.trade.support;

import ai.neargo.shop.trade.mapper.TradeMappers.SeqMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 DB 领一段序列（ADR-035）。**单独一个 bean**：领段的 bump→peek 必须在同一事务里
 * （行锁保证读到自己 bump 后的值），而 {@code OrderNoGenerator} 自调用走不到 Spring 代理，
 * 所以把带 {@code @Transactional} 的这步拆出来。
 */
@Component
public class SeqSegmentSource {

    private final SeqMapper seqMapper;

    public SeqSegmentSource(SeqMapper seqMapper) {
        this.seqMapper = seqMapper;
    }

    /**
     * 领一段，返回 {@code [lo, hi]}（闭区间，共 {@code step} 个）。
     *
     * <p>{@code sys_sequence} 没有归属列、不进数据域，但订单创建事务里可能带着 SELF/MERCHANT 的
     * 数据域上下文 —— 显式 {@code executeWithoutScope} 免得将来有人给它加了列又忘了这里。
     */
    @Transactional
    public long[] claim(String name, int step) {
        return DataScopeContext.executeWithoutScope(() -> {
            seqMapper.touch(name);
            seqMapper.bump(name, step);
            long hi = seqMapper.peek(name);
            return new long[]{hi - step + 1, hi};
        });
    }
}
