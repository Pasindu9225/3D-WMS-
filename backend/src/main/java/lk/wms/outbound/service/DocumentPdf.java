package lk.wms.outbound.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lk.wms.outbound.service.PackService.PackItem;
import lk.wms.outbound.service.PackService.Papers;

/**
 * The packing slip (what is in the boxes, for the packer and the customer's store) and the delivery note (what was
 * delivered, signed by the customer). Pure: figures in, PDF out. Every cell is plain text, never markup.
 */
public final class DocumentPdf {

    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font TEXT = new Font(Font.HELVETICA, 10);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.DARK_GRAY);
    private static final Color SHADE = new Color(235, 238, 242);
    // ponytail: Sri Lanka time on paper; a per-company time zone when a customer outside it signs up.
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private DocumentPdf() {
    }

    public static byte[] packingSlip(Papers p) {
        return build("Packing slip " + p.number(), doc -> {
            header(doc, p.company(), "PACKING SLIP");
            facts(doc, new String[][] {
                {"Order", p.number()}, {"Delivery note", p.deliveryNoteNo()},
                {"Customer", p.customerName() + " (" + p.customerCode() + ")"}, {"Customer order no.", dash(p.customerRef())},
                {"From warehouse", p.warehouseCode()}, {"Packed", WHEN.format(p.packedAt().atZone(ZONE))},
                {"Cartons", Integer.toString(p.cartons())}, {"Weight", p.weightG() == null ? "-" : kg(p.weightG())},
            });
            PdfPTable t = table(new float[] {3, 7, 2, 2}, "SKU", "Product", "Packed", "Unit");
            for (PackItem i : p.items()) {
                if (i.picked().signum() > 0) {
                    row(t, i.sku(), i.name(), PackService.plain(i.picked()), i.baseUom());
                }
            }
            doc.add(t);
            doc.add(new Paragraph("Checked by scan at packing.", SMALL));
        });
    }

    public static byte[] deliveryNote(Papers p) {
        return build("Delivery note " + p.deliveryNoteNo(), doc -> {
            header(doc, p.company(), "DELIVERY NOTE  " + p.deliveryNoteNo());
            facts(doc, new String[][] {
                {"Deliver to", p.customerName() + " (" + p.customerCode() + ")"}, {"Date", p.packedAt().atZone(ZONE).toLocalDate().toString()},
                {"Address", dash(p.shipTo())}, {"Our order", p.number()},
                {"Customer order no.", dash(p.customerRef())}, {"Cartons", Integer.toString(p.cartons())},
            });
            PdfPTable t = table(new float[] {3, 7, 2, 2, 2}, "SKU", "Product", "Ordered", "Delivered", "Unit");
            for (PackItem i : p.items()) {
                row(t, i.sku(), i.name(), PackService.plain(i.ordered()), PackService.plain(i.picked()), i.baseUom());
            }
            doc.add(t);
            Paragraph space = new Paragraph(" ");
            space.setSpacingAfter(24);
            doc.add(space);
            PdfPTable sign = new PdfPTable(3);
            sign.setWidthPercentage(100);
            for (String label : new String[] {"Received in good condition (signature)", "Name", "Date"}) {
                PdfPCell c = new PdfPCell(new Phrase(label, SMALL));
                c.setFixedHeight(48);
                c.setVerticalAlignment(Element.ALIGN_BOTTOM);
                c.setBorder(PdfPCell.TOP);
                c.setPaddingTop(4);
                sign.addCell(c);
            }
            doc.add(sign);
        });
    }

    // ---------------------------------------------------------------- layout

    private interface Body {
        void write(Document doc);
    }

    private static byte[] build(String title, Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, out).setFullCompression();
        doc.addTitle(title);
        doc.addCreator("3D WMS");
        doc.open();
        body.write(doc);
        doc.close();
        return out.toByteArray();
    }

    private static void header(Document doc, String company, String title) {
        doc.add(new Paragraph(company, BOLD));
        Paragraph t = new Paragraph(title, TITLE);
        t.setSpacingBefore(4);
        t.setSpacingAfter(12);
        doc.add(t);
    }

    /** Label / value pairs in two columns. */
    private static void facts(Document doc, String[][] pairs) {
        PdfPTable t = new PdfPTable(new float[] {2, 4, 2, 4});
        t.setWidthPercentage(100);
        t.setSpacingAfter(14);
        for (String[] pair : pairs) {
            PdfPCell label = new PdfPCell(new Phrase(pair[0], SMALL));
            label.setBorder(PdfPCell.NO_BORDER);
            label.setPaddingBottom(4);
            PdfPCell value = new PdfPCell(new Phrase(pair[1], TEXT));
            value.setBorder(PdfPCell.NO_BORDER);
            value.setPaddingBottom(4);
            t.addCell(label);
            t.addCell(value);
        }
        doc.add(t);
    }

    private static PdfPTable table(float[] widths, String... heads) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1); // repeated on every page
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, BOLD));
            c.setBackgroundColor(SHADE);
            c.setPadding(5);
            t.addCell(c);
        }
        return t;
    }

    private static void row(PdfPTable t, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(cells[i], TEXT));
            c.setPadding(5);
            if (i >= 2 && i < cells.length - 1) {
                c.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            t.addCell(c);
        }
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String kg(int grams) {
        return java.math.BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString() + " kg";
    }
}
