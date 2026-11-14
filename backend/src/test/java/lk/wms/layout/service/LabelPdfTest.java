package lk.wms.layout.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

class LabelPdfTest {

    @Test
    void sheetPutsTwentyFourLabelsOnEachA4Page() throws Exception {
        List<String> codes = codes(30);
        PdfReader pdf = new PdfReader(render(codes, LabelPdf.Format.SHEET));
        assertThat(pdf.getNumberOfPages()).isEqualTo(2); // 24 + 6
        assertThat(pdf.getPageSize(1).getWidth()).isCloseTo(595f, org.assertj.core.data.Offset.offset(1f)); // A4
        PdfTextExtractor text = new PdfTextExtractor(pdf);
        assertThat(text.getTextFromPage(1)).contains("A-R01-01-01", "WH1");
        assertThat(text.getTextFromPage(2)).contains("A-R01-01-30").doesNotContain("A-R01-01-01");
    }

    @Test
    void singleFormatIsOneLabelPerPage() throws Exception {
        PdfReader pdf = new PdfReader(render(codes(3), LabelPdf.Format.SINGLE));
        assertThat(pdf.getNumberOfPages()).isEqualTo(3);
        assertThat(pdf.getPageSize(1).getWidth()).isCloseTo(283f, org.assertj.core.data.Offset.offset(1f)); // 100 mm
    }

    @Test
    void qrCodeHoldsExactlyThePrintedBinCode() throws Exception {
        for (String code : List.of("A-R01-01-01", "COLD-R99-20-50", "Z9-1-01-01")) {
            assertThat(decode(LabelPdf.qr(code))).isEqualTo(code);
        }
    }

    @Test
    void fiveThousandLabelsStaySmall() {
        byte[] pdf = render(codes(5000), LabelPdf.Format.SHEET);
        assertThat(pdf.length).isLessThan(3_000_000); // vector QR, compressed: a whole warehouse in a few MB
    }

    private static byte[] render(List<String> codes, LabelPdf.Format format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LabelPdf.write(codes, "WH1", format, out);
        return out.toByteArray();
    }

    private static List<String> codes(int n) {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            codes.add(String.format("A-R%02d-01-%02d", (i - 1) / 99 + 1, (i - 1) % 99 + 1));
        }
        return codes;
    }

    /** Draws the matrix 4 px per module and decodes it with ZXing's reader. */
    private static String decode(BitMatrix m) throws Exception {
        int scale = 4;
        int size = m.getWidth() * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = m.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, pixels))))
                .getText();
    }
}
