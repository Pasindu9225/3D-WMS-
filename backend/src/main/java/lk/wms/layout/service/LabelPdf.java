package lk.wms.layout.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Bin labels as PDF: a QR code holding exactly the printed bin code, plus the code in large type.
 * The QR is drawn as vector rectangles (crisp on any printer, small files). Pure: codes in, PDF out.
 */
public final class LabelPdf {

    public enum Format {
        /** A4, 3 x 8 labels of 70 x 37 mm (Avery 3474 and similar sheets). */
        SHEET,
        /** One 100 x 50 mm label per page, for thermal label printers. */
        SINGLE
    }

    static final int SHEET_COLUMNS = 3;
    static final int SHEET_ROWS = 8;
    private static final float MM = 72f / 25.4f;
    private static final float PAD = 3 * MM;

    private LabelPdf() {
    }

    public static void write(List<String> codes, String warehouseCode, Format format, OutputStream out) {
        Rectangle page = format == Format.SHEET ? PageSize.A4 : new Rectangle(100 * MM, 50 * MM);
        Document doc = new Document(page, 0, 0, 0, 0);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setFullCompression();
            doc.addTitle("Bin labels " + warehouseCode);
            doc.addCreator("3D WMS");
            doc.open();
            BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BaseFont regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            PdfContentByte canvas = writer.getDirectContent();
            int perPage = format == Format.SHEET ? SHEET_COLUMNS * SHEET_ROWS : 1;
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0 && i % perPage == 0) {
                    doc.newPage();
                }
                float w = format == Format.SHEET ? 70 * MM : page.getWidth();
                float h = format == Format.SHEET ? 37 * MM : page.getHeight();
                int slot = i % perPage;
                float x = format == Format.SHEET ? (slot % SHEET_COLUMNS) * w : 0;
                // sheet rows from the top, with the 0.5 mm top margin of these label sheets
                float y = format == Format.SHEET ? page.getHeight() - 0.5f * MM - (slot / SHEET_COLUMNS + 1) * h : 0;
                drawLabel(canvas, bold, regular, codes.get(i), warehouseCode, x, y, w, h);
            }
            doc.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawLabel(PdfContentByte c, BaseFont bold, BaseFont regular, String code, String warehouseCode,
            float x, float y, float w, float h) {
        float qrSize = h - 2 * PAD;
        BitMatrix qr = qr(code);
        float module = qrSize / qr.getWidth();
        c.setColorFill(Color.BLACK);
        for (int row = 0; row < qr.getHeight(); row++) {
            int col = 0;
            while (col < qr.getWidth()) { // one rectangle per run of dark modules
                if (!qr.get(col, row)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < qr.getWidth() && qr.get(col, row)) {
                    col++;
                }
                c.rectangle(x + PAD + start * module, y + PAD + qrSize - (row + 1) * module, (col - start) * module, module);
            }
        }
        c.fill();

        float textX = x + PAD + qrSize + 2 * MM;
        float textWidth = w - (textX - x) - PAD;
        float size = Math.min(h * 0.32f, textWidth / bold.getWidthPoint(code, 1f)); // fit the longest codes
        c.beginText();
        c.setFontAndSize(bold, size);
        c.setTextMatrix(textX, y + h / 2 - size * 0.25f);
        c.showText(code);
        c.setColorFill(Color.DARK_GRAY);
        c.setFontAndSize(regular, 7);
        c.setTextMatrix(textX, y + PAD + 1 * MM);
        c.showText(warehouseCode);
        c.endText();
    }

    /** QR with medium error correction (survives scuffs) and a 2-module quiet zone. */
    static BitMatrix qr(String code) {
        try {
            return new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode " + code, e);
        }
    }
}
