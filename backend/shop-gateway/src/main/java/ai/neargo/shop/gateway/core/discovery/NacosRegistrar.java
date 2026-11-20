package ai.neargo.shop.gateway.core.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * 把本实例登记进 Nacos，停机时摘掉（ADR-062、TDD-网关层与统一权限 §2.24）。
 *
 * <p>应用就绪之后才登记：登记了就可能有人来调，那时入口必须已经能用。
 * 登记失败不影响启动 —— 每 30 秒重试，直到成功。登记的是临时实例，由客户端的长连接保活，进程没了 Nacos 自己会摘。
 */
public class NacosRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistrar.class);

    private final NamingService naming;
    private final String serviceName;
    private final String group;
    private final String ip;
    private final int port;
    private final Map<String, String> metadata;
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nacos-register");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean registered;
    private volatile boolean closed;

    public NacosRegistrar(NamingService naming, String serviceName, String group, String ip, int port,
                          Map<String, String> metadata) {
        this.naming = naming;
        this.serviceName = serviceName;
        this.group = group;
        this.ip = ip;
        this.port = port;
        this.metadata = new LinkedHashMap<>(metadata);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnReady() {
        attempt();
    }

    private void attempt() {
        if (closed || registered) {
            return;
        }
        try {
            Instance instance = new Instance();
            instance.setIp(ip);
            instance.setPort(port);
            instance.setEphemeral(true);
            instance.setMetadata(metadata);
            naming.registerInstance(serviceName, group, instance);
            registered = true;
            log.info("已登记到 Nacos：{}@{} {}:{} {}", serviceName, group, ip, port, metadata);
        } catch (NacosException | RuntimeException e) {
            log.warn("登记到 Nacos 失败，30 秒后重试（不影响本实例对外服务）：{}", e.getMessage());
            retry.schedule(this::attempt, 30, TimeUnit.SECONDS);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void deregisterOnClose() {
        closed = true;
        retry.shutdownNow();
        if (!registered) {
            return;
        }
        try {
            naming.deregisterInstance(serviceName, group, ip, port);
            log.info("已从 Nacos 摘除：{} {}:{}", serviceName, ip, port);
        } catch (NacosException | RuntimeException e) {
            log.warn("从 Nacos 摘除失败（临时实例，连接断开后 Nacos 会自己摘）：{}", e.getMessage());
        }
    }

    public boolean registered() {
        return registered;
    }
}
