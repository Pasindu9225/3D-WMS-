package lk.wms.report.service;

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
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.service.StockService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.service.SalesOrderService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Exports (v1.0 task 8): stock, movements and sales orders to Excel or PDF. Rows are read 1,000 at a time and written
 * straight to the response (Excel through SXSSF, 100 rows in memory; PDF onto the stream), so memory stays flat.
 * Text is always written as text, never as a formula (SEC-46).
 */
@Service
public class ExportService {

    public enum What { STOCK, MOVEMENTS, ORDERS }

    public enum Format { XLSX, PDF }

    /** Filters, as the screens have them; unused ones are null. */
    public record Filter(String warehouseId, String movementType, LocalDate from, LocalDate to, String orderStatus, String query,
            LocalDate asOf) {
    }

    /** A checked export, ready to write: title for the file and page, the columns, and how to fetch each page of rows. */
    public record Plan(What what, Format format, String fileName, String title, String subtitle, List<String> columns, long rows,
            Function<Pageable, Page<List<Object>>> source) {
    }

    static final int PAGE = 1_000;
    // ponytail: Sri Lanka time on generated files; a per-company time zone when a customer outside it signs up.
    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StockService stock;
    private final SalesOrderService orders;
    private final WarehouseService warehouses;
    private final AuditService audit;
    private final int maxXlsxRows;
    private final int maxPdfRows;

    public ExportService(StockService stock, SalesOrderService orders, WarehouseService warehouses, AuditService audit,
            @Value("${wms.export.max-xlsx-rows:200000}") int maxXlsxRows, @Value("${wms.export.max-pdf-rows:20000}") int maxPdfRows) {
        this.stock = stock;
        this.orders = orders;
        this.warehouses = warehouses;
        this.audit = audit;
        this.maxXlsxRows = maxXlsxRows;
        this.maxPdfRows = maxPdfRows;
    }

