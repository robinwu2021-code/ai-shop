package ai.neargo.shop.community.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 固定地址库的一条：**某个坐标格子叫什么名字，上次核对是什么时候**。
 *
 * <p><b>缓存就是它，不是另一层。</b> 它同时是答案库、是防止频繁调地图的挡板、
 * 也是逐步长大的自有资产 —— 这三样本来就该是同一份数据。做成
 * 「缓存表 + 固定地址表」两层的话，同一个地方会有两条记录、两个时间戳，
 * 而它们不一致时没有任何人会发现。
 *
 * <p><b>与 {@link CmtCommunity} 的分工</b>：聚落是**业务对象**（围栏、商品池、
 * 开没开通），我们自己维护，没有过期一说；这张表只回答「这儿叫什么」。
 * 用得最多的那些 POI 会被升级成聚落（{@link #promotedNo}），升级之后
 * 它就走聚落那条更靠前的路，彻底不再依赖地图。
 */
@Getter
@Setter
@TableName("geo_place")
public class GeoPlace extends BaseEntity {

    /** 建筑/兴趣点 —— 最具体的一档，也是唯一值得沉淀成聚落的一档 */
    public static final String KIND_POI = "POI";
    /** 小区 / 楼盘 */
    public static final String KIND_AOI = "AOI";
    /** 街道 + 门牌 */
    public static final String KIND_STREET = "STREET";
    /** 只推得出区县 */
    public static final String KIND_REGION = "REGION";

    /** geohash 精度 8（约 38m×19m）。同一栋楼里所有人命中同一行 */
    private String geoKey;

    /** 这个格子里**首次落库的那个点**，不是格子中心 —— 用来算命中点与它的偏移 */
    private Integer latE6;
    private Integer lngE6;

    private String name;
    private String kind;
    private String address;
    private String regionCode;
    private String township;

    /**
     * 上次核对的时刻。
     *
     * <p><b>超期不等于作废</b>：它只说「该回头核一次了」。命中一条超期的行时
     * 先把它用上（用户在等首页，不是在等「这个地名是不是最新的」），刷新放到后台。
     */
    private LocalDateTime verifiedAt;

    private Integer hitCount;
    private LocalDateTime lastHitAt;

    /** 已升级成聚落的话指过去；指了就说明这一行不再是唯一答案 */
    private String promotedNo;
}
