package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 一张证照 + 它下面我能进的门店。门店选择器（01 屏）按这个分组渲染。
 *
 * <p><b>为什么分组而不是拍平成一个门店列表</b>：两家店同名是常事（「文三路店」
 * 在两张执照下各有一家），拍平之后老板在切换器里看到两个一模一样的条目，
 * 点哪个都不知道自己进了哪张执照 —— 而进错执照的表现是「商品怎么全没了」。
 *
 * <p>只有一张证照时端上应当<b>不渲染分组头</b>：绝大多数商家是这一支，
 * 给他们看一个只有一组的分组是纯负担。
 */
public record EntityStoresVO(EntityVO entity, List<StoreVO> stores) {
}
