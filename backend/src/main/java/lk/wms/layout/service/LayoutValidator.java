package lk.wms.layout.service;

import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.layout.service.LayoutDocument.Point;
import lk.wms.layout.service.LayoutDocument.Rack;
import lk.wms.layout.service.LayoutDocument.Zone;

/**
 * Geometry checks for a layout draft, using the JDK's java.awt.geom (no library). Pure function:
 * errors block saving, warnings are shown to the planner. Touching edges are fine; overlap is not.
 */
public final class LayoutValidator {

    public enum Severity { ERROR, WARNING }

    /** {@code ref} names the zone/rack/opening so the editor can highlight it. */
    public record Issue(Severity severity, String code, String message, String ref) {
    }

    /** Size of a rack template, as far as the floor plan is concerned. */
    public record Footprint(int widthMm, int depthMm) {
    }

    static final int MAX_WARNINGS = 50;
    private static final double WALL_TOLERANCE_MM = 100;

    private LayoutValidator() {
    }

    public static List<Issue> validate(LayoutDocument doc, Map<String, Footprint> templates) {
        List<Issue> issues = new ArrayList<>();

        // ---- floor
        if (selfIntersects(doc.floor())) {
            issues.add(error("FLOOR_SELF_INTERSECTS", "The floor outline crosses itself", "floor"));
            return issues; // nothing else can be judged against a broken floor
        }
        Area floor = area(doc.floor());

        // ---- doors and docks sit on the outer wall
        for (LayoutDocument.Opening o : doc.openings()) {
            if (distanceToOutline(doc.floor(), o.x(), o.y()) > WALL_TOLERANCE_MM) {
                issues.add(error("OPENING_NOT_ON_WALL", o.label() + " is not on the outer wall", o.label()));
            }
        }

        // ---- zones
        Map<String, Area> zones = new HashMap<>();
        for (Zone z : doc.zones()) {
            if (zones.containsKey(z.code())) {
                issues.add(error("DUPLICATE_ZONE_CODE", "Zone code " + z.code() + " is used twice", z.code()));
                continue;
            }
            if (selfIntersects(z.polygon())) {
                issues.add(error("ZONE_SELF_INTERSECTS", "Zone " + z.code() + " crosses itself", z.code()));
                continue;
            }
            Area area = area(z.polygon());
            if (!inside(area, floor)) {
                issues.add(error("ZONE_OUTSIDE_FLOOR", "Zone " + z.code() + " goes outside the floor", z.code()));
            }
            for (Map.Entry<String, Area> other : zones.entrySet()) {
                if (overlaps(area, other.getValue())) {
                    issues.add(error("ZONES_OVERLAP", "Zones " + other.getKey() + " and " + z.code() + " overlap", z.code()));
                }
            }
            zones.put(z.code(), area);
        }

        // ---- racks
        Set<String> rackCodes = new HashSet<>();
        List<Rectangle2D> placed = new ArrayList<>();
        List<String> placedCodes = new ArrayList<>();
        for (Rack r : doc.racks()) {
            if (!rackCodes.add(r.code())) {
                issues.add(error("DUPLICATE_RACK_CODE", "Rack code " + r.code() + " is used twice", r.code()));
                continue;
            }
            if (r.rotation() % 90 != 0) {
                issues.add(error("RACK_ROTATION", "Rack " + r.code() + " must be turned in steps of 90°", r.code()));
                continue;
            }
            Footprint fp = templates.get(r.templateId());
            if (fp == null) {
                issues.add(error("RACK_UNKNOWN_TEMPLATE", "Rack " + r.code() + " uses a rack type that does not exist",
                        r.code()));
                continue;
            }
            Area zone = zones.get(r.zone());
            if (zone == null) {
                issues.add(error("RACK_UNKNOWN_ZONE", "Rack " + r.code() + " is in zone " + r.zone()
                        + ", which does not exist", r.code()));
                continue;
            }
            Rectangle2D rect = footprint(r, fp);
            if (!zone.contains(rect)) {
                issues.add(error("RACK_OUTSIDE_ZONE", "Rack " + r.code() + " is not fully inside zone " + r.zone(),
                        r.code()));
            }
            placed.add(rect);
            placedCodes.add(r.code());
        }
        checkRackPairs(placed, placedCodes, doc.settings().minAisleMm(), issues);
        return issues;
    }

