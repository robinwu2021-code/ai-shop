package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Append-only verified owner-disposition hint; every later use must obtain a fresh matching native
 * proof.
 */
@Getter
@Setter
@TableName("cim_source_withdrawal")
public class CimSourceWithdrawal extends BaseEntity {
    private String withdrawalNo;
    private String entityNo;
    private String storeNo;
    private String sourceOwner;
    private String sourceRef;
    private String previousRevision;
    private String withdrawalRevision;
    private String contentHash;
    private String proofSnapshot;
    private String noticeNo;
    private String status;
}
