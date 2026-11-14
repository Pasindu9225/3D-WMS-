package lk.wms.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import lk.wms.common.errors.ApiException;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void parsesQuotesCommasAndLineBreaksInsideQuotes() {
        List<List<String>> rows = Csv.parse("sku,name\r\nA1,\"Oil, coconut \"\"1L\"\"\"\n\nB2,\"two\nlines\"\n");
        assertThat(rows).containsExactly(List.of("sku", "name"), List.of("A1", "Oil, coconut \"1L\""),
                List.of("B2", "two\nlines"));
    }

    @Test
    void uploadsMustBeUtf8CsvWithinLimits() {
        byte[] ok = "﻿sku,name\nA1,Oil\n".getBytes(StandardCharsets.UTF_8);
        assertThat(Csv.readUpload("p.csv", ok)).hasSize(2).first().isEqualTo(List.of("sku", "name")); // BOM dropped
        assertThatThrownBy(() -> Csv.readUpload("p.xlsx", ok)).hasMessageContaining(".csv");
        assertThatThrownBy(() -> Csv.readUpload("p.csv", new byte[] {'P', 'K', 3, 4, (byte) 0xFF, 0}))
                .isInstanceOf(ApiException.class).hasMessageContaining("UTF-8"); // a zip/xlsx renamed
        assertThatThrownBy(() -> Csv.readUpload("p.csv", "sku\n".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("no rows");
        StringBuilder big = new StringBuilder("sku\n");
        for (int i = 0; i < 5001; i++) {
            big.append("A").append(i).append('\n');
        }
        assertThatThrownBy(() -> Csv.readUpload("p.csv", big.toString().getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("5,000");
        assertThatThrownBy(() -> Csv.parse("a,\"open")).hasMessageContaining("not closed");
    }

    @Test
    void exportedCellsCannotRunAsFormulas() {
        assertThat(Csv.line(List.of("=HYPERLINK(\"http://x\")", "+1", "-2", "@SUM(A1)", "ok", "a\"b")))
                .isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\",\"'+1\",\"'-2\",\"'@SUM(A1)\",\"ok\",\"a\"\"b\"\r\n");
    }
}