    /** Footprint on the floor: width x depth, swapped when turned 90° or 270°; (x, y) is the centre. */
    static Rectangle2D footprint(Rack r, Footprint fp) {
        boolean turned = r.rotation() == 90 || r.rotation() == 270;
        double w = turned ? fp.depthMm() : fp.widthMm();
        double d = turned ? fp.widthMm() : fp.depthMm();
        return new Rectangle2D.Double(r.x() - w / 2, r.y() - d / 2, w, d);
    }

    /**
     * Overlaps are errors. Two racks facing each other with a gap smaller than the minimum aisle get a
     * warning; a gap of 0 is back-to-back (normal) and is not flagged.
     * ponytail: O(n²) over ≤2,000 racks (≤2 M cheap pairs); add a sweep line if layouts grow far beyond that.
     */
    private static void checkRackPairs(List<Rectangle2D> racks, List<String> codes, int minAisleMm, List<Issue> issues) {
        int warnings = 0;
        for (int i = 0; i < racks.size(); i++) {
            Rectangle2D a = racks.get(i);
            for (int j = i + 1; j < racks.size(); j++) {
                Rectangle2D b = racks.get(j);
                double overlapX = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
                double overlapY = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
                if (overlapX > 0 && overlapY > 0) {
                    issues.add(error("RACKS_OVERLAP", "Racks " + codes.get(i) + " and " + codes.get(j) + " overlap",
                            codes.get(j)));
                    continue;
                }
                double gap = overlapY > 0 ? -overlapX : overlapX > 0 ? -overlapY : -1; // facing across x or y
                if (gap > 0 && gap < minAisleMm && warnings < MAX_WARNINGS) {
                    warnings++;
                    issues.add(new Issue(Severity.WARNING, "AISLE_TOO_NARROW", "Aisle between " + codes.get(i) + " and "
                            + codes.get(j) + " is " + Math.round(gap) + " mm, less than " + minAisleMm + " mm",
                            codes.get(j)));
                }
            }
        }
    }

    static boolean selfIntersects(List<Point> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Point a1 = polygon.get(i);
            Point a2 = polygon.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                if (j == i || (j + 1) % n == i || j == (i + 1) % n) {
                    continue; // neighbouring edges share a corner
                }
                Point b1 = polygon.get(j);
                Point b2 = polygon.get((j + 1) % n);
                if (Line2D.linesIntersect(a1.x(), a1.y(), a2.x(), a2.y(), b1.x(), b1.y(), b2.x(), b2.y())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double distanceToOutline(List<Point> polygon, int x, int y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i);
            Point b = polygon.get((i + 1) % polygon.size());
            best = Math.min(best, Line2D.ptSegDist(a.x(), a.y(), b.x(), b.y(), x, y));
        }
        return best;
    }

    static Area area(List<Point> polygon) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(polygon.get(0).x(), polygon.get(0).y());
        for (int i = 1; i < polygon.size(); i++) {
            path.lineTo(polygon.get(i).x(), polygon.get(i).y());
        }
        path.closePath();
        return new Area(path);
    }

    private static boolean inside(Area inner, Area outer) {
        Area rest = new Area(inner);
        rest.subtract(outer);
        return rest.isEmpty();
    }

    private static boolean overlaps(Area a, Area b) {
        Area both = new Area(a);
        both.intersect(b);
        return !both.isEmpty();
    }

    private static Issue error(String code, String message, String ref) {
        return new Issue(Severity.ERROR, code, message, ref);
    }
}
