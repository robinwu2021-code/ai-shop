package ai.neargo.shop.user.dto;

/**
 * C 端店铺列表的一行 = 一家<b>门店</b>（TDD-C端门店化与门店门户 AC1）。
 *
 * <p>此前一行是一个主体：同一主体下四家店在小程序上都叫主体名。
 * 这里的 {@code storeName} 是门店自己的名字；主体只在资质页露面。
 *
 * @param status     {@code ACTIVE} 营业 / {@code READONLY} 商家自助停用（「我的店」里压淡显示「暂停营业」）
 * @param openNow    按营业时间算的「现在开没开」；营业时间没填或写法认不出时为 {@code null}（不说，不猜）
 * @param distanceM  离用户多少米；用户没给位置、或门店还没在地图上选点时为 {@code null}
 * @param rating     门店评分 0–5；{@code ratingCount = 0} 是暂无评价，不是 0 分
 * @param relation   只在「我的店」里有：他和这家店是什么关系；「附近」里为 {@code null}
 */
public record StoreCardVO(String storeNo, String storeName, String entityNo, String logo,
                          String status, Boolean openNow, String openHours, String address,
                          Integer distanceM, double rating, int ratingCount,
                          Relation relation) {

    /**
     * @param orderCount   成交过几单（{@code OrdSubOrder.PAID} 口径，不含取消）；0 = 只逛过
     * @param lastOrderAt  最近一次成交（毫秒）；没买过为 {@code null}
     * @param lastViewAt   最近一次进店（毫秒）；只买过、没有进店记录的老关系为 {@code null}
     * @param firstSource  这家店是怎么进入他的列表的：SHARE / SCAN / LIST / SEARCH / GOODS；
     *                     只有购买、没有进店记录时为 {@code null}
     */
    public record Relation(int orderCount, Long lastOrderAt, Long lastViewAt, String firstSource) {
    }
}
