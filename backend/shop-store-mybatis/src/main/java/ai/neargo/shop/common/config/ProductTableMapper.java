package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 是商品领域的，表在门店交易库（{@link ModuleDataSources#PRODUCT} 句柄）。
 *
 * <p>商品领域的 Mapper 只能打这个标记（评价、专题、收藏打 {@link MemberMarketingTableMapper}）：
 * {@code ProductDatabaseIsolationTest} 逐个核对，并核对它们用到的表都在门店交易库的设计 DDL 里。
 * 全局扫描按这个类型排除，商品模块的扫描按这个类型收取。
 */
public interface ProductTableMapper<T> extends BaseMapper<T> {
}
