package lk.wms.layout.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.wms.layout.service.LayoutDocument.Opening;
import lk.wms.layout.service.LayoutDocument.Point;
import lk.wms.layout.service.LayoutDocument.Rack;
import lk.wms.layout.service.LayoutDocument.Settings;
import lk.wms.layout.service.LayoutDocument.Zone;
import lk.wms.layout.service.LayoutValidator.Footprint;
import lk.wms.layout.service.LayoutValidator.Issue;
import lk.wms.layout.service.LayoutValidator.Severity;
import org.junit.jupiter.api.Test;

/** Floor 40 m x 20 m; one storage zone covering it; rack type 2.7 m wide x 1.1 m deep. */
class LayoutValidatorTest {

    static final String T = "01TEMPLATE0000000000000000";
    static final Map<String, Footprint> TEMPLATES = Map.of(T, new Footprint(2700, 1100, 12));
    static final List<Point> FLOOR = rect(0, 0, 40_000, 20_000);

    @Test
    void cleanLayoutHasNoIssues() {
        assertThat(validate(List.of(rack("R1", 5000, 5000), rack("R2", 5000, 7800)))).isEmpty(); // 1.7 m aisle
    }

    @Test
    void backToBackRacksAreFineButNarrowAislesWarn() {
        // R1 spans y 4450..5550; R2 directly behind it (touching) and R3 with an 800 mm gap
        List<Issue> issues = validate(List.of(rack("R1", 5000, 5000), rack("R2", 5000, 6100), rack("R3", 5000, 8000)));
        assertThat(issues).extracting(Issue::code).containsExactly("AISLE_TOO_NARROW");
        assertThat(issues.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(issues.get(0).message()).contains("800 mm");
    }

    @Test
    void overlappingRacksAreAnError() {
        assertThat(validate(List.of(rack("R1", 5000, 5000), rack("R2", 6000, 5000))))
                .extracting(Issue::code).contains("RACKS_OVERLAP");
    }

    @Test
    void rotationSwapsWidthAndDepth() {
        // Turned 90°, R1 is 1.1 m wide x 2.7 m deep, so a rack 1.2 m to its right no longer overlaps.
        Rack turned = new Rack("R1", T, "A", 5000, 5000, 90, null, null, null);
        Rack neighbour = new Rack("R2", T, "A", 6200, 5000, 90, null, null, null);
        assertThat(validate(List.of(turned, neighbour))).extracting(Issue::code).containsOnly("AISLE_TOO_NARROW");
    }

    @Test
    void racksTurnToAnyAngleAndAreCheckedByTheirRealOutline() {
        // 45°: 2.7 x 1.1 m turned has a 2.69 m square bounding box around (10, 10) m.
        Rack angled = new Rack("R1", T, "A", 10_000, 10_000, 45, null, null, null);
        assertThat(validate(List.of(angled))).isEmpty();
        // End to end along the same 45° line, 0.69 m apart: not touching, but a narrow aisle.
        Rack nearCorner = new Rack("R2", T, "A", 12_400, 12_400, 45, null, null, null);
        assertThat(validate(List.of(angled, nearCorner))).extracting(Issue::code).containsExactly("AISLE_TOO_NARROW");
        // Same centre line, overlapping for real.
        Rack onTop = new Rack("R3", T, "A", 11_000, 11_000, 45, null, null, null);
        assertThat(validate(List.of(angled, onTop))).extracting(Issue::code).containsExactly("RACKS_OVERLAP");
        // Turned so a corner sticks out past the zone edge at x = 0.
        assertThat(validate(List.of(new Rack("R4", T, "A", 1200, 5000, 30, null, null, null)))).extracting(Issue::code)
                .containsExactly("RACK_OUTSIDE_ZONE");
    }

    @Test
    void aLayoutCannotMakeMoreThan50000Bins() {
        // 1,000 bins per rack (20 levels x 50): 50 racks is the limit, 51 is one rack too many.
        Map<String, Footprint> big = Map.of(T, new Footprint(2700, 1100, 1000));
        List<Rack> racks = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            racks.add(rack("R" + i, 2000 + (i % 10) * 3000, 2000 + (i / 10) * 3000));
        }
        List<Zone> zones = List.of(zone("A", FLOOR));
        assertThat(LayoutValidator.validate(doc(FLOOR, zones, racks.subList(0, 50), List.of()), big))
                .extracting(Issue::code).doesNotContain("TOO_MANY_BINS");
        assertThat(LayoutValidator.validate(doc(FLOOR, zones, racks, List.of()), big))
                .extracting(Issue::code).contains("TOO_MANY_BINS");
    }

