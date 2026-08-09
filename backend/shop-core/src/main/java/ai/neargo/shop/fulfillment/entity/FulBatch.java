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

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RECEIVED = "RECEIVED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private String pickupNo;

    /** 到货日期 YYYY-MM-DD —— 按天分批，与 pickupNo 一起是这堆货的自然键。 */
    private String arriveDate;

    private Integer totalQty;

    /** {@link #STATUS_PENDING} / {@link #STATUS_RECEIVED}。 */
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
