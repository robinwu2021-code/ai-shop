package ai.neargo.shop.logistics.entity;

import ai.neargo.shop.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 运单（V132 建为 ful_shipment，V387 改名 lgs_waybill 并加列）。字段语义见 TDD-物流模块 §2.2。
 *
 * <p>⚠️ 更新一律用<b>只带 id 与要改字段的补丁实体</b>：{@link BaseEntity} 带 {@code @Version}，
 * 整行实体回写时版本对不上会静默改 0 行 —— 过渡期旧轮询也在写这张表。
 */
@Getter
@Setter
@TableName("lgs_waybill")
public class LgsWaybill extends BaseEntity {

    public static final String BIZ_SUB_ORDER = "SUB_ORDER";

    public static final String PROFILE_WX = "WX";
    public static final String PROFILE_SELF = "SELF";

    public static final String SUB_PENDING = "PENDING";
    public static final String SUB_DONE = "DONE";
    public static final String SUB_FATAL = "FATAL";
    public static final String SUB_ENDED = "ENDED";
    public static final String SUB_NA = "NA";

    public static final String BIND_NA = "NA";
    public static final String BIND_WAITING = "WAITING";
    public static final String BIND_DONE = "DONE";
    public static final String BIND_FATAL = "FATAL";

    private String shipmentNo;
    private String bizType;
    private String bizRef;
    private String entityNo;
    private String storeNo;
    private String carrier;
    private String waybillNo;
    private String status;
    private String profile;
    private String receiver;
    private String region;
    private String receiverPhoneEnc;
    private String receiverPhoneLast4;
    private String wxTransId;
    private String wxOpenid;
    private String wxOutTradeNo;
    private String goodsBrief;
    private Long pickedUpAt;
    private Long signedAt;
    private Integer atLocker;
    private String carrierCorrectedFrom;
    private Long lastEventAt;
    private String subState;
    private String subChannel;
    private String subRef;
    private Integer subAttempts;
    private String subError;
    private String kd100SubMonth;
    private Integer kd100SubCount;
    private Long wxUploadedAt;
    private String bindState;
    private String bindError;
    private String displayToken;
    private Long wxStatusCheckedAt;

    /** 补丁实体：只带 id，调用方再 set 要改的字段 */
    public static LgsWaybill patch(Long id) {
        LgsWaybill w = new LgsWaybill();
        w.setId(id);
        return w;
    }
}
