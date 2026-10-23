package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Nonmonetary canonical source watermark; no execution or payment authority. */
@Getter
@Setter
@TableName("bty_source_observation")
public class BtySourceObservation extends BaseEntity {
    /** Authorized entity. */
    private String entityNo;

    /** Canonical owner store. */
    private String storeNo;

    /** STORE_ORDER only. */
    private String sourceKind;

    /** Canonical sub-order number. */
    private String sourceNo;

    /** BEAUTY only. */
    private String checkoutCaller;

    /** Unique owner revision domain. */
    private String checkoutOperationNo;

    /** Canonical ordinary payment root. */
    private String orderNo;

    /** Verified USER identity. */
    private String buyerNo;

    /** Local monotonic source-call fence. */
    private Long observationEpoch;

    /** UNKNOWN or READY or BLOCKED. */
    private String state;

    /** Highest observed owner revision. */
    private Long sourceRevision;

    /** Hash paired with highest owner revision. */
    private String sourceHash;

    /** Last complete verified owner revision. */
    private Long verifiedRevision;

    /** Hash paired with verified lines. */
    private String verifiedHash;

    /** Last verified identity and quantity lines; no money. */
    private String verifiedLines;

    /** Last complete owner observation time. */
    private LocalDateTime verifiedAt;

    /** Current epoch completion time; null while reading. */
    private LocalDateTime observedAt;

    /** Safe current blocking reason. */
    private String failureCode;
}
