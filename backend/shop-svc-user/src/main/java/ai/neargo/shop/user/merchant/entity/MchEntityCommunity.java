package ai.neargo.shop.user.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家覆盖的社区（{@code serviceScope=COMMUNITY} 时生效）。
 *
 * <p>为什么是独立关联表而不是 {@code mch_entity} 上的一个 JSON 列：
 * C 端「本社区能看到哪些商家」是**高频反查**，要能按 {@code community_no} 走索引。
 * JSON 列只能正查（这个商家覆盖哪些社区），反查得全表扫。
 */
@Getter
@Setter
@TableName("mch_entity_community")
public class MchEntityCommunity extends BaseEntity {

    private String entityNo;
    private String communityNo;
}
