package lk.wms.layout.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A warehouse layout as the editor sends it and the draft stores it. All lengths are millimetres on
 * the floor plan (x to the right, y down), so no rounding happens between editor, server and 3D view.
 * Limits here are the first line (SEC-20); LayoutValidator checks the geometry.
 */
public record LayoutDocument(
        @NotNull @Size(min = 3, max = 100) List<@NotNull @Valid Point> floor,
        @NotNull @Size(max = 50) List<@NotNull @Valid Opening> openings,
        @NotNull @Size(max = 100) List<@NotNull @Valid Zone> zones,
        @NotNull @Size(max = 2000) List<@NotNull @Valid Rack> racks,
        @NotNull @Valid Settings settings) {

    public static final int MAX_MM = 1_000_000; // 1 km: far beyond any warehouse

    public record Point(@Min(0) @Max(MAX_MM) int x, @Min(0) @Max(MAX_MM) int y) {
    }

    /** A door or loading dock on the outer wall. (x, y) is its middle. */
    public record Opening(
            @NotNull @Pattern(regexp = "DOOR|DOCK") String kind,
            @NotNull @Size(min = 1, max = 40) String label,
            @Min(0) @Max(MAX_MM) int x,
            @Min(0) @Max(MAX_MM) int y,
            @Min(500) @Max(20_000) int widthMm,
            /** Height in mm (v2.1); null on layouts saved before heights existed. */
            @Min(1_500) @Max(15_000) Integer heightMm) {
    }

    public record Zone(
            @NotNull @Pattern(regexp = "[A-Z0-9]{1,10}") String code,
            @NotNull @Pattern(regexp = "RECEIVING|STORAGE|PICKING|DISPATCH|QUARANTINE|COLD|FROZEN") String type,
            @NotNull @Size(max = 60) String name,
            @NotNull @Size(min = 3, max = 100) List<@NotNull @Valid Point> polygon,
            /** Main UN dangerous goods classes this zone may hold (v2.1); null or empty = none. */
            @Size(max = 9) List<@NotNull @Pattern(regexp = "[1-9]") String> hazardClasses) {

        public Zone(String code, String type, String name, List<Point> polygon) {
            this(code, type, name, polygon, null);
        }

        /** "3,8": sorted, no repeats; null when none. */
        public String hazardList() {
            return hazardClasses == null || hazardClasses.isEmpty() ? null
                    : String.join(",", new java.util.TreeSet<>(hazardClasses));
        }
    }

    /**
     * A rack placed on the floor. (x, y) is its centre, so rotating does not move it. rotation is any whole
     * degree, clockwise. widthMm/depthMm/heightMm resize this one rack; null means the rack type size.
     */
    public record Rack(
            @NotNull @Pattern(regexp = "[A-Z0-9]{1,10}") String code,
            @NotNull @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String templateId,
            @NotNull @Pattern(regexp = "[A-Z0-9]{1,10}") String zone,
            @Min(0) @Max(MAX_MM) int x,
            @Min(0) @Max(MAX_MM) int y,
            @Min(0) @Max(359) int rotation,
            @Min(300) @Max(30_000) Integer widthMm,
            @Min(300) @Max(5_000) Integer depthMm,
            @Min(500) @Max(40_000) Integer heightMm) {

        /** Rack-type size unless this rack was resized. */
        public int width(int typeWidthMm) {
            return widthMm != null ? widthMm : typeWidthMm;
        }

        public int depth(int typeDepthMm) {
            return depthMm != null ? depthMm : typeDepthMm;
        }

        public int height(int typeHeightMm) {
            return heightMm != null ? heightMm : typeHeightMm;
        }
    }

    /** Per-layout settings the editor can change. */
    public record Settings(@Min(500) @Max(6000) int minAisleMm) {
        public static final Settings DEFAULT = new Settings(1200); // walk / hand-pallet aisle
    }
}