    @Test
    void aResizedRackUsesItsOwnSize() {
        // Type is 2.7 m wide; made 4 m wide, R1 reaches 0.65 m further and now overlaps R2 (centres 3.3 m apart).
        Rack wide = new Rack("R1", T, "A", 5000, 5000, 0, 4000, null, null);
        assertThat(validate(List.of(wide, rack("R2", 8300, 5000)))).extracting(Issue::code).containsExactly("RACKS_OVERLAP");
        assertThat(validate(List.of(rack("R1", 5000, 5000), rack("R2", 8300, 5000)))).extracting(Issue::code)
                .containsExactly("AISLE_TOO_NARROW"); // 600 mm gap at the type size
    }

    @Test
    void racksMustBeInsideAKnownZoneAndUseAKnownType() {
        assertThat(validate(List.of(rack("R1", 39_000, 5000)))).extracting(Issue::code).containsExactly("RACK_OUTSIDE_ZONE");
        assertThat(validate(List.of(new Rack("R1", T, "Z", 5000, 5000, 0, null, null, null)))).extracting(Issue::code)
                .containsExactly("RACK_UNKNOWN_ZONE");
        assertThat(validate(List.of(new Rack("R1", "01OTHER0000000000000000000", "A", 5000, 5000, 0, null, null, null))))
                .extracting(Issue::code).containsExactly("RACK_UNKNOWN_TEMPLATE");
        assertThat(validate(List.of(rack("R1", 5000, 5000), rack("R1", 20_000, 5000)))).extracting(Issue::code)
                .containsExactly("DUPLICATE_RACK_CODE");
    }

    @Test
    void zonesMustStayInsideTheFloorAndNotOverlap() {
        List<Zone> zones = List.of(zone("A", rect(0, 0, 20_000, 20_000)), zone("B", rect(19_000, 0, 41_000, 10_000)));
        List<Issue> issues = LayoutValidator.validate(doc(FLOOR, zones, List.of(), List.of()), TEMPLATES);
        assertThat(issues).extracting(Issue::code).containsExactlyInAnyOrder("ZONE_OUTSIDE_FLOOR", "ZONES_OVERLAP");
        // L-shaped floor: a zone in the cut-out corner is outside
        List<Point> lShape = List.of(new Point(0, 0), new Point(20_000, 0), new Point(20_000, 10_000),
                new Point(10_000, 10_000), new Point(10_000, 20_000), new Point(0, 20_000));
        assertThat(LayoutValidator.validate(doc(lShape, List.of(zone("C", rect(12_000, 12_000, 18_000, 18_000))),
                List.of(), List.of()), TEMPLATES)).extracting(Issue::code).containsExactly("ZONE_OUTSIDE_FLOOR");
    }

    @Test
    void crossedOutlinesAndOffWallDoorsAreErrors() {
        List<Point> bowTie = List.of(new Point(0, 0), new Point(10_000, 10_000), new Point(10_000, 0), new Point(0, 10_000));
        assertThat(LayoutValidator.validate(doc(bowTie, List.of(), List.of(), List.of()), TEMPLATES))
                .extracting(Issue::code).containsExactly("FLOOR_SELF_INTERSECTS");
        List<Opening> doors = List.of(new Opening("DOCK", "Dock 1", 10_000, 20_000, 3000, 3000),
                new Opening("DOOR", "Middle", 10_000, 10_000, 1000, null));
        assertThat(LayoutValidator.validate(doc(FLOOR, List.of(), List.of(), doors), TEMPLATES))
                .extracting(Issue::ref).containsExactly("Middle");
    }

    @Test
    void warningsAreCappedSoAHugeLayoutStaysReadable() {
        List<Rack> row = new ArrayList<>();
        for (int i = 0; i < 70; i++) { // two facing rows with a 500 mm gap: 70 narrow aisles
            row.add(rack("A" + i, 2000 + i * 2700, 3000));
            row.add(rack("B" + i, 2000 + i * 2700, 4600));
        }
        List<Point> bigFloor = rect(0, 0, 200_000, 20_000);
        List<Issue> issues = LayoutValidator.validate(doc(bigFloor, List.of(zone("A", bigFloor)), row, List.of()), TEMPLATES);
        assertThat(issues).hasSize(LayoutValidator.MAX_WARNINGS);
    }

    // ---------------------------------------------------------------- helpers

    private static List<Issue> validate(List<Rack> racks) {
        return LayoutValidator.validate(doc(FLOOR, List.of(zone("A", FLOOR)), racks, List.of()), TEMPLATES);
    }

    private static LayoutDocument doc(List<Point> floor, List<Zone> zones, List<Rack> racks, List<Opening> openings) {
        return new LayoutDocument(floor, openings, zones, racks, Settings.DEFAULT);
    }

    private static Rack rack(String code, int x, int y) {
        return new Rack(code, T, "A", x, y, 0, null, null, null);
    }

    private static Zone zone(String code, List<Point> polygon) {
        return new Zone(code, "STORAGE", "Zone " + code, polygon);
    }

    static List<Point> rect(int x1, int y1, int x2, int y2) {
        return List.of(new Point(x1, y1), new Point(x2, y1), new Point(x2, y2), new Point(x1, y2));
    }
}
