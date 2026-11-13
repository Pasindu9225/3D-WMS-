package lk.wms.common.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lk.wms.common.errors.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

/** List response shape for every list endpoint: {@code {items, page, size, total}}. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> map) {
        return new PageResponse<>(page.getContent().stream().map(map).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Builds a page request from {@code ?page=&size=&sort=field,dir}. Size is capped at 100 and only
     * sort fields in {@code allowed} (API name -> entity property) are accepted, so a client can
     * never sort by an internal column.
     */
    public static Pageable request(Integer page, Integer size, String sort, Map<String, String> allowed,
            Sort fallback) {
        int p = page == null ? 0 : Math.max(page, 0);
        int s = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(p, s, fallback);
        }
        String[] parts = sort.split(",", 2);
        String property = allowed.get(parts[0].trim());
        if (property == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SORT_NOT_ALLOWED", "This list cannot be sorted by that field");
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(p, s, Sort.by(dir, property).and(fallback));
    }
}
