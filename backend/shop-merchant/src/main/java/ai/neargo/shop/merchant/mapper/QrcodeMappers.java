package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.merchant.entity.MchStoreQrcodePrint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 店铺码相关的 Mapper。
 *
 * <p>单开一个文件而不是并进 {@code MerchantMappers}：那个文件是本域的公共入口，
 * 二十几个会话都可能在改它，往里加行是无谓的冲突面。
 */
public final class QrcodeMappers {

    private QrcodeMappers() {
    }

    public interface StoreQrcodePrintMapper extends BaseMapper<MchStoreQrcodePrint> {
    }
}
