package ai.neargo.shop.logistics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一家承运商在某个物流渠道里叫什么（V387）。
 *
 * <p>渠道的「覆盖哪些承运商」就是这张表里它有哪些行 —— 圆通直连只有 {@code YTO} 一行，所以只覆盖圆通单。
 * 按渠道加列（{@code kd100_code}、{@code wx_delivery_id}…）的话，每接一家渠道就要改一次表。
 */
@Getter
@Setter
@TableName("lgs_carrier_code")
public class LgsCarrierCode {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 我方承运商码（SF / STO / YTO …，与微信 delivery_id 同一套） */
    private String carrier;
    /** 渠道名：kuaidi100 / wx / yto / … */
    private String channel;
    /** 该渠道里的叫法 */
    private String code;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
