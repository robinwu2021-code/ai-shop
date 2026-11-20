package ai.neargo.shop.portal.common;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 复用证照图片缩放与 EXIF 清理；PDF 仅在内存校验，不创建公开文件。 */
public final class CertDocumentReader {
    private static final long MAX_BYTES = 12L * 1024 * 1024;
    private static final int MAX_EDGE = 1600;
    private CertDocumentReader() { }

    public record Input(byte[] bytes, String contentType) { }

    public static Input read(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw BizException.of(ErrorCode.BAD_REQUEST);
        return read(file.getBytes(), file.getContentType());
    }

    /** 已在私有存储里的文件（如 near-pay 回读的实体附件）走同一套校验与重编码。 */
    public static Input read(byte[] bytes, String contentType) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw BizException.of(ErrorCode.BAD_REQUEST);
        boolean pdf = bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
        if (pdf || "application/pdf".equalsIgnoreCase(contentType)) {
            try (var document = Loader.loadPDF(bytes)) {
                if (document.isEncrypted() || document.getNumberOfPages() == 0) throw BizException.of(ErrorCode.BAD_REQUEST);
            } catch (IOException e) { throw BizException.of(ErrorCode.BAD_REQUEST); }
            return new Input(bytes, "application/pdf");
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null); }
        finally { graphics.dispose(); }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return new Input(out.toByteArray(), "image/jpeg");
    }
}
