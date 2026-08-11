package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.spi.platform.MasterDataPort;
import org.springframework.stereotype.Component;

/**
 * {@link MasterDataPort} 实现。
 *
 * <p><b>薄薄一层，但不能省。</b>让 {@code MasterDataServiceImpl} 直接实现 Port 的话，
 * 本域内部方法与跨域契约会混在一个类里 —— 改本域逻辑时不知不觉改掉了别的域看到的行为，
 * 而两拨受众看到的能力范围也不一样（platform 自己要 {@code snapshot()}，
 * 别的域只该看到几个判断）。架构守卫拦的就是这个。
 */
@Component
public class MasterDataPortImpl implements MasterDataPort {

    private final MasterDataService masterDataService;
    private final ai.neargo.shop.platform.RegionService regionService;

    public MasterDataPortImpl(MasterDataService masterDataService,
                              ai.neargo.shop.platform.RegionService regionService) {
        this.masterDataService = masterDataService;
        this.regionService = regionService;
    }

    @Override
    public String canonicalSubject(String anySubject) {
        return masterDataService.canonicalSubject(anySubject);
    }

    @Override
    public boolean industryGated(String subjectType) {
        return masterDataService.industryGated(subjectType);
    }

    @Override
    public String settleAccountType(String subjectType) {
        return masterDataService.settleAccountType(subjectType);
    }

    @Override
    public void assertServiceScopeAllowed(String scope) {
        masterDataService.assertServiceScopeAllowed(scope);
    }

    @Override
    public String regionPathName(String regionCode) {
        var path = regionService.path(regionCode);
        return path.isEmpty() ? regionCode
                : path.stream().map(ai.neargo.shop.platform.RegionService.RegionVO::name)
                        .collect(java.util.stream.Collectors.joining(" / "));
    }

    @Override
    public String channelName(String payChannel) {
        return masterDataService.channelName(payChannel);
    }
}
