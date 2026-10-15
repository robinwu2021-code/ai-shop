package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdStockRestore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Original deduction locks serialize cumulative restore claims from different child refunds. */
public interface StockRestoreMapper extends BaseMapper<PrdStockRestore> { }
