package ai.neargo.shop.marketing.slot.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 内容位：运营配的首页楼层 / 轮播 / 频道。
 *
 * <p><b>这一版只有 {@link #HOME_FLOOR} 真的被端消费</b>，另外两种能建、能排期，
 * 但没有任何端会去读它 —— 这是明说的现状，不是漏掉的接线：
 * C 端首页没有轮播位、也没有频道页，没有承接位就定不了「跳去哪」那个模型，
 * 定了必返工。
 *
 * <p><b>它替换掉的是一段兜底</b>：{@code GoodsServiceImpl#promoted} 一直按销量倒序，
 * 也就是说首页那一屏展示的是**销量事实**，而页面上写的是「推荐」。
 * 配了内容位就按运营给的顺序展示；<b>没配仍然走销量兜底</b> ——
 * 删掉兜底的话，没人配过的社区首页上那一屏直接空了。
 */
@Getter
@Setter
@TableName("mkt_content_slot")
public class MktContentSlot extends BaseEntity {

    /** 首页楼层：一组有序商品。**当前唯一有 C 端消费方的形态**。 */
    public static final String HOME_FLOOR = "HOME_FLOOR";
    /** 轮播图。要 图片 + 跳转目标，C 端还没有承接位。 */
    public static final String BANNER = "BANNER";
    /** 频道入口。C 端还没有频道页。 */
    public static final String CHANNEL = "CHANNEL";

    private String slotNo;

    /** 运营自己认的名字，**不出现在 C 端**。 */
    private String title;

    private String kind;

    /** 同 kind 内展示顺序，小的在前。列名避开 `sort`（MariaDB/H2 保留字判定不一致）。 */
    private Integer sortNo;

    /** JSON 数组；**空 = 全部社区**。与商品社区池同一口径：池外的货用户看得到也买不到。 */
    private String communityNos;

    /** JSON 数组，<b>有序</b> —— 数组顺序就是楼层里的展示顺序。 */
    private String goodsNos;

    private Long onlineAt;
    private Long offlineAt;

    /**
     * 与 {@link #offlineAt} 是**两件事**：关掉即刻不再展示，不等下线时间。
     * 出了问题时运营要的是「现在就下」，而不是去改一个时间。
     */
    private Boolean enabled;

    private java.time.LocalDateTime archivedAt;
}
