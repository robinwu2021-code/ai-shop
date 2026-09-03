package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台禁售词（商品①）。商家提审商品时前置校验标题。
 *
 * <p><b>词存小写</b>，匹配时两边都转小写 —— 否则配了「iPhone」拦不住「IPHONE」，
 * 而拦不住的那次没有任何痕迹。
 */
@Getter
@Setter
@TableName("sys_banned_word")
public class SysBannedWord extends BaseEntity {

    private String word;

    /** 为什么禁。**会原样出现在给商家的报错里**，所以要写成他看得懂的一句话 */
    private String reason;

    private Boolean enabled;
    private String tenantNo;
}
