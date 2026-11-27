package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在基础能力库（ADR-066 的 common 模块，TDD-基础能力库对齐 Q2）。
 *
 * <p>和 {@link PlatformTableMapper} 同一个用意：公共能力的 Mapper 散在好几个包里，和别的库的 Mapper 混在一起，
 * 按包绑定会把它们一起带走。全局扫描按这个类型排除，公共能力的扫描按这个类型收到 {@code common} 句柄上。
 */
public interface CommonTableMapper<T> extends BaseMapper<T> {
}
