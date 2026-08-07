package ai.neargo.shop.platform.mapper;

import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.entity.SysChannelCategoryRule;
import ai.neargo.shop.platform.entity.SysStaff;
import ai.neargo.shop.platform.entity.UsrMerchantApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** platform 域的 Mapper 集合。 */
public final class PlatformMappers {

    private PlatformMappers() {
    }

    public interface StaffMapper extends BaseMapper<SysStaff> {
    }

    public interface AuditLogMapper extends BaseMapper<SysAuditLog> {
    }

    public interface MerchantApplyMapper extends BaseMapper<UsrMerchantApply> {
    }

    /** 端 × 品类 可售规则。审核被拒时运营当天改这张表，不用发版。 */
    public interface ChannelCategoryRuleMapper extends BaseMapper<SysChannelCategoryRule> {
    }

}
