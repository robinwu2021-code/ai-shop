package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private String channelAccountNo;
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
    // 新库的非空列不带默认值（旧表有）：初值写在实体上，插入时由代码给出。
    private String allocationOwner = "MANUAL";
    /** 服务商配号申请号（V5，鲸跃 applyid） */
    private String applyId;
    /** 服务商回传的发票种类（V5） */
    private String invoiceType;
    /** 期末空白未使用号码已上报服务商的时间（V12）；null = 未报 */
    private LocalDateTime blankReportedAt;
    /** 最近一次空白上报失败的原因（V12）；成功后清空 */
    private String blankReportError;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
