package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Immutable original supplier statement evidence; no local money authority. */
@Getter
@Setter
@TableName("cim_statement")
public class CimStatement extends BaseEntity {
    private String statementNo;
    private String entityNo;
    private String accountNo;
    private String profileNo;
    private String statementRef;
    private String sourceRevision;
    private String expectedHash;
    private String documentHash;
    private String payloadJson;
    private String lookupJson;
    private String currency;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private String status;
    private String operationNo;
    private String originalReceiptRef;
}
