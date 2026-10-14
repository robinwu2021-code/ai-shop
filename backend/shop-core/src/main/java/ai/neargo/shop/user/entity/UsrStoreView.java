package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户逛过的门店（TDD-C端门店化与门店门户）。一人一店一行。
 *
 * <p>{@link #firstSource} 只在第一次写入时定 —— 它记的是这家店<b>怎么进入他的列表</b>，
 * 之后从别的入口进来只刷新 {@link #lastAt} 与 {@link #viewCount}。
 */
@Getter
@Setter
@TableName("usr_store_view")
public class UsrStoreView extends BaseEntity {

    /** 首次来源：点开了别人的分享 */
    public static final String SOURCE_SHARE = "SHARE";
    /** 首次来源：扫店码 / 打开店铺短链 */
    public static final String SOURCE_SCAN = "SCAN";
    /** 首次来源：店铺页的列表 */
    public static final String SOURCE_LIST = "LIST";
    /** 首次来源：搜索结果 */
    public static final String SOURCE_SEARCH = "SEARCH";
    /** 首次来源：商品详情里的「进店」 */
    public static final String SOURCE_GOODS = "GOODS";

    public static final java.util.Set<String> SOURCES =
            java.util.Set.of(SOURCE_SHARE, SOURCE_SCAN, SOURCE_LIST, SOURCE_SEARCH, SOURCE_GOODS);

    private String userNo;
    private String storeNo;
    private String entityNo;
    private String firstSource;
    private String firstInviterNo;
    private Long firstAt;
    private Long lastAt;
    private Integer viewCount;
}
