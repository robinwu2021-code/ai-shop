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

    /**
     * 某个区划下的**开放**社区（ADR-013 阶段二，展开商家覆盖范围用）。
     *
     * <p>靠国标编码的层级性做<b>前缀匹配</b>：区县 {@code 330106} 命中
     * {@code 330106}（挂到区）与 {@code 330106002}（挂到街道）两种归属；
     * 城市 {@code 3301} 命中杭州下的所有区与街道。
     * 这正是当初坚持用国标码而不自造的回报 —— 自造码没有这个性质，
     * 展开就得先把整棵树查出来再逐层求并。
     *
     * <p><b>只给开放的</b>：关城的社区不该因为「商家框了这个区」而重新可见。
     *
     * @param regionPrefix 区划码前缀；空返回空集合（**不返回全部**，
     *                     空前缀匹配一切是最危险的默认值）
     */
    java.util.List<String> openCommunityNosUnderRegion(String regionPrefix);

    /**
     * 社区展示名。查不到时<b>返回社区号本身</b>，不返回空 ——
     * 页面上宁可显示 C0001，也不要显示一个空白的覆盖项：
     * 空白会让商家以为「这一条坏了」而去删掉它。
     */
    String communityName(String communityNo);

    /**
     * 批量取自提点名（门店档案展示挂靠的取货点，P-11.2.1c）。
     *
     * <p><b>批量而不是逐个</b>：一家店可能挂多个点，逐个查就是 N+1。
     *
     * @return 自提点号 → 名称；<b>查不到的不出现</b>，调用方自己决定显示点号还是留空
     */
    java.util.Map<String, String> pickupNames(java.util.Collection<String> pickupNos);

    /**
     * 这些社区的坐标（gcj02, E6）。<b>建社区池时用来算「哪家店离这儿最近」。</b>
     *
     * <p>批量取而不是逐个查：一次上架要给几十个社区建池行，逐个查就是几十次往返。
     *
     * @return 只含**有坐标**的那些；没标过点的社区不出现在结果里 ——
     *         调用方据此走「算不出距离」那一支，而不是拿 (0,0) 去算出一个地球另一端的距离
     */
    java.util.Map<String, int[]> coordsOfCommunities(Collection<String> communityNos);
}
