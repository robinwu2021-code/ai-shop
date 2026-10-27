package ai.neargo.shop.pay.mapper.access;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 接入记录的 mapper（db/pay V5 · TDD-支付服务-I5接入层 S2） */
public final class AccessMappers {

    private AccessMappers() {
    }

    public interface PayApiRequestLogMapper extends BaseMapper<PayApiRequestLog> {
    }
}
