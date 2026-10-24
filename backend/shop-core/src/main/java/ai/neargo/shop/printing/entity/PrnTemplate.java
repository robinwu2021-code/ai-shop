package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_template")
public class PrnTemplate extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String templateNo;
    private String templateCode;
    private Integer revision;
    private String scene;
    private String content;
    private String status;
}
