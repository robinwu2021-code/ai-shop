package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.platform.dto.MasterDataVO;
import ai.neargo.shop.platform.entity.SysIndustry;
import ai.neargo.shop.platform.entity.SysLegalForm;
import ai.neargo.shop.platform.entity.SysPayChannel;
import ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantSubjectMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.PayChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** {@link MasterDataService} 实现。跨域调用见 {@code platform.port.MasterDataPortImpl}。 */
@Service
public class MasterDataServiceImpl implements MasterDataService {

    private final IndustryMapper industryMapper;
    private final MerchantSubjectMapper subjectMapper;
    private final PayChannelMapper channelMapper;
    private final ObjectMapper json;

    public MasterDataServiceImpl(IndustryMapper industryMapper, MerchantSubjectMapper subjectMapper,
                                 PayChannelMapper channelMapper, ObjectMapper json) {
        this.industryMapper = industryMapper;
        this.subjectMapper = subjectMapper;
        this.channelMapper = channelMapper;
        this.json = json;
    }

    @Override
    public MasterDataVO snapshot() {
        List<SysIndustry> industries = DataScopeContext.executeWithoutScope(() ->
                industryMapper.selectList(Wrappers.<SysIndustry>lambdaQuery()
                        .eq(SysIndustry::getEnabled, true).orderByAsc(SysIndustry::getSort)));
        List<SysLegalForm> subjects = enabledSubjectRows();
        List<SysPayChannel> channels = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<SysPayChannel>lambdaQuery()
                        .eq(SysPayChannel::getEnabled, true)));

        return new MasterDataVO(
                industries.stream().map(i -> new MasterDataVO.Industry(
                        i.getIndustry(), i.getName(),
                        // 任一通道允许即算允许 —— 商家走哪个通道由进件时决定。
                        // 端上据此禁用「小微」选项，而不是让人填完再被拒
                        Boolean.TRUE.equals(i.getWechatMicroAllowed())
                                || Boolean.TRUE.equals(i.getAlipayMicroAllowed()))).toList(),
                subjects.stream().map(s -> new MasterDataVO.Subject(
                        s.getLegalForm(), s.getName(),
                        Boolean.TRUE.equals(s.getNeedLicense()),
                        Boolean.TRUE.equals(s.getIndustryGated()),
                        s.getSettleAccountType())).toList(),
                channels.stream().map(c -> new MasterDataVO.Channel(
                        c.getPayChannel(), c.getName(), Boolean.TRUE.equals(c.getEnabled()),
                        readList(c.getPayMethods()))).toList());
    }

    @Override
    public boolean needLicense(String subjectType) {
        SysLegalForm row = row(subjectType);
        // 查不到按「要执照」：少要一次的代价是放进一个不该通过的商家，多要一次只是麻烦
        return row == null || Boolean.TRUE.equals(row.getNeedLicense());
    }

    @Override
    public boolean industryGated(String subjectType) {
        SysLegalForm row = row(subjectType);
        // 查不到按 false：未知不该凭空加一条限制
        return row != null && Boolean.TRUE.equals(row.getIndustryGated());
    }

    @Override
    public String canonicalSubject(String anySubject) {
        if (anySubject == null || anySubject.isBlank()) {
            return null;
        }
        SysLegalForm byCode = row(anySubject);
        if (byCode != null) {
            return byCode.getLegalForm();
        }
        SysLegalForm byLegacy = DataScopeContext.executeWithoutScope(() ->
                subjectMapper.selectOne(Wrappers.<SysLegalForm>lambdaQuery()
                        .eq(SysLegalForm::getLegacySubject, anySubject).last("limit 1")));
        return byLegacy == null ? null : byLegacy.getLegalForm();
    }

    @Override
    public List<String> enabledSubjects() {
        return enabledSubjectRows().stream().map(SysLegalForm::getLegalForm).toList();
    }

    private List<SysLegalForm> enabledSubjectRows() {
        return DataScopeContext.executeWithoutScope(() ->
                subjectMapper.selectList(Wrappers.<SysLegalForm>lambdaQuery()
                        .eq(SysLegalForm::getEnabled, true)
                        .orderByAsc(SysLegalForm::getSort)));
    }

    @Override
    public String settleAccountType(String subjectType) {
        SysLegalForm row = row(subjectType);
        return row == null ? null : row.getSettleAccountType();
    }

    @Override
    public String channelName(String payChannel) {
        if (payChannel == null || payChannel.isBlank()) {
            return payChannel;
        }
        var row = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectOne(Wrappers.<ai.neargo.shop.platform.entity.SysPayChannel>lambdaQuery()
                        .eq(ai.neargo.shop.platform.entity.SysPayChannel::getPayChannel, payChannel)
                        .last("limit 1")));
        return row == null || row.getName() == null ? payChannel : row.getName();
    }

    private SysLegalForm row(String subjectType) {
        if (subjectType == null || subjectType.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                subjectMapper.selectOne(Wrappers.<SysLegalForm>lambdaQuery()
                        .eq(SysLegalForm::getLegalForm, subjectType).last("limit 1")));
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
