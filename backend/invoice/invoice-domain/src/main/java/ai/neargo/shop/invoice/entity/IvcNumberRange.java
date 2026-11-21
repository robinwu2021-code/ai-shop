package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 财政部配发的字轨号段（V1）。取号与发票落库同一事务，号码不回收。 */
@Getter
@Setter
@TableName("ivc_number_range")
public class IvcNumberRange {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String rangeNo;
    private String tenantNo;
    private String entityNo;
    private String regimeCode;
    /** {@code *} = 主体内各店共用 */
    private String storeNo;
    private String periodCode;
    private String track;
    private Integer startNo;
    private Integer endNo;
    private Integer nextNo;
    /** ACTIVE / EXHAUSTED */
    private String status;
    /** MANUAL 商家登记 / PROVIDER 服务商配给（V5） */
    private String allocationOwner;
    /** 服务商配号申请号（V5，鲸跃 applyid） */
    private String applyId;
    /** 服务商回传的发票种类（V5） */
    private String invoiceType;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
