package ai.neargo.shop.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 核销日志（append-only）。**失败也记**：
 * 「顾客说扫不了」这类纠纷，能查到的只有失败记录 —— 只记成功等于没记。
 */
@Getter
@Setter
@TableName("ful_verify_log")
public class FulVerifyLog {

    public static final String TYPE_SCAN = "SCAN";
    public static final String TYPE_BATCH = "BATCH";
    public static final String TYPE_ON_BEHALF = "ON_BEHALF";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String subOrderNo;
    private String pickupNo;
    private String verifyCode;
    private String verifyType;

    /** 操作人 userNo —— 代核销必须能追到人。 */
    private String operatorNo;

    /** SUCCESS 或失败原因。 */
    private String result;

    private Long at;
    private String tenantNo;
    private LocalDateTime createdAt;
}
