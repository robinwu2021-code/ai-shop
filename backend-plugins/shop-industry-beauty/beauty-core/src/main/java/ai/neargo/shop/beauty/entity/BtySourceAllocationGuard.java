package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 来源行服务分配仲裁；保存本域意图与引用，不复制外部资产权威。 */
@Getter
@Setter
@TableName("bty_source_allocation_guard")
public class BtySourceAllocationGuard extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 仲裁号。 */
    private String guardNo;
    /** 来源类型。 */
    private String sourceKind;
    /** 来源业务号。 */
    private String sourceNo;
    /** 稳定来源行。 */
    private String sourceLineRef;
    /** 权威来源版本。 */
    private Long sourceRevision;
    /** 可信来源数量快照。 */
    private BigDecimal sourceQty;
    /** 当前有效分配数量。 */
    private BigDecimal allocatedQty;
    /** 数量单位。 */
    private String uom;
    /** 来源摘要。 */
    private String sourceHash;
}
