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

    public MasterDataPortImpl(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
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
    public String channelName(String payChannel) {
        return masterDataService.channelName(payChannel);
    }
}
