package ai.neargo.shop.spi.user;

import java.util.Collection;
import java.util.List;

/**
 * merchant / product → community：社区的开放状态与积分开关。
 *
 * <p>刻意只暴露<b>两个判断结论</b>，而不是返回社区实体列表。原因在
 * {@link #anyPointsEnabled(Collection)} 上写得最清楚：那不是一次查询，
 * 是一条**业务规则**（一个开着就算开）。规则跟着数据走，才不会被两处各写一遍。
 */
public interface CommunityQueryPort {

    /** 当前开放（{@code status=OPEN}）的社区编号。商家服务范围为 CITY/PLATFORM 时用它展开。 */
    List<String> openCommunityNos();

    /**
     * 这批社区里<b>是否至少有一个开着积分</b>。
     *
     * <p>为什么是「任一」而不是「全部」：商家可跨社区经营（ADR-009 三档范围）。
     * 要求全部开启的话，跨社区商家会因为其中某个尚未开放积分的社区而被整体禁掉——
     * 而他在其他社区明明是可以用的。
     *
     * @param communityNos 空集合返回 {@code false}
     */
    boolean anyPointsEnabled(Collection<String> communityNos);
}
