package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在门店交易库（ADR-066 的 trade 模块，ADR-068）。
 *
 * <p>和 {@link PlatformTableMapper} 同一个用意：交易相关的包（trade、product、fulfillment、community、
 * 商户域的资源预约）里混着别的库的表 —— 打印事实、开票资料、物流、评价、积分、专题 ——
 * 按包绑定会把它们一起带走。全局扫描按这个类型排除，交易模块的扫描按这个类型收取。
 *
 * <p>只给「切过去」与「先并再切」的表用；「先留原表」的表（见 docs/technical/零售域-旧表新表对照.md）
 * 留在主库，不打这个标记。
 */
public interface TradeTableMapper<T> extends BaseMapper<T> {
}
