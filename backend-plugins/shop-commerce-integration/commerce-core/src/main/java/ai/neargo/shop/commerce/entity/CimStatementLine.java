package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Immutable original supplier statement evidence; no local money authority. */
@Getter
@Setter
@TableName("cim_statement_line")
public class CimStatementLine extends BaseEntity {
    private String lineNo;
    private String entityNo;
    private String statementNo;
    private String accountNo;
    private String storeNo;
    private String connectionNo;
    private String lineRef;
    private String kind;
    private String externalStoreRef;
    private String externalOrderRef;
    private String externalAfterSaleRef;
    private String currency;
    private String differenceCode;
    private String amountsJson;
}
