package ai.neargo.shop.user.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.user.dto.StoreCardVO;

import java.util.List;

/**
 * C 端店铺页：「我的店」与「附近」（TDD-C端门店化与门店门户 AC2–AC8）。
 *
 * <p>放在 user 域、挨着收藏：「我的店」回答的是<b>这个人</b>和哪些店有关系。
 * 门店长什么样走 {@code StoreDirectoryPort}，买过没有走 {@code PurchaseHistoryPort}。
 */
public interface MyStoreService {

    /**
     * 记一次进店。一人一店一行：第一次插入并定下首次来源；之后只刷新最近时间与次数。
     *
     * @param source    SHARE / SCAN / LIST / SEARCH / GOODS；认不出的按 LIST 记
     * @param inviterNo 分享人；只在首次来源是分享时落库
     */
    void recordView(String userNo, String storeNo, String entityNo, String source, String inviterNo);

    /**
     * 我的店：逛过（近 N 天）∪ 买过（不设期限），按最近一次接触倒序；并列时买得多的在前。
     * 停用的门店照样列出（{@code status=READONLY}），主体被封 / 门店删除的不列。
     */
    List<StoreCardVO> mine(String userNo, Integer latE6, Integer lngE6);

    /**
     * 附近：能卖到这里的营业门店，去掉「我的店」已有的（登录时）。
     * 有位置按距离升序、没坐标的门店排最后；没位置按评分。
     */
    PageData<StoreCardVO> nearby(String userNo, Integer latE6, Integer lngE6, String communityNo,
                                 String keyword, long page, long size);
}
