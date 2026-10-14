package ai.neargo.shop.logistics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 轨迹节点（append-only；V132 建为 ful_shipment_trace，V387 改名并加 channel / mode）。 */
@Getter
@Setter
@TableName("lgs_waybill_node")
public class LgsWaybillNode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String shipmentNo;
    private Long at;
    /** 节点原文，原样来自渠道 */
    private String text;
    private String location;
    private Integer latE6;
    private Integer lngE6;
    private String statusCode;
    /** 来自哪个渠道 */
    private String channel;
    /** PUSH / QUERY */
    private String mode;
    private String tenantNo;
    private LocalDateTime createdAt;
}
