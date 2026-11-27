package ai.neargo.shop.platform.port;

import ai.neargo.shop.spi.dict.DictItem;
import ai.neargo.shop.spi.dict.DictType;
import ai.neargo.shop.spi.dict.DictionaryPort;
import ai.neargo.shop.spi.dict.DictionarySource;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link DictionaryPort} 的唯一实现：按类型汇集 {@link DictionarySource}（TDD-基础能力库对齐 §2.3 1b）。
 *
 * <p>同一类型两个来源直接启动失败：两个来源意味着同一种字典有两份数据，调用方读到哪份取决于装配顺序。
 */
@Component
public class DictionaryPortImpl implements DictionaryPort {

    private final Map<DictType, DictionarySource> sources = new EnumMap<>(DictType.class);

    public DictionaryPortImpl(List<DictionarySource> sources) {
        for (DictionarySource s : sources) {
            if (this.sources.putIfAbsent(s.type(), s) != null) {
                throw new IllegalStateException("字典类型 " + s.type() + " 有两个来源");
            }
        }
    }

    @Override
    public List<DictItem> items(DictType type) {
        DictionarySource source = sources.get(type);
        if (source == null) return List.of();
        return source.items();
    }
}
