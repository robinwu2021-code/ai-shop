package ai.neargo.shop.trade.support;

import ai.neargo.shop.numbering.store.JdbcDocumentNumberRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 订单号从 DB 领一段序列（ADR-035）。游标与单据号同在发号的唯一存储里（{@code sys_sequence}，
 * TDD-基础能力库对齐 1d），这里只是订单域的入口，不再自带一套 Mapper。
 *
 * <p>★ 领段在<b>独立事务</b>里提交（由存储负责）：号段的铁律是「领了就不回退」。并进外层下单事务的话，
 * 下单一回滚游标就退回，而内存段已经发出去了 —— 下一次领段领到重叠的号段，重号
 * （2026-10-11 全量测试抓到过：回滚后订单号撞 uk_order_no）。
 */
@Component
public class SeqSegmentSource {

    private final JdbcDocumentNumberRepository numbers;

    public SeqSegmentSource(@Qualifier("coreDocumentNumbers") JdbcDocumentNumberRepository numbers) {
        this.numbers = numbers;
    }

    /** 领一段，返回 {@code [lo, hi]}（闭区间，共 {@code step} 个）。 */
    public long[] claim(String name, int step) {
        return numbers.claimSegment(name, step);
    }
}
