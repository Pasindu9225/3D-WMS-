package lk.wms.catalog.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.wms.catalog.service.ImportResult.RowError;
import lk.wms.common.errors.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Reads CSV rows by header name and collects per-row, per-column errors, so the user gets one list of
 * everything to fix. Each row becomes the same request record the API uses and passes the same Bean Validation.
 */
public final class CsvRows {

    private final List<List<String>> rows;
    private final Map<String, Integer> columns = new HashMap<>();
    public final List<RowError> errors = new java.util.ArrayList<>();

    public CsvRows(List<List<String>> rows, List<String> known, List<String> required) {
        this.rows = rows;
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim().toLowerCase(Locale.ROOT);
            if (!known.contains(name)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CSV_BAD_HEADER", "Unknown column \"" + header.get(i)
                        + "\". Columns: " + String.join(", ", known));
            }
            columns.put(name, i);
        }
        for (String r : required) {
            if (!columns.containsKey(r)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CSV_BAD_HEADER", "Missing column \"" + r + "\"");
            }
        }
    }

    public int size() {
        return rows.size() - 1;
    }

    /** Spreadsheet row number of data row i (header is row 1). */
    public static int rowNumber(int i) {
        return i + 2;
    }

    public String text(int i, String column) {
        Integer c = columns.get(column);
        List<String> row = rows.get(i + 1);
        if (c == null || c >= row.size()) {
            return null;
        }
        String v = row.get(c).trim();
        if (v.length() > 1 && v.charAt(0) == '\'' && "=+-@".indexOf(v.charAt(1)) >= 0) {
            v = v.substring(1); // the apostrophe our formula-safe export adds; round trips cleanly
        }
        return v.isEmpty() ? null : v;
    }

    public String upper(int i, String column) {
        String v = text(i, column);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    /** Decimal times a factor, as a whole number (kg x 1000 = g); null when blank, error when not a number. */
    public Long scaled(int i, String column, int factor) {
        String v = text(i, column);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.replace(",", "")).multiply(BigDecimal.valueOf(factor)).setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            errors.add(new RowError(rowNumber(i), column, "\"" + v + "\" is not a number"));
            return null;
        }
    }

    public boolean yesNo(int i, String column) {
        String v = text(i, column);
        if (v == null) {
            return false;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> true;
            case "no", "n", "false", "0" -> false;
            default -> {
                errors.add(new RowError(rowNumber(i), column, "Use yes or no"));
                yield false;
            }
        };
    }

    public void error(int i, String column, String message) {
        errors.add(new RowError(rowNumber(i), column, message));
    }

    /** Bean Validation of the row's request; returns true when it is valid. */
    public <T> boolean valid(Validator validator, int i, T request, Map<String, String> fieldToColumn) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        for (ConstraintViolation<T> v : violations) {
            String field = v.getPropertyPath().toString();
            String column = fieldToColumn.entrySet().stream().filter(e -> field.startsWith(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(field);
            errors.add(new RowError(rowNumber(i), column, v.getMessage()));
        }
        return violations.isEmpty();
    }

    /** SEC-45: imports count as uploads (20 per user per minute). */
    public static void uploadAllowed(lk.wms.common.web.RateLimiter limiter, lk.wms.common.security.CurrentUser cu) {
        if (limiter.tryConsume(lk.wms.common.web.RateLimiter.Scope.UPLOAD, Long.toString(cu.userId())) > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Too many uploads; wait a minute");
        }
    }

    /** A CSV download (UTF-8 with a byte-order mark so Excel shows Sinhala/Tamil text correctly). */
    public static org.springframework.http.ResponseEntity<byte[]> csvFile(String name, String body) {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(name).build().toString())
                .body(("﻿" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
