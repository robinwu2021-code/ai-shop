package ai.neargo.shop.pay.mapper.openapp;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.entity.PayApiNonce;
import ai.neargo.shop.pay.openapp.entity.PayAppGrant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 接入方的 mapper（db/pay V7） */
public final class OpenAppMappers {

    private OpenAppMappers() {
    }

    public interface PayAppMapper extends BaseMapper<PayApp> {
    }

    public interface PayAppGrantMapper extends BaseMapper<PayAppGrant> {
    }

    public interface PayApiNonceMapper extends BaseMapper<PayApiNonce> {
    }
}
