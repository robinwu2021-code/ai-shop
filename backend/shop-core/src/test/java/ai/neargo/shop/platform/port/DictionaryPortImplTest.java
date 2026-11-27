package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.dict.DictItem;
import ai.neargo.shop.spi.dict.DictType;
import ai.neargo.shop.spi.dict.DictionaryPort.IndustryRow;
import ai.neargo.shop.spi.dict.DictionarySource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TDD-基础能力库对齐 §2.3 1b：一个字典端口，按类型汇集来源。 */
class DictionaryPortImplTest {

    private static DictionarySource source(DictType type, DictItem... items) {
        return new DictionarySource() {
            @Override
            public DictType type() {
                return type;
            }

            @Override
            public List<DictItem> items() {
                return List.of(items);
            }
        };
    }

    private static DictItem item(String tenant, DictType type, String code, String name, int sort) {
        return new DictItem(tenant, type, code, null, null, name, Map.of(), sort, true, Map.of());
    }

    @Test
    void itemsComeFromTheSourceOfThatType() {
        var port = new DictionaryPortImpl(List.of(
                source(DictType.PAY_CHANNEL, item("MAIN", DictType.PAY_CHANNEL, "WECHAT", "微信支付", 10),
                        item("MAIN", DictType.PAY_CHANNEL, "ALIPAY", "支付宝", 20)),
                source(DictType.ONBOARD_INDUSTRY, item("MAIN", DictType.ONBOARD_INDUSTRY, "RETAIL", "线下零售", 20))));

        assertThat(port.items(DictType.PAY_CHANNEL)).extracting(DictItem::code).containsExactly("WECHAT", "ALIPAY");
        assertThat(port.nameOf(DictType.PAY_CHANNEL, "WECHAT")).isEqualTo("微信支付");
        assertThat(port.items(DictType.BUSINESS_TYPE)).isEmpty();
    }

    @Test
    void unknownCodeShowsTheCodeItself() {
        var port = new DictionaryPortImpl(List.of());

        assertThat(port.nameOf(DictType.PAY_CHANNEL, "WECHAT")).isEqualTo("WECHAT");
    }

    @Test
    void twoSourcesForOneTypeFailAtStartup() {
        var a = source(DictType.PAY_CHANNEL);
        var b = source(DictType.PAY_CHANNEL);

        assertThatThrownBy(() -> new DictionaryPortImpl(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAY_CHANNEL");
    }

    @Test
    void knownIsScopedToTheTenantAndIncludesDisabledItems() {
        var disabled = new DictItem("MAIN", DictType.ONBOARD_INDUSTRY, "ONLINE", null, null, "线上", Map.of(), 60,
                false, Map.of());
        var port = new DictionaryPortImpl(List.of(source(DictType.ONBOARD_INDUSTRY, disabled)));

        assertThat(port.known("MAIN", DictType.ONBOARD_INDUSTRY, "ONLINE")).isTrue();
        assertThat(port.known("OTHER", DictType.ONBOARD_INDUSTRY, "ONLINE")).isFalse();
        assertThat(port.known("MAIN", DictType.ONBOARD_INDUSTRY, "CATERING")).isFalse();
    }

    @Test
    void storeIndustryRowsRoundTripThroughDictionaryItems() {
        var top = new IndustryRow("FOOD", null, "FOOD", "餐饮", "餐飲", "Food & beverage", 10, true);
        var sub = new IndustryRow("FOOD", "FOOD_CAFE", "FOOD", "咖啡厅", "咖啡廳", "Cafe", 60, false);
        var port = new DictionaryPortImpl(List.of(source(DictType.BUSINESS_TYPE, top.toItem("MAIN"),
                sub.toItem("MAIN"))));

        assertThat(port.storeIndustries()).containsExactly(top, sub);
        assertThat(port.item(DictType.BUSINESS_TYPE, "FOOD_CAFE").orElseThrow().parentCode()).isEqualTo("FOOD");
        assertThat(sub.displayName(Locale.TRADITIONAL_CHINESE)).isEqualTo("咖啡廳");
        assertThat(sub.displayName(Locale.ENGLISH)).isEqualTo("Cafe");
    }
}
