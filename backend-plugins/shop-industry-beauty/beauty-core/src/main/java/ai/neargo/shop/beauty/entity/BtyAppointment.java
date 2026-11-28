package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 预约单；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("ord_appointment")
public class BtyAppointment extends BaseEntity {
    /** 存在门店交易库 {@code ord_appointment}（TDD-美业域迁移新库 §2.6 第 1 项）；预约类型固定 SERVICE（餐饮订位是 TABLE）。共用表的 {@code tenant_no} 没有库默认值，平台审计也不填它：在这里带上。 */
    public BtyAppointment() {
        setTenantNo("MAIN");
    }
    /** 预约类型：美业服务预约（同表的餐饮订位是 TABLE）。 */
    private String appointmentKind = "SERVICE";
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 预约号。 */
    private String appointmentNo;
    /** 门店。 */
    private String storeNo;
    /** 已识别客户引用。 */
    private String customerRef;
    /** 受控临客资料引用。 */
    private String guestRef;
    /** ONLINE或PHONE或WALK_IN或STAFF。 */
    private String source;
    /** 预约状态。 */
    private String status;
    /** 服务开始。 */
    private LocalDateTime startAt;
    /** 服务结束。 */
    private LocalDateTime endAt;
    /** 业务时区。 */
    private String timezone;
    /** 资源凭证。 */
    private String holdNo;
    /** 受理规则。 */
    private String policyVersionNo;
    /** 签到。 */
    private LocalDateTime checkedInAt;
    /** 已开的工单（V9.1）：预约侧的护栏看这一列，不联查行业库的工单表。 */
    private String workOrderNo;
    /** 取消或爽约的原因（最长 500）：受理取消、爽约时写，释放资源被拒时清掉（TDD §2.6 第 12 项，原来只在操作日志里）。 */
    private String cancelReason;
    /** 取消或爽约的凭证引用（登记爽约时的证明材料）；没有为空。 */
    private String cancelEvidenceRef;
}
