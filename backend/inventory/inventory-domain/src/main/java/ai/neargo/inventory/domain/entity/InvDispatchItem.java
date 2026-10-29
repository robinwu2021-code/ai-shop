package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 出库池记录：交接凭证，不是库存（TDD §2.9）。逐件物料一件一条，数量物料一行一条。
 *
 * <p>成套：从属记录（宝）的 {@code parentDispatchId} 指向主记录（柜），随主记录调整 / 撤回 / 签收；
 * 单独动从属记录被拒绝。
 */
@Getter
@Setter
@TableName("inv_dispatch_item")
public class InvDispatchItem extends InvMutableEntity {
    private String dispatchId;
    private String ownerId;
    private String outboundNo;
    private Integer lineNo;
    private String itemId;
    /** 从哪个库位发出（撤回时回到这里） */
    private String locationId;
    private String serialId;
    private String serialNo;
    /** 逐件：发出时关闭的那一个在库周期 */
    private String episodeId;
    private BigDecimal qty;
    private String parentDispatchId;
    private String rootTargetType;
    private String rootTargetRef;
    /** AVAILABLE / CLAIMED / REVOKED / RETURNED */
    private String status;
    /** 每次状态或目标变化 +1 */
    private Integer resourceVersion;
}
