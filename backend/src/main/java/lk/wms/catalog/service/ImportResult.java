package lk.wms.catalog.service;

import java.util.List;

/**
 * Outcome of a CSV import: either everything was saved, or nothing was and {@code errors} says which rows to fix
 * (row numbers as a spreadsheet shows them: the header is row 1). At most 100 errors are returned.
 */
public record ImportResult(boolean saved, int created, int updated, List<RowError> errors) {

    static final int MAX_ERRORS = 100;

    public record RowError(int row, String field, String message) {
    }

    static ImportResult of(int created, int updated, List<RowError> errors) {
        if (errors.isEmpty()) {
            return new ImportResult(true, created, updated, List.of());
        }
        List<RowError> sorted = errors.stream().sorted(java.util.Comparator.comparingInt(RowError::row)).limit(MAX_ERRORS).toList();
        return new ImportResult(false, 0, 0, sorted);
    }
}
