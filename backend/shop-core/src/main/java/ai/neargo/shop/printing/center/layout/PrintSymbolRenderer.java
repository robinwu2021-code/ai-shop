package ai.neargo.shop.printing.center.layout;

import ai.neargo.shop.print.api.PrintDrawing;
import ai.neargo.shop.print.api.PrintFailure;
import ai.neargo.shop.print.api.PrintHashes;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 把版面行里的条码符号画成位图 {@link PrintDrawing.ImageRun}（发票证明联的一维码与左右 QR，TDD-发票模块 §2.3）。
 *
 * <p>位图按「每个模块一像素」生成，由输出端按毫米尺寸缩放：PDF / 桌面 agent 按矢量尺寸放大，不糊；
 * 只收文本的厂商通道（飞鹅）遇到图片会按既有逻辑拒绝，不会把条码静默丢掉。
 */
final class PrintSymbolRenderer {

    static final BigDecimal BARCODE_HEIGHT_MM = BigDecimal.valueOf(6);
    static final BigDecimal QR_GAP_MM = BigDecimal.valueOf(4);

    private PrintSymbolRenderer() {
    }

    /** @return 本行图片与占用高度（毫米） */
    record Placed(List<PrintDrawing> drawings, BigDecimal heightMm) {}

    static Placed code39(String value, BigDecimal xMm, BigDecimal yMm, BigDecimal widthMm) {
        if (!value.matches("[0-9A-Z .$/+%-]{1,43}")) throw new PrintFailure("print.symbolValueRejected");
        var matrix = new Code39Writer().encode(value, BarcodeFormat.CODE_39, 0, 1);
        return new Placed(List.of(image(matrix, xMm, yMm, widthMm, BARCODE_HEIGHT_MM)), BARCODE_HEIGHT_MM);
    }

    /** 左右两个等宽 QR 并排；右码为空串时只画左码。 */
    static Placed qrPair(String left, String right, BigDecimal xMm, BigDecimal yMm, BigDecimal widthMm) {
        BigDecimal side = widthMm.subtract(QR_GAP_MM).divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN);
        var first = image(qr(left), xMm, yMm, side, side);
        if (right == null || right.isEmpty()) return new Placed(List.of(first), side);
        var second = image(qr(right), xMm.add(side).add(QR_GAP_MM), yMm, side, side);
        return new Placed(List.of(first, second), side);
    }

    private static BitMatrix qr(String value) {
        try {
            return new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 0,
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L));
        } catch (WriterException e) {
            throw new PrintFailure("print.symbolValueRejected");
        }
    }

    private static PrintDrawing.ImageRun image(BitMatrix m, BigDecimal x, BigDecimal y, BigDecimal w, BigDecimal h) {
        var img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int i = 0; i < m.getWidth(); i++)
            for (int j = 0; j < m.getHeight(); j++) img.setRGB(i, j, m.get(i, j) ? 0x000000 : 0xFFFFFF);
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] png = out.toByteArray();
        return new PrintDrawing.ImageRun(png, PrintHashes.sha256(png), x, y, w, h);
    }
}
