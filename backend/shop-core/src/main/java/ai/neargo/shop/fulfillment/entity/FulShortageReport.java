package ai.neargo.shop.fulfillment.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 自提点上报的缺件 / 破损（P-5.1.2 缺货标记回传 · B-10.3.4）。
 *
 * <p><b>为什么它必须是一张表而不是一条时间线文本</b>：
 * {@code PickupService.reportShortage} 收下了 {@code skuNo} 却原地丢掉，
 * 只往订单时间线追加一句「自提点上报短少：xxx」。买家看得到，
 * 而平台侧「这个点今天哪个 SKU 缺了几件」无从算起 —— 自由文本没法聚合。
 *
 * <p>于是平台分拣汇总里的缺件数只能恒为 0，页面上那个红色徽标**永远不亮**。
 * 一个永远不亮的告警比没有告警更坏：看的人会以为「今天没缺件」。
 *
 * <p><b>只留痕，不改状态、不退款</b>（与 {@code reportException} 同口径）：
 * 责任在供货方还是承接方尚未定（矩阵 M4），自动退款等于默认平台兜底。
 */
@Getter
@Setter
@TableName("ful_shortage_report")
public class FulShortageReport extends BaseEntity {

    public static final String KIND_SHORTAGE = "SHORTAGE";
    public static final String KIND_DAMAGE = "DAMAGE";

    private String subOrderNo;
    private String pickupNo;

    /**
     * 哪个 SKU 缺了。<b>分拣是按规格分堆的</b> —— 到商品就分不出是哪个规格少了，
     * 而店主要照着分拣单去找的正是那一堆。
     */
    private String skuNo;

    /** {@link #KIND_SHORTAGE} / {@link #KIND_DAMAGE}。两者的售后路径不同。 */
    private String kind;

    /** 缺件数。端上今天只报「缺了」不报「缺几件」，所以默认 1 —— 给 0 会让汇总恒为 0。 */
    private Integer qty;

    private String note;

    /** 上报人 userNo —— 缺件是责任判定的输入，必须追到人。 */
    private String reporterNo;

    private Long at;
}
