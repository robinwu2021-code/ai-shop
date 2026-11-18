package ai.neargo.shop.gateway.core.function;

import ai.neargo.shop.auth.Realm;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 功能点登记表：把已装配模块的声明聚合起来（TDD-网关层与统一权限 §2.6）。
 *
 * <p>回答三个问题：这套部署里有哪些模块；一个权限码是哪个模块提供的；一个权限码在这套部署里存不存在。
 */
@Component
public class FunctionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FunctionRegistry.class);

    /** 端 + 码 → 模块编码。 */
    private final Map<String, String> moduleByPoint = new LinkedHashMap<>();
    private final TreeSet<String> modules = new TreeSet<>();

    public FunctionRegistry(List<FunctionModule> declared) {
        for (FunctionModule module : declared) {
            modules.add(module.code());
            for (FunctionPoint point : module.points()) {
                String previous = moduleByPoint.put(key(point.realm(), point.code()), module.code());
                if (previous != null && !previous.equals(module.code())) {
                    throw new IllegalStateException("功能点 " + point.code() + " 被两个模块同时声明："
                            + previous + "、" + module.code());
                }
            }
        }
        log.info("功能点登记表：{} 个模块 {}，{} 个功能点", modules.size(), modules, moduleByPoint.size());
    }

    /** 已装配的模块编码。 */
    public TreeSet<String> modules() {
        return new TreeSet<>(modules);
    }

    /** 这个权限码由哪个已装配的模块提供；没有模块提供则为空。 */
    public Optional<String> moduleOf(Realm realm, String code) {
        return Optional.ofNullable(moduleByPoint.get(key(realm, code)));
    }

    public boolean registered(Realm realm, String code) {
        return moduleByPoint.containsKey(key(realm, code));
    }

    /** 一端已登记的全部权限码。 */
    public TreeSet<String> codes(Realm realm) {
        TreeSet<String> out = new TreeSet<>();
        String prefix = realm.name() + " ";
        moduleByPoint.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(k -> out.add(k.substring(prefix.length())));
        return out;
    }

    private static String key(Realm realm, String code) {
        return realm.name() + " " + code;
    }
}
