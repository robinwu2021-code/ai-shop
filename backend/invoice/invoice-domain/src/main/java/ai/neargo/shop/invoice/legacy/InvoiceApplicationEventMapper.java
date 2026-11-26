package ai.neargo.shop.invoice.legacy;

import ai.neargo.shop.invoice.entity.IvcInvoiceApplicationEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 开票申请的事件留在主库原表（TDD-多数据源与发票打印对齐新库 §2.4 乙类）。
 *
 * <p>新库不建这张表：它是历史记录，去向是统一审计。统一审计落地之前仍写在这里，
 * 但不再和申请同事务 —— 见 {@link InvoiceHistory}。
 */
public interface InvoiceApplicationEventMapper extends BaseMapper<IvcInvoiceApplicationEvent> {
}
