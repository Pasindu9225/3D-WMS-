package lk.wms.common;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lk.wms.common.errors.ApiException;
import org.springframework.http.HttpStatus;

/**
 * CSV for imports and exports (SEC-46), no library: RFC 4180 quotes, commas and line breaks inside quotes.
 * Uploads must be real UTF-8 text with a .csv name, at most 10 MB and 5,000 data rows. Exported cells that a
 * spreadsheet would run as a formula (starting with = + - @, tab or CR) are prefixed with an apostrophe.
 */
public final class Csv {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 5_000;

    private Csv() {
    }

    /** Checks the upload and returns its rows (header first). Empty lines are skipped. */
    public static List<List<String>> readUpload(String filename, byte[] bytes) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw bad("FILE_TYPE_NOT_ALLOWED", "Upload a .csv file");
        }
        if (bytes.length == 0) {
            throw bad("FILE_EMPTY", "The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "CSV files can be at most 10 MB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw bad("CSV_NOT_TEXT", "The file is not UTF-8 text (save it from Excel as \"CSV UTF-8\")");
        }
        if (text.indexOf('\0') >= 0) {
            throw bad("CSV_NOT_TEXT", "The file is not UTF-8 text (save it from Excel as \"CSV UTF-8\")");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1); // Excel's byte-order mark
        }
        List<List<String>> rows = parse(text);
        if (rows.size() - 1 > MAX_ROWS) {
            throw bad("CSV_TOO_MANY_ROWS", "Import at most 5,000 rows at once");
        }
        if (rows.size() < 2) {
            throw bad("CSV_NO_ROWS", "The file has a header but no rows");
        }
        return rows;
    }

    /** RFC 4180: "" inside quotes is a quote; commas and line breaks inside quotes are data. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                addIfNotBlank(rows, row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw bad("CSV_BAD_QUOTES", "A quoted value is not closed");
        }
        row.add(cell.toString());
        addIfNotBlank(rows, row);
        return rows;
    }

    private static void addIfNotBlank(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(v -> !v.isBlank())) {
            rows.add(row);
        }
    }

    /** One CSV line, every cell quoted and made formula-safe. */
    public static String line(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(safe(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        return sb.append("\r\n").toString();
    }

    /** Cells a spreadsheet would treat as a formula get a leading apostrophe (OWASP CSV injection). */
    public static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
