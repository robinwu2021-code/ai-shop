package ai.neargo.shop.spi.user;

import java.util.List;

/**
 * message 域 → user 域：谁收藏了这家店。
 *
 * <p><b>为什么要一条 SPI 而不是直接查表</b>：收藏住在 user 域（{@code usr_store_favorite}），
 * 而用它的是 message 域的扇出逻辑 —— 域间不得互相依赖，
 * 跨域只能走 spi（同 {@link MerchantStaffPort}，那条是「谁该被来单提醒吵到」）。
 *
 * <p><b>只给 userNo，不给收藏时间/数量</b>：调用方要的是收件人名单，
 * 多给一个字段就多一条会被别人拿去做判断的路 —— 而那些判断属于 user 域。
 */
public interface StoreFavoritePort {

    /**
     * 收藏过这家店的用户号。
     *
     * <p><b>没人收藏时返回空列表，不是 null</b>：调用方在扇出循环里，
     * 空列表自然什么都不做；null 会在那里炸出一个与业务毫无关系的 NPE。
     *
     * @param merchantNo 商家号（收藏挂在主体上，不是门店）
     */
    List<String> followerUserNos(String merchantNo);
}
