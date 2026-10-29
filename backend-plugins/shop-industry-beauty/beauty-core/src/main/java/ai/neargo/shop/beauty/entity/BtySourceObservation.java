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

    /** Immutable manual domain; historical financial inserts keep the legacy default. */
    private String admissionKind = "LEGACY_FUNDED";

    private String customerKind;
    private String guestNo;
    private Long identityRevision;
    private Long manualEpoch;
    private String manualState;
    private LocalDateTime manualObservedAt;
    private String manualFailureCode;
    private Long manualRevision;
    private String manualHash;
    private Long manualVerifiedRevision;
    private String manualVerifiedHash;
    private String manualVerifiedLines;
    private LocalDateTime manualVerifiedAt;
    private Long manualAdmissionRevision;
    private String manualAdmissionState;
}
