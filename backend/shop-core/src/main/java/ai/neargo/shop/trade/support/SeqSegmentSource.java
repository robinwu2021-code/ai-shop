package ai.neargo.shop.trade.support;

import ai.neargo.shop.trade.order.mapper.SeqMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
    /**
     * ★ {@code REQUIRES_NEW}：领段必须在**独立事务**里提交 —— 号段的铁律是「领了就不回退」。
     *
     * 默认的 {@code REQUIRED} 会把这步并进外层下单事务；一旦下单回滚（业务失败，或测试 @Transactional
     * 回滚），bump 跟着回滚、DB 游标退回，而内存段已经发出去了 —— 下一次领段就领到重叠的号段，重号。
     * 2026-10-11 全量测试抓到：跨 context 的场景测试回滚后 sys_sequence 退回，订单号撞 uk_order_no。
     * 独立事务提交后，即使外层回滚、订单没落库，那几个号也只是浪费掉（无害），绝不重用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long[] claim(String name, int step) {
        return DataScopeContext.executeWithoutScope(() -> {
            seqMapper.touch(name);
            seqMapper.bump(name, step);
            long hi = seqMapper.peek(name);
            return new long[]{hi - step + 1, hi};
        });
    }
}
