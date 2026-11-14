package ai.neargo.shop.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Single committed consumption notification; SENDING is never automatically replayed. */
@Data
@TableName("notify_stored_value_task")
public class StoredValueNotifyTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String usageNo;
    private String recipient;
    private String scene;
    private String eventNo;
    private String storeNo;
    private String documentNo;
    private String valuesJson;
    private String openId;
    private String templateId;
    private String status;
    private String reasonCode;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime updatedAt;
}
