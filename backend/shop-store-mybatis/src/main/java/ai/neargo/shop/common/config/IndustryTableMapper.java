package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在行业库（ADR-066 的 industry 模块，ADR-067）。
 *
 * <p>和 {@link TradeTableMapper} 同一个用意：行业插件的 mapper 包里混着交易库、行业库和还留在主库的表，
 * 按包绑定会把它们一起带走。各插件的持久化配置按这个类型把 Mapper 收到行业句柄上。
 *
 * <p>只给落在行业库的表用（见 docs/technical/零售域-第二段建议方案与表清单.md B3）。
 */
public interface IndustryTableMapper<T> extends BaseMapper<T> {
}
