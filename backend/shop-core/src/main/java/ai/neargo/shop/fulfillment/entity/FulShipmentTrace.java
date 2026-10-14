package ai.neargo.shop.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 快递轨迹节点（append-only）。
 *
 * <p><b>轨迹是承运商的事实，平台不编。</b>运营端页面上没有「手工加一条轨迹」——
 * 平台自己写的轨迹一旦与承运商记录不一致，纠纷时反而站不住。
 *
 * <p>这张表今天只有一个写入方：<b>换运单号那条留痕</b>，且它会写明是平台写的。
 * 接了承运商回传之后，回传节点与这条并存 —— 所以只追加、不修改、不删除。
 */
@Getter
@Setter
// V387 起表名改为 lgs_ 前缀（TDD-物流模块 批 1）。本类是过渡：批 2 起由 logistics 模块的实体取代
@TableName("lgs_waybill_node")
public class FulShipmentTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shipmentNo;

    private Long at;

    /** 节点描述，原样来自承运商；平台写的那条会注明来源。 */
    private String text;

    private String location;

    private String tenantNo;

    private LocalDateTime createdAt;

    /** 行政区中心纬度 ×1e6。**不是快件 GPS** —— 聚合器只给到行政区中心点 */
    private Integer latE6;

    /** 行政区中心经度 ×1e6 */
    private Integer lngE6;

    /** 承运商/聚合器的高级状态码，步骤条区分「派送中」用 */
    private String statusCode;
}
