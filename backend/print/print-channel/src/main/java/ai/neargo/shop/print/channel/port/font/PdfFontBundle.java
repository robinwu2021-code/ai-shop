package ai.neargo.shop.print.channel.port.font;

import ai.neargo.shop.print.api.*;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;

import java.io.*;

@Component
public final class PdfFontBundle {
    public static final String VERSION = "NOTO_SC_V1";

    private static class Content {
        static final byte[] bytes = loadBytes();
        static final String hash = PrintHashes.sha256(bytes);

        private static byte[] loadBytes() {
            try (var in =
                    PdfFontBundle.class.getResourceAsStream(
                            "/print/fonts/NotoSansSC-Regular.ttf")) {
                if (in == null) throw new IllegalStateException("Print font missing");
                return in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public FontRef ref() {
        return new FontRef(VERSION, Content.hash, "NotoSansSC");
    }

    public void verify(FontRef ref) {
        if (!ref().equals(ref)) throw new PrintFailure("print.fontVersionMismatch");
    }

    public PDType0Font load(PDDocument doc) {
        try {
            return PDType0Font.load(doc, new ByteArrayInputStream(Content.bytes), true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
