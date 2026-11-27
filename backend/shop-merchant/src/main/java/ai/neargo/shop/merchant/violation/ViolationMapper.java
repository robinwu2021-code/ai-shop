package ai.neargo.shop.merchant.violation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 违规与处置记录。只在本包内使用，商户域经 {@link ViolationLedger} 读写。 */
public interface ViolationMapper extends BaseMapper<MchViolation> {
}
