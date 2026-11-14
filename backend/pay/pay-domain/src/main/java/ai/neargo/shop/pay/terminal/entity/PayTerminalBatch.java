package ai.neargo.shop.pay.terminal.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_terminal_batch（db/pay V16 · pay-guard 开发计划 M6.1）：终端日结：终端上报与服务端汇总比对。
 *
 * <p>字段注释取自表结构真源 pay-service-database.json。
 */
@Getter
@Setter
@TableName("pay_terminal_batch")
public class PayTerminalBatch extends PayBaseEntity {

    /** 终端 pay_terminal.id */
    private Long payTerminalId;

    /** 营业日 */
    private LocalDate bizDate;

    /** 币种 */
    private String currency;

    /** 终端上报笔数 */
    private Integer reportedCount;

    /** 终端上报金额 */
    private Long reportedMinor;

    /** 撤销笔数 */
    private Integer reportedVoidCount;

    /** 撤销金额 */
    private Long reportedVoidMinor;

    /** 服务端汇总笔数 */
    private Integer serverCount;

    /** 服务端汇总金额 */
    private Long serverMinor;

    /** REPORTED / MATCHED / MISMATCHED */
    private String status;

    /** 日结收银员（审计字段） */
    private String operatorNo;
}
