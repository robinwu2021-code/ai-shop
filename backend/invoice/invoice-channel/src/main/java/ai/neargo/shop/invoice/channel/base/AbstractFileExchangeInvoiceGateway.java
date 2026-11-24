package ai.neargo.shop.invoice.channel.base;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态五 · 文件交换（台湾：财政部 Turnkey）。把报文写成文件交给对方程序；结果来自回执文件，不在本次调用里。
 *
 * <p>模板保证原子性：先写同目录 {@code .tmp} 再原子改名，对方扫到的一定是完整文件；
 * 文件名由厂商按「报文代号 + 业务号」定，重发覆盖同名文件，不会产生两份。送达 = 文件已落地。
 */
public abstract class AbstractFileExchangeInvoiceGateway extends AbstractInvoiceGateway {

    protected AbstractFileExchangeInvoiceGateway(ObjectMapper json) {
        super(json);
    }

    /** 报文 → 文件；不支持的报文抛 {@link UnsupportedMessageException}。 */
    protected abstract ExchangeFile render(GatewayRequest request, JsonNode message);

    @Override
    protected final GatewayReceipt upload(GatewayRequest request, JsonNode message) {
        var file = render(request, message);
        Path target = file.target();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, file.content(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return GatewayReceipt.accepted(target.toString());
    }

    public record ExchangeFile(Path target, String content) {}
}
