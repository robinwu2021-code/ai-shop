package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Durable reread work retains original source identity and a bounded scope cursor. */
@Getter
@Setter
@TableName("cim_source_notice")
public class CimSourceNotice extends BaseEntity {
    private String noticeNo;
    private String entityNo;
    private String storeNo;
    private String sourceSystem;
    private String noticeKind;
    private String sourceEventNo;
    private String sourceOwner;
    private String sourceRef;
    private String payloadHash;
    private LocalDateTime effectiveAt;
    private LocalDateTime nextCheckAt;
    private Long scopeCursor;
    private String status;
    private Integer attempts;
    private String errorCode;
}
