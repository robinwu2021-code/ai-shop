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
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
