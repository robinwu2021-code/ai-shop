package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Unique execution and after-sale keys prevent a second cash instruction for the same decision. */
public interface RefundExecutionMapper extends BaseMapper<OrdRefundExecution> { }
