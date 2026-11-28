package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment arrangement; not external resource authority. */
@Getter
@Setter
@TableName("ord_appointment_revision")
public class BtyAppointmentArrangement extends BaseEntity {
    /** 存在门店交易库 {@code ord_appointment_revision}（§2.6 第 3 项，预约修订；预约准备随第 4 项并入）。共用表的 {@code tenant_no} 没有库默认值，平台审计也不填它：在这里带上。 */
    public BtyAppointmentArrangement() {
        setTenantNo("MAIN");
    }
    /**
     * 修订状态（TDD §2.6 第 4–6 项，用户 2026-11-28 定；短值，不超过 10 个字符）：只在这里写字面量，别处引用本常量。
     * 草稿 → 在途 → 生效 / 资源拒绝 / 待释放；草稿另有过期、放弃，生效的旧版被新版取代。
     */
    public static final String DRAFT = "DRAFT";
    public static final String APPLYING = "APPLYING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String RELEASING = "RELEASING";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String EXPIRED = "EXPIRED";
    public static final String DISCARDED = "DISCARDED";

    private String entityNo;
    private String appointmentNo;
    private Long arrangementRevision;
    private String previewNo;
    private String holdNo;
    private String snapshot;
    private String storeNo;
    private String status;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String timezone;
    private String customerRef;
    private String guestRef;
    private String policyVersionNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private LocalDateTime expiresAt;
    /** 草稿基于的预约单版本（{@code ord_appointment.version}），新约为 0。 */
    private Long basedOnRevision;
    /** 这次变更做什么：CREATE、CHANGE、RECEPTION_CHANGE、CANCEL、NO_SHOW。 */
    private String action;
    private String frozenPlan;
    private String planHash;
    private String commandNo;
    private String commandHash;
    private String decisionNo;
    private LocalDateTime permittedAt;
    private LocalDateTime validUntil;
    private String rejectCode;
    private String resultHash;
    private LocalDateTime nextRetryAt;
}
