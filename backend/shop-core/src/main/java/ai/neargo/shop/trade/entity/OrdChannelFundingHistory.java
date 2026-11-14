package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Immutable merchant admission evidence; supplier collection never posts a local payment. */
@Getter
@Setter
@TableName("ord_channel_funding_history")
public class OrdChannelFundingHistory extends BaseEntity {
    private String historyNo;
    private String entityNo;
    private String storeNo;
    private String admissionNo;
    private String previousCollection;
    private String admittedCollection;
    private String previousSnapshot;
    private String admittedSnapshot;
    private String originalCollectionHash;
}
