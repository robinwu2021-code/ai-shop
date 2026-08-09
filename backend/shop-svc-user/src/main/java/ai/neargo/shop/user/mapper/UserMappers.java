package ai.neargo.shop.user.mapper;

import ai.neargo.shop.user.community.entity.CmtCommunity;
import ai.neargo.shop.user.community.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * user 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper 只做单表 CRUD 与条件组合，跨表聚合放 Service —— 一旦 Mapper 里出现业务分支，
 * 数据域拦截器与状态机就会被绕过。
 *
 * <p>商家的六个 Mapper 已迁往 {@code MerchantMappers}（模块优化实施步骤 S3）。
 */
public final class UserMappers {

    private UserMappers() {
    }

    public interface UserMapper extends BaseMapper<UsrAccount> {
    }

    public interface CommunityMapper extends BaseMapper<CmtCommunity> {
    }

    public interface PickupPointMapper extends BaseMapper<CmtPickupPoint> {
    }

    public interface AddressMapper extends BaseMapper<UsrAddress> {
    }

    public interface StoreFavoriteMapper extends BaseMapper<UsrStoreFavorite> {
    }
}
