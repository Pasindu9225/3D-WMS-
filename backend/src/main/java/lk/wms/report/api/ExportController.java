package lk.wms.report.api;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.RateLimiter;
import org.springframework.http.HttpStatus;
import lk.wms.report.service.ExportService;
import lk.wms.report.service.ExportService.Filter;
import lk.wms.report.service.ExportService.Format;
import lk.wms.report.service.ExportService.What;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exports (v1.0 task 8): reports:export. Everything is checked before the first byte goes out. */
@RestController
public class ExportController {

    private final ExportService exports;
    private final RateLimiter limiter;

    public ExportController(ExportService exports, RateLimiter limiter) {
        this.exports = exports;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/exports/{what}.{format}")
    @PreAuthorize("hasAuthority('reports:export')")
    public void export(@PathVariable @Pattern(regexp = "stock|movements|orders") String what,
            @PathVariable @Pattern(regexp = "xlsx|pdf") String format,
            @RequestParam(required = false) @Size(max = 26) String warehouse,
            @RequestParam(required = false) @Pattern(regexp = "RECEIVE|PUTAWAY|TRANSFER|ADJUST|PICK|SHIP|RETURN|COUNT") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|RELEASED|PICKING|PICKED|PACKED|SHIPPED|CANCELLED") String status,
            @RequestParam(required = false) @Size(max = 200) String q,
            HttpServletResponse response) throws IOException {
        CurrentUser cu = CurrentUser.require();
        if (limiter.tryConsume(RateLimiter.Scope.EXPORT, Long.toString(cu.userId())) > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_EXPORTS", "Too many exports; wait a minute");
        }
        Format f = Format.valueOf(format.toUpperCase(Locale.ROOT));
        ExportService.Plan plan = exports.plan(cu, What.valueOf(what.toUpperCase(Locale.ROOT)), f,
                new Filter(warehouse, type, from, to, status, q));
        response.setContentType(f == Format.XLSX ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/pdf");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(plan.fileName() + "." + format).build().toString());
        exports.write(plan, response.getOutputStream());
    }
}