    /** Checks access, the filters and the size before anything is written; throws so no half file is ever sent. */
    @org.springframework.transaction.annotation.Transactional
    public Plan plan(CurrentUser cu, What what, Format format, Filter f) {
        String today = LocalDate.now(ZONE).toString();
        Plan plan = switch (what) {
            case STOCK -> {
                WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, f.warehouseId());
                if (f.asOf() != null) {
                    Function<Pageable, Page<List<Object>>> src = p -> stock.asOf(cu, f.warehouseId(), f.asOf(), p)
                            .map(r -> List.<Object>of(r.sku(), r.name(), r.onHand(), r.baseUom(),
                                    r.valueMinor() == null ? "" : BigDecimal.valueOf(r.valueMinor(), 2)));
                    yield new Plan(what, format, "stock-" + wh.code() + "-on-" + f.asOf(), "Stock on " + f.asOf(),
                            "Warehouse " + wh.code() + " · end of day (UTC), valued at today's cost",
                            List.of("SKU", "Product", "On hand", "Unit", "Value (LKR)"), 0, src);
                }
                Function<Pageable, Page<List<Object>>> src = p -> stock.rows(cu, f.warehouseId(), null, null, null, false, p)
                        .map(r -> List.<Object>of(place(r.binCode()), r.sku(), r.name(), text(r.batchNo()), date(r.expiryDate()), r.qtyOnHand(),
                                r.qtyReserved(), r.baseUom(), r.receivedAt()));
                yield new Plan(what, format, "stock-" + wh.code() + "-" + today, "Stock", "Warehouse " + wh.code(),
                        List.of("Place", "SKU", "Product", "Batch", "Expiry", "On hand", "Reserved", "Unit", "First received"), 0, src);
            }
            case MOVEMENTS -> {
                WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, f.warehouseId());
                Function<Pageable, Page<List<Object>>> src = p -> stock.movements(cu, f.warehouseId(), f.movementType(), f.from(), f.to(), p)
                        .map(r -> List.<Object>of(r.at(), r.type(), r.sku(), r.name(), text(r.batchNo()), date(r.expiryDate()), place(r.from()),
                                place(r.to()), r.qty(), r.baseUom(), text(r.reason()), text(r.user())));
                String filters = (f.movementType() == null ? "all types" : f.movementType().toLowerCase())
                        + (f.from() == null ? "" : ", from " + f.from()) + (f.to() == null ? "" : ", to " + f.to());
                yield new Plan(what, format, "movements-" + wh.code() + "-" + today, "Stock movements", "Warehouse " + wh.code() + " · " + filters,
                        List.of("When", "Type", "SKU", "Product", "Batch", "Expiry", "From", "To", "Quantity", "Unit", "Reason", "By"), 0, src);
            }
            case ORDERS -> {
                Function<Pageable, Page<List<Object>>> src = p -> orders.list(cu, f.orderStatus(), f.query(),
                        PageRequest.of(p.getPageNumber(), p.getPageSize(), Sort.by(Sort.Direction.DESC, "id")))
                        .map(o -> List.<Object>of(o.number(), o.status(), o.customerName(), o.customerCode(), text(o.customerRef()),
                                o.warehouseCode(), date(o.requestedDate()), BigDecimal.valueOf(o.lineCount()), o.createdAt()));
                String filters = (f.orderStatus() == null ? "all statuses" : f.orderStatus().toLowerCase())
                        + (f.query() == null || f.query().isBlank() ? "" : ", matching \"" + f.query() + "\"");
                yield new Plan(what, format, "sales-orders-" + today, "Sales orders", filters,
                        List.of("Number", "Status", "Customer", "Customer code", "Customer order no.", "Warehouse", "Wanted by", "Lines", "Created"),
                        0, src);
            }
        };
        long rows = plan.source().apply(PageRequest.of(0, 1)).getTotalElements();
        int limit = format == Format.XLSX ? maxXlsxRows : maxPdfRows;
        if (rows > limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE", rows + " rows is more than " + limit
                    + " for " + format.name().toLowerCase() + "; narrow the dates" + (format == Format.PDF ? " or use Excel" : ""));
        }
        audit.record(cu.tenantId(), cu.userId(), "EXPORTED", "report", null, null,
                Map.of("what", what.name(), "format", format.name(), "rows", rows));
        return new Plan(plan.what(), format, plan.fileName(), plan.title(), plan.subtitle(), plan.columns(), rows, plan.source());
    }

    /** Writes every page of rows to the stream in the plan's format. */
    public void write(Plan plan, OutputStream out) throws IOException {
        TableWriter w = plan.format() == Format.XLSX ? new XlsxWriter(out) : new PdfWriterTable(out);
        w.start(plan.title(), plan.subtitle() + " · generated " + WHEN.format(Instant.now().atZone(ZONE)), plan.columns());
        for (int page = 0; ; page++) {
            Page<List<Object>> rows = plan.source().apply(PageRequest.of(page, PAGE));
            for (List<Object> row : rows) {
                w.row(row);
            }
            if (!rows.hasNext()) {
                break;
            }
        }
        w.finish();
    }

    private static String place(String code) {
        return code == null ? "" : switch (code) {
            case "RECEIVING" -> "Receiving area";
            case "DISPATCH" -> "Dispatch area";
            default -> code;
        };
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static Object date(LocalDate d) {
        return d == null ? "" : d;
    }

    // ---------------------------------------------------------------- writers

    /** One table, written row by row; the two formats behind one shape. */
    interface TableWriter {
        void start(String title, String subtitle, List<String> columns) throws IOException;

        void row(List<Object> cells) throws IOException;

        void finish() throws IOException;
    }

    /** Excel through SXSSF: 100 rows in memory, the rest in a temp file removed when done. */
    static final class XlsxWriter implements TableWriter {
        private final OutputStream out;
        private final SXSSFWorkbook book = new SXSSFWorkbook(100);
        private Sheet sheet;
        private CellStyle dateStyle;
        private CellStyle timeStyle;
        private int next;

        XlsxWriter(OutputStream out) {
            this.out = out;
        }

        @Override
        public void start(String title, String subtitle, List<String> columns) {
            sheet = book.createSheet(title.length() > 31 ? title.substring(0, 31) : title);
            dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            timeStyle = book.createCellStyle();
            timeStyle.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            CellStyle bold = book.createCellStyle();
            org.apache.poi.ss.usermodel.Font font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            sheet.createRow(next++).createCell(0).setCellValue(title + " - " + subtitle);
            Row head = sheet.createRow(next++);
            for (int i = 0; i < columns.size(); i++) {
                head.createCell(i).setCellValue(columns.get(i));
                head.getCell(i).setCellStyle(bold);
            }
            sheet.createFreezePane(0, next);
        }

        @Override
        public void row(List<Object> cells) {
            Row r = sheet.createRow(next++);
            for (int i = 0; i < cells.size(); i++) {
                Object v = cells.get(i);
                var c = r.createCell(i);
                if (v instanceof BigDecimal n) {
                    c.setCellValue(n.doubleValue());
                } else if (v instanceof LocalDate d) {
                    c.setCellValue(d);
                    c.setCellStyle(dateStyle);
                } else if (v instanceof Instant t) {
                    c.setCellValue(t.atZone(ZONE).toLocalDateTime());
                    c.setCellStyle(timeStyle);
                } else {
                    c.setCellValue(v == null ? "" : v.toString()); // a string cell: "=..." stays text, never a formula
                }
            }
        }

        @Override
        public void finish() throws IOException {
            try {
                book.write(out);
            } finally {
                book.dispose();
                book.close();
            }
        }
    }

    /** A4 landscape PDF; the table goes out in chunks so the document never holds every row. */
    static final class PdfWriterTable implements TableWriter {
        private static final Font HEAD = new Font(Font.HELVETICA, 8, Font.BOLD);
        private static final Font CELL = new Font(Font.HELVETICA, 8);
        private static final int CHUNK = 200;
        private final Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        private final OutputStream out;
        private List<String> columns;
        private PdfPTable table;
        private int inTable;

        PdfWriterTable(OutputStream out) {
            this.out = out;
        }

        @Override
        public void start(String title, String subtitle, List<String> columns) {
            PdfWriter.getInstance(doc, out).setFullCompression();
            doc.addTitle(title);
            doc.addCreator("3D WMS");
            doc.open();
            doc.add(new Paragraph(title, new Font(Font.HELVETICA, 14, Font.BOLD)));
            Paragraph sub = new Paragraph(subtitle, new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY));
            sub.setSpacingAfter(8);
            doc.add(sub);
            this.columns = columns;
            newTable();
        }

        private void newTable() {
            table = new PdfPTable(columns.size());
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            table.setComplete(false);
            for (String c : columns) {
                PdfPCell h = new PdfPCell(new Phrase(c, HEAD));
                h.setBackgroundColor(new Color(235, 238, 242));
                table.addCell(h);
            }
            inTable = 0;
        }

        @Override
        public void row(List<Object> cells) {
            for (Object v : cells) {
                String s = v == null ? "" : v instanceof Instant t ? WHEN.format(t.atZone(ZONE))
                        : v instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString() : v.toString();
                PdfPCell c = new PdfPCell(new Phrase(s, CELL));
                if (v instanceof BigDecimal) {
                    c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(c);
            }
            if (++inTable % CHUNK == 0) {
                doc.add(table); // incomplete table: rows written so far leave memory, the header repeats on new pages
            }
        }

        @Override
        public void finish() {
            table.setComplete(true);
            doc.add(table);
            doc.close();
        }
    }

}
