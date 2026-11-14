package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 在库周期：入库开、出库关；回仓永远开新的（TDD §2.6）。
 *
 * <p>库里还有一列生成列 {@code open_serial_id}（只在 OPEN 时有值、带唯一索引）——
 * <b>刻意不映射</b>：它由数据库算，实体里有这个字段就会被写进 INSERT 而报错。
 */
@Getter
@Setter
@TableName("inv_stock_episode")
public class InvStockEpisode extends InvMutableEntity {
    private String episodeId;
    private String ownerId;
    private String serialId;
    private String itemId;
    private String locationId;
    /** OPEN / CLOSED */
    private String status;
    /** 这一轮入库所凭的放行凭证 */
    private String gateRef;
    private String openDocNo;
    private String closeDocNo;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
}
