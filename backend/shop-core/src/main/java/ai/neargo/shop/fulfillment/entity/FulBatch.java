package ai.neargo.shop.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 到货批次：自提点按「哪一天到的货」把子单归堆，站长按堆签收。
 *
 * <p>没有它，站长只能一单一单核对到货 —— 一个点一天几十单时，
 * 漏收与错收都无法追溯到具体哪一批。
 *
 * <p><b>注意批次与核销是两件事</b>：本表记的是<b>货到没到自提点</b>（站长签收），
 * {@link FulVerifyLog} 记的是<b>顾客有没有取走</b>。两者时间不同、责任人不同，
 * 混用会让「货到了但没人取」这种最常见的纠纷查不出卡在哪一环。
 */
@Getter
@Setter
@TableName("ful_batch")
public class FulBatch {

    /**
     * 四态有序推进（V130 起）：计划 → 已发车 → 已到货 → 已签收。
     *
     * <p><b>中间两态是责任分界。</b>只有「待收/已收」两态时，「车还没发」与
     * 「车发了但没到」无法区分 —— 而货丢在哪一段恰恰是自提履约里最常见的纠纷。
     *
     * <p><b>不许跳步</b>：没到货就签收，等于把「货到底交没交到点上」这条判据跳过去。
     */
    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_ARRIVED = "ARRIVED";
    public static final String STATUS_SIGNED = "SIGNED";

    /** 状态机：每个状态只有一条出路。放在实体上是因为它是这张表的不变量，不是某个 Service 的私事。 */
    public static final java.util.Map<String, String> NEXT = java.util.Map.of(
            STATUS_PLANNED, STATUS_DISPATCHED,
            STATUS_DISPATCHED, STATUS_ARRIVED,
            STATUS_ARRIVED, STATUS_SIGNED);

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private String pickupNo;

    /** 目的社区。平台是跨社区调度的，按社区筛是这一页的主筛项。 */
    private String communityNo;

    /** 到货日期 YYYY-MM-DD —— 按天分批，与 pickupNo 一起是这堆货的自然键。 */
    private String arriveDate;

    /**
     * 计划到货时间戳。与 {@link #arriveDate} 分开：那个是按天分批的自然键，
     * 而一天可能分早晚两车 —— 只有日期的话，两车的迟到无法分别追责。
     */
    private Long planArriveAt;

    /** 车次/司机标识。一期人肉填，二期接运力系统（ADR-005 §5）。 */
    private String vehicle;

    /**
     * 历史列（V1）。<b>平台侧的件数不读它</b> —— 件数从 {@code ord_sub_order} 现算。
     *
     * <p>存一份计数器的代价是「总览说 3 单、点进去只有 2 单」，而这种不一致
     * 既不报错也无从复现。留着这一列只是因为删列比留着更贵。
     */
    private Integer totalQty;

    /** {@link #STATUS_PLANNED} / {@link #STATUS_DISPATCHED} / {@link #STATUS_ARRIVED} / {@link #STATUS_SIGNED}。 */
    private String status;

    private Long receivedAt;

    /** 签收人 userNo —— 少收了货要能追到是谁签的。 */
    private String receivedBy;

    private String tenantNo;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
    private Long version;
    private Integer deleted;
}
