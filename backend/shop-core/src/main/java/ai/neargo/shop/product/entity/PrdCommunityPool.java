package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 社区商品池：**只决定可见性，不存价**（TDD-backend §6.3）。
 *
 * <p>一个商家在哪些社区卖货由它表达。首页「按社区逛」= 这张表 join 商品表，
 * 价格始终来自 {@link PrdSku}。
 */
@Getter
@Setter
@TableName("prd_community_pool")
public class PrdCommunityPool extends BaseEntity {

    private String communityNo;

    /**
     * 这一行是**哪家门店**摆的（V240）。
     *
     * <p>可空：存量行是主体级口径写下的，迁移回填成该主体的默认店；
     * 回填不到的留空，读侧按「未知门店」处理 —— <b>不要兜底成任意一家</b>，
     * 兜错店的表现是「单发到了没有这件货的店」。
     */
    private String storeNo;
    private String goodsNo;
    private String entityNo;

    /**
     * 排序权重。<b>至今每一处写入都写死 0</b>（运营端从来没做那个置顶入口）。
     *
     * <p>规划里它将装「这家门店到这个社区的距离」，用于一件货被两家店摆着时
     * 挑最近的那家展示 —— 但那要等唯一键换成三元组、池里真的可能出现多行之后
     * （见「可见性按门店算-方案」第 3/4 步）。在那之前它没有读者，保持 0。
     */
    private Integer sortWeight;
}
