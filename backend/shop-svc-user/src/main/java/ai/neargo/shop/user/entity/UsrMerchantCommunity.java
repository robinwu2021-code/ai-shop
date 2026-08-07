package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家覆盖的社区（{@code serviceScope=COMMUNITY} 时生效）。
 *
 * <p>为什么是独立关联表而不是 {@code usr_merchant} 上的一个 JSON 列：
 * C 端「本社区能看到哪些商家」是**高频反查**，要能按 {@code community_no} 走索引。
 * JSON 列只能正查（这个商家覆盖哪些社区），反查得全表扫。
 */
@Getter
@Setter
@TableName("usr_merchant_community")
public class UsrMerchantCommunity extends BaseEntity {

    private String merchantNo;
    private String communityNo;
}
