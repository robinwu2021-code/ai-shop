package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.platform.entity.SysIndustry;
import ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper;
import ai.neargo.shop.spi.dict.DictItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 进件行业字典带上通道准入属性，按排序给出：主数据快照改读端口后，小微开关与顺序不变（TDD-基础能力库对齐 1b 续）。 */
class OnboardIndustryDictionarySourceTest {

    private static SysIndustry row(String code, int sort, boolean enabled, Boolean wechatMicro, Boolean alipayMicro) {
        var r = new SysIndustry();
        r.setTenantNo("MAIN");
        r.setIndustry(code);
        r.setName(code + "-名");
        r.setSort(sort);
        r.setEnabled(enabled);
        r.setWechatMicroAllowed(wechatMicro);
        r.setAlipayMicroAllowed(alipayMicro);
        return r;
    }

    @Test
    void itemsCarryMicroAdmissionAndComeInSortOrder() {
        IndustryMapper mapper = mock(IndustryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                row("CATERING", 30, true, false, true),
                row("RETAIL", 10, true, true, null),
                row("LIFE_SERVICE", 20, false, null, false)));

        List<DictItem> items = new OnboardIndustryDictionarySource(mapper).items();

        assertThat(items).extracting(DictItem::code).containsExactly("RETAIL", "LIFE_SERVICE", "CATERING");
        assertThat(items).extracting(i -> i.attr(DictItem.ATTR_MICRO_ALLOWED)).containsExactly("true", "false", "true");
        assertThat(items.get(1).enabled()).isFalse();
    }
}
