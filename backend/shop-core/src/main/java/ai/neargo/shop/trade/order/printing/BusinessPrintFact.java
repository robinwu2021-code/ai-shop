package ai.neargo.shop.trade.order.printing;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
/** Read-only receipt attachment; delivery remains owned by PrintService. */
@Getter @Setter @TableName("biz_print_fact")
public class BusinessPrintFact extends OrderPrintFact {
    private String sourceType;
    private String sourceNo;
    private String ownerEventHash;
}
