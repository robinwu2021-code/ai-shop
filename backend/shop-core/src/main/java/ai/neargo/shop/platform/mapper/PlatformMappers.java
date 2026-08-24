package ai.neargo.shop.platform.mapper;

import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.entity.SysChannelCategoryRule;
import ai.neargo.shop.platform.entity.SysIndustry;
import ai.neargo.shop.platform.entity.SysPayChannel;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.entity.MchEntityApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** platform 域的 Mapper 集合。 */
public final class PlatformMappers {

    private PlatformMappers() {
    }

    public interface StaffMapper extends BaseMapper<SysOpsStaff> {
    }

    public interface AuditLogMapper extends BaseMapper<SysAuditLog> {
    }

    public interface MerchantApplyMapper extends BaseMapper<MchEntityApply> {
    }

    /** 端 × 品类 可售规则。审核被拒时运营当天改这张表，不用发版。 */
    public interface ChannelCategoryRuleMapper extends BaseMapper<SysChannelCategoryRule> {
    }

    public interface PayChannelMapper extends BaseMapper<SysPayChannel> {
    }

    public interface IndustryMapper extends BaseMapper<SysIndustry> {
    }

    /** 地图地点缓存（一片一行，结果存 JSON）。 */
    public interface GeoPoiCacheMapper
            extends BaseMapper<ai.neargo.shop.platform.entity.GeoPoiCache> {
    }

    /** 商家主体类型注册表。 */
    public interface MerchantSubjectMapper
            extends BaseMapper<ai.neargo.shop.platform.entity.SysLegalForm> {
    }


    /** 平台可调参数（评分权重、快速退款阈值…）。 */
    /** 行政区划四级（44703 行，由 V31 灌入）。按 parent_code 逐级查，不整棵树捞。 */
    public interface RegionMapper extends BaseMapper<ai.neargo.shop.platform.entity.SysRegion> {
    }

    public interface SettingMapper extends BaseMapper<ai.neargo.shop.platform.entity.SysSetting> {
    }
}
