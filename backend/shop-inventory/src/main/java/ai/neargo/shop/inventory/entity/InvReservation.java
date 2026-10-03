package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 预留：占住一批货等人来取 */
@Getter
@Setter
@TableName("inv_reservation")
public class InvReservation extends InvMutableEntity {

    private String reservationId;

    private String ownerId;

    /** 调用方订单号。网络超时重试是常态，不幂等就会预留两次而第二次没人释放 */
    private String externalRef;

    /** HELD / COMMITTED / RELEASED / EXPIRED */
    private String status;

    /** ★ 到期自动回收。调用方可能永远不回来 —— 跨进程之后兜底必须在本领域内 */
    private LocalDateTime expiresAt;

    private LocalDateTime committedAt;

    private LocalDateTime releasedAt;

    /** commit 时生成的销售出库单 */
    private String outboundNo;

}
