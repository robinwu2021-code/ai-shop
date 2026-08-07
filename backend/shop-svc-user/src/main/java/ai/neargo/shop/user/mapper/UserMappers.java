package ai.neargo.shop.user.mapper;

import ai.neargo.shop.user.entity.CmtCommunity;
import ai.neargo.shop.user.entity.UsrMerchantPayment;
import ai.neargo.shop.user.entity.UsrMerchantCommunity;
import ai.neargo.shop.user.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.entity.UsrUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * user 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper 只做单表 CRUD 与条件组合，跨表聚合放 Service —— 一旦 Mapper 里出现业务分支，
 * 数据域拦截器与状态机就会被绕过。
 */
public final class UserMappers {

    private UserMappers() {
    }

    public interface UserMapper extends BaseMapper<UsrUser> {
    }

    public interface MerchantMapper extends BaseMapper<UsrMerchant> {
    }

    public interface CommunityMapper extends BaseMapper<CmtCommunity> {
    }

    public interface PickupPointMapper extends BaseMapper<CmtPickupPoint> {
    }

    public interface AddressMapper extends BaseMapper<UsrAddress> {
    }

    public interface StoreFavoriteMapper extends BaseMapper<UsrStoreFavorite> {
    }

    /** 商家覆盖的社区：C 端「本社区可见商家」的反查索引所在。 */
    public interface MerchantCommunityMapper extends BaseMapper<UsrMerchantCommunity> {
    }

    /** 商家支付进件：每通道一条。分账回调只带 sub_mchid，靠 idx_mp_sub_mchid 反查商家。 */
    public interface MerchantPaymentMapper extends BaseMapper<UsrMerchantPayment> {
    }


}
