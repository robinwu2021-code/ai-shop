package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一片地方的地图结果缓存（V206）。
 *
 * <p><b>为什么整块存 JSON</b>：这张表只回答一个问题 ——「这一片有哪些小区」。
 * 摊成一条小区一行要连带解决去重、跨片同名、增量更新与全局排序，那是另一件事；
 * 需要按名字全局搜小区的那天再摊开，这张表就是现成的数据源。
 */
@Getter
@Setter
@TableName("geo_poi_cache")
public class GeoPoiCache extends BaseEntity {

    public static final String ESTATE = "ESTATE";
    public static final String AMAP = "AMAP";

    /** 这一片的区划码：街道 9 位或村/社区 12 位 */
    private String scopeCode;

    /** 上一级码。列表要在上一级的每一行上预告「12 个小区」，靠它一次批量取 */
    private String parentCode;

    private String kind;

    private String source;

    /** 归一后的数组 JSON：[{name,address,latE6,lngE6,poiId}] */
    private String payload;

    /** 条数。单独一列是为了批量预告时不必解 JSON */
    private Integer itemCount;

    /** 最后一次问地图的时刻。TTL 与后台刷新看它 */
    private LocalDateTime fetchedAt;
}
