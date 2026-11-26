package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在平台基础库（ADR-066 的 platform 模块）。
 *
 * <p>为什么用标记而不是按包绑定：商户域的 Mapper 都挤在一个包里，其中只有一部分的表属于平台基础库，
 * 其余的在门店交易库、支付商户库、账务结算库。按包绑定会把别的库的 Mapper 一起带走。
 * 全局扫描按这个类型排除，平台模块的扫描按这个类型收取 —— 一个 Mapper 归哪个库，写在它自己的声明上。
 */
public interface PlatformTableMapper<T> extends BaseMapper<T> {
}
