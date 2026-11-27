package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在会员营销库（{@link ModuleDataSources#MEMBER_MARKETING} 句柄）。
 *
 * <p>储值与权益的 Mapper 按包名收到这个句柄（{@link ModuleDataSources#MEMBER_MARKETING_MAPPER_PATTERN}）；
 * 其他领域里表归会员营销库的（商品领域的评价、专题、收藏）不在那个包里，按这个标记收。
 */
public interface MemberMarketingTableMapper<T> extends BaseMapper<T> {
}
