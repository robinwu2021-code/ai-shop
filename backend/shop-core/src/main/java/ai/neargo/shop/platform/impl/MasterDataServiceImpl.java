package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ServiceScopes;
import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.platform.ServiceScopeService;
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
    /** 经营范围的启用白名单归它管 —— 一期开哪几档是运营的决定，不该发版 */
    private final ServiceScopeService serviceScopeService;

    public MasterDataServiceImpl(IndustryMapper industryMapper, MerchantSubjectMapper subjectMapper,
                                 PayChannelMapper channelMapper, ObjectMapper json,
                                 ServiceScopeService serviceScopeService) {
        this.industryMapper = industryMapper;
        this.subjectMapper = subjectMapper;
        this.channelMapper = channelMapper;
        this.json = json;
        this.serviceScopeService = serviceScopeService;
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
                        readList(c.getPayMethods()))).toList(),
                /*
                 * 按 ServiceScopeServiceImpl.ORDER 的顺序输出启用的那几档 ——
                 * 顺序是产品定义（按履约半径从小到大，与 ADR-009 的叙述一致），
                 * 不该取决于运营点开关的先后。用 enabledScopes() 的集合序输出的话，
                 * 端上的档位会随后台操作历史变来变去。
                 */
                enabledScopesInOrder());
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

    private List<String> enabledScopesInOrder() {
        var enabled = serviceScopeService.enabledScopes();
        return ServiceScopeServiceImpl.ORDER.stream().filter(enabled::contains).toList();
    }

    @Override
    public void assertServiceScopeAllowed(String scope) {
        if (scope == null || scope.isBlank()) {
            // 空 = 调用方不改这个字段。默认值由各调用方自己定，不在这里替他决定
            return;
        }
        // 第一层：值域。代码的事实，运营改不了
        if (!ServiceScopes.ALL.contains(scope)) {
            throw BizException.of(ErrorCode.SERVICE_SCOPE_NOT_ALLOWED);
        }
        /*
         * 第二层：这一期开放哪几档。运营的决定，读的是 ServiceScopeService 那一份 ——
         * 在这里自己再解析一遍 sys_setting 的话，两处的键名与兜底迟早分岔，
         * 而分岔的症状是「运营在后台开了，商家还是选不了」，两边各自看起来都对。
         */
        if (!serviceScopeService.enabledScopes().contains(scope)) {
            throw BizException.of(ErrorCode.SERVICE_SCOPE_NOT_ALLOWED);
        }
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
    public boolean supportsSubsidy(String payChannel) {
        if (payChannel == null || payChannel.isBlank()) {
            return false;
        }
        var row = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectOne(Wrappers.<ai.neargo.shop.platform.entity.SysPayChannel>lambdaQuery()
                        .eq(ai.neargo.shop.platform.entity.SysPayChannel::getPayChannel, payChannel)
                        .last("LIMIT 1")));
        // 查不到按 false：这个字段建出来就是为了拦截，而「查不到 = 支持」
        // 会让不具备补差能力的通道静默开出积分抵扣 —— 症状是商家账上少一笔钱
        return row != null && Boolean.TRUE.equals(row.getSupportsSubsidy());
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
