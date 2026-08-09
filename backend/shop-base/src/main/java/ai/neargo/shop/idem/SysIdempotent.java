package ai.neargo.shop.idem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 幂等记录。唯一索引在 {@code (idem_key, endpoint)} 上 ——
 * 幂等靠的是数据库的唯一约束，不是「先查再插」：并发两个请求同时查不到再同时插，
 * 是这类实现最常见的失效方式。
 */
@Data
@TableName("sys_idempotent")
public class SysIdempotent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String idemKey;

    /** 端点，如 {@code POST /mp/order}。同一个 key 在不同端点互不影响。 */
    private String endpoint;

    private String userNo;

    /** 首次成功的结果快照，重放时原样返回，保证「重复下单」拿到的是同一个订单号。 */
    private String resultJson;

    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
}
