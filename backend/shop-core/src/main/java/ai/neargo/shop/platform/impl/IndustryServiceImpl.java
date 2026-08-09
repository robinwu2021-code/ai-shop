package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.IndustryService;
import ai.neargo.shop.platform.dto.IndustryVO;
import ai.neargo.shop.platform.entity.SysIndustry;
import ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 行业主数据实现。 */
@Service
public class IndustryServiceImpl implements IndustryService {

    private static final String WECHAT = "WECHAT";
    private static final String ALIPAY = "ALIPAY";

    private final IndustryMapper mapper;
    private final MerchantQueryPort merchantQuery;

    public IndustryServiceImpl(IndustryMapper mapper, MerchantQueryPort merchantQuery) {
        this.mapper = mapper;
        this.merchantQuery = merchantQuery;
    }

    @Override
    public List<IndustryVO> list() {
        return mapper.selectList(Wrappers.<SysIndustry>lambdaQuery()
                .orderByAsc(SysIndustry::getSort)).stream().map(this::toVO).toList();
    }

    @Override
    public List<IndustryVO> enabled() {
        return mapper.selectList(Wrappers.<SysIndustry>lambdaQuery()
                        .eq(SysIndustry::getEnabled, true)
                        .orderByAsc(SysIndustry::getSort))
                .stream().map(this::toVO).toList();
    }

    @Override
    public boolean microAllowed(String industry, String payChannel) {
        SysIndustry row = find(industry);
        // **查不到一律不允许**。返回 true 的话，商家会填完全部资料才被通道拒绝
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            return false;
        }
        return switch (payChannel) {
            case WECHAT -> Boolean.TRUE.equals(row.getWechatMicroAllowed());
            case ALIPAY -> Boolean.TRUE.equals(row.getAlipayMicroAllowed());
            // 未知通道同样不允许 —— 新通道接进来时必须显式配，不能默认继承
            default -> false;
        };
    }

    @Override
    @Transactional
    public IndustryVO setMicroAllowed(String industry, String payChannel, boolean allowed, String remark) {
        SysIndustry row = require(industry);
        switch (payChannel) {
            case WECHAT -> row.setWechatMicroAllowed(allowed);
            case ALIPAY -> row.setAlipayMicroAllowed(allowed);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "未知支付通道：" + payChannel);
        }
        // 准入结论必须写清理由 —— 半年后没人记得当初为什么放开或收紧
        if (remark != null && !remark.isBlank()) {
            row.setRemark(remark);
        }
        mapper.updateById(row);
        return toVO(row);
    }

    @Override
    @Transactional
    public IndustryVO setEnabled(String industry, boolean enabled) {
        SysIndustry row = require(industry);
        // 停用只影响**新入驻**：存量商家的 industry 不动，他们的店照常经营。
        // 停用不是撤销资质 —— 那是另一件事，走商家封禁
        row.setEnabled(enabled);
        mapper.updateById(row);
        return toVO(row);
    }

    @Override
    @Transactional
    public IndustryVO setPointsForced(String industry, boolean forced) {
        SysIndustry row = require(industry);
        // 只改**默认值**，不回写存量商家的 mch_entity.points_forced ——
        // 强制开积分要提前 30 天通知 + 费率补偿 + 申诉通道（ADR-006），
        // 不能靠改一个开关就对所有存量商家生效
        row.setPointsForced(forced);
        mapper.updateById(row);
        return toVO(row);
    }

    // ---------------------------------------------------------------- 内部

    private SysIndustry find(String industry) {
        return mapper.selectOne(Wrappers.<SysIndustry>lambdaQuery()
                .eq(SysIndustry::getIndustry, industry).last("LIMIT 1"));
    }

    private SysIndustry require(String industry) {
        SysIndustry row = find(industry);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "行业不存在：" + industry);
        }
        return row;
    }

    private IndustryVO toVO(SysIndustry e) {
        return new IndustryVO(
                e.getIndustry(), e.getName(),
                e.getSort() == null ? 0 : e.getSort(),
                Boolean.TRUE.equals(e.getEnabled()),
                Boolean.TRUE.equals(e.getWechatMicroAllowed()),
                Boolean.TRUE.equals(e.getAlipayMicroAllowed()),
                Boolean.TRUE.equals(e.getPointsForced()),
                e.getRemark(),
                merchantQuery.countByIndustry(e.getIndustry()));
    }
}
