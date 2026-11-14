package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A null entity is the genuine platform-default source scope, not a fabricated merchant account.
 */
@Getter
@Setter
@TableName("cim_tax_source_notice")
public class CimTaxSourceNotice extends BaseEntity {
    private String noticeNo;
    private String sourceEventNo;
    private String entityNo;
    private String settingKey;
    private Long sourceVersion;
    private String contentHash;
    private String payloadHash;
    private LocalDateTime effectiveAt;
    private Long scopeCursor;
    private String status;
    private LocalDateTime nextCheckAt;
    private Integer attempts;
}
