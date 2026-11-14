package ai.neargo.shop.pay.mapper.terminal;

import ai.neargo.shop.pay.terminal.entity.PayManagerCode;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import ai.neargo.shop.pay.terminal.entity.PayTerminalBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** POS 终端的 mapper（db/pay V16） */
public final class TerminalMappers {

    private TerminalMappers() {
    }

    public interface PayTerminalMapper extends BaseMapper<PayTerminal> {
    }

    public interface PayManagerCodeMapper extends BaseMapper<PayManagerCode> {
    }

    public interface PayTerminalBatchMapper extends BaseMapper<PayTerminalBatch> {
    }
}
