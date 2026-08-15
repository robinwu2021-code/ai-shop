package ai.neargo.shop.fulfillment.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 第三方运力接入配置（P-5.2.4）。
 *
 * <p>⚠️ <b>标的是二期</b>：这一批只做配置存储 + 启停，不接任何真实物流 API
 * （ADR-005 §5：即时配送全外接，一期只做快递 + 商家自送）。
 *
 * <p><b>这个类刻意没有密钥字段，只有一个「配没配」的布尔。</b>
 * 密钥该进配置中心/KMS，不该躺在业务表里；更不该出现在前端契约里，哪怕是脱敏的。
 * 有了字段就迟早有人把真密钥填进去，然后它会跟着一次日志打印流出去。
 *
 * <p>这一页配错的后果不是「显示不对」，而是<b>订单发不出去</b>，所以三条闸在 Service 里硬判：
 * 没配密钥不能启用、还有在途单的不能停用、不能把最后一家启用的也停掉。
 */
@Getter
@Setter
@TableName("ful_carrier")
public class FulCarrier extends BaseEntity {

    private String carrier;
    private String name;
    private Integer enabled;

    /**
     * 数字越小越优先。<b>不允许重复</b> —— 同优先级时选哪家取决于查询顺序，
     * 那是隐性行为：换一次索引或分页，选中的运力就变了，而没有任何东西会报错。
     */
    private Integer priority;

    /** 接入账号，展示一律脱敏。 */
    private String accountMasked;

    /** 密钥是否已配（密钥本身不在这里，见类注释）。 */
    private Integer apiKeyConfigured;

    /** 每日截单时间 HH:mm，过点的单顺延到次日。 */
    private String pickupCutoff;

    /** 承诺时效（小时），必须为正。 */
    private Integer slaHours;
}
