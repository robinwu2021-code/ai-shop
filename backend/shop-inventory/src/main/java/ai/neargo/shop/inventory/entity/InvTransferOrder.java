package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 调拨单：编排一出一入，中间停在 TRANSIT */
@Getter
@Setter
@TableName("inv_transfer_order")
public class InvTransferOrder extends InvMutableEntity {

    private String transferNo;

    private String ownerId;

    private String fromLocationId;

    /** 与 from 不同，且必须同一个业主 —— 跨业主的移动不是调拨，是买卖 */
    private String toLocationId;

    /** DRAFT / SHIPPED / RECEIVED / VOIDED */
    private String status;

    /** 发出时生成：from -> TRANSIT */
    private String shippedOutboundNo;

    /** 收到时生成：TRANSIT -> to */
    private String receivedInboundNo;

    private LocalDateTime shippedAt;

    /**
     * 承运方编号；空 = 自己送或没记。
     *
     * <p><b>取值是主库 {@code ful_carrier.carrier}</b>（{@code SF} / {@code JD} 这类），
     * 不是那张表的自增 id —— 列名叫 {@code carrier_no} 只是本域的命名习惯。
     * 跨库不能外键，所以这里存的是对方的业务键。
     *
     * <p>可选列表走 {@code GET /biz/fulfillment/carriers}（只列启用的）。
     */
    private String carrierNo;

    /**
     * 发货当时的承运方名字<b>快照</b>。
     *
     * <p>与 {@link #carrierNo} 并存不是冗余：进销存是独立库、跨库不能外键，
     * 而且承运方三个月后改了名，这张历史单该显示当时那个名字。
     */
    private String carrierName;

    /** 运单号。<b>值不是实体</b>，所以端上是输入框不是选择器 */
    private String trackingNo;

    private LocalDateTime receivedAt;

    private String operator;

    private String remark;

}
