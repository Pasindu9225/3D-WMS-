package lk.wms.layout.service;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
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
        List<Path2D> placed = new ArrayList<>();
        List<String> placedCodes = new ArrayList<>();
        List<Boolean> placedStraight = new ArrayList<>();
        for (Rack r : doc.racks()) {
            if (!rackCodes.add(r.code())) {
                issues.add(error("DUPLICATE_RACK_CODE", "Rack code " + r.code() + " is used twice", r.code()));
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
            Path2D rect = footprint(r, fp);
            if (!inside(new Area(rect), zone)) {
                issues.add(error("RACK_OUTSIDE_ZONE", "Rack " + r.code() + " is not fully inside zone " + r.zone(),
                        r.code()));
            }
            placed.add(rect);
            placedCodes.add(r.code());
            placedStraight.add(straight(r));
        }
        checkRackPairs(placed, placedStraight, placedCodes, doc.settings().minAisleMm(), issues);
        return issues;
    }

    /**
     * Footprint on the floor: width x depth (the rack's own size if resized) centred on (x, y), turned
     * clockwise by rotation degrees. Multiples of 90° turn exactly, so straight racks stay exact.
     */
    static Path2D footprint(Rack r, Footprint fp) {
        double w = r.width(fp.widthMm());
        double d = r.depth(fp.depthMm());
        Path2D.Double rect = new Path2D.Double(new Rectangle2D.Double(-w / 2, -d / 2, w, d));
        AffineTransform at = AffineTransform.getTranslateInstance(r.x(), r.y());
        if (r.rotation() % 90 == 0) {
            at.quadrantRotate(r.rotation() / 90);
        } else {
            at.rotate(Math.toRadians(r.rotation()));
        }
        rect.transform(at);
        return rect;
    }

    /** Axis-aligned footprint: exact box maths apply. */
    private static boolean straight(Rack r) {
        return r.rotation() % 90 == 0;
    }

    /**
     * Overlaps are errors. Two racks facing each other with a gap smaller than the minimum aisle get a
     * warning; a gap of 0 is back-to-back (normal) and is not flagged. Straight racks use exact box maths;
     * angled racks use their real outline, and their aisle is the shortest edge-to-edge distance.
     * ponytail: O(n²) over ≤2,000 racks (≤2 M cheap pairs); add a sweep line if layouts grow far beyond that.
     */
    private static void checkRackPairs(List<Path2D> racks, List<Boolean> straight, List<String> codes, int minAisleMm,
            List<Issue> issues) {
        int warnings = 0;
        List<Rectangle2D> boxes = racks.stream().map(Path2D::getBounds2D).toList();
        for (int i = 0; i < racks.size(); i++) {
            Rectangle2D a = boxes.get(i);
            for (int j = i + 1; j < racks.size(); j++) {
                Rectangle2D b = boxes.get(j);
                double gap;
                if (straight.get(i) && straight.get(j)) {
                    double overlapX = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
                    double overlapY = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
                    if (overlapX > 0 && overlapY > 0) {
                        issues.add(overlap(codes.get(i), codes.get(j)));
                        continue;
                    }
                    gap = overlapY > 0 ? -overlapX : overlapX > 0 ? -overlapY : -1; // facing across x or y
                } else {
                    if (a.getMaxX() + minAisleMm < b.getMinX() || b.getMaxX() + minAisleMm < a.getMinX()
                            || a.getMaxY() + minAisleMm < b.getMinY() || b.getMaxY() + minAisleMm < a.getMinY()) {
                        continue; // far apart: skip the exact check
                    }
                    if (overlaps(new Area(racks.get(i)), new Area(racks.get(j)))) {
                        issues.add(overlap(codes.get(i), codes.get(j)));
                        continue;
                    }
                    gap = distance(racks.get(i), racks.get(j));
                    if (gap < 1) {
                        continue; // touching (back to back)
                    }
                }
                if (gap > 0 && gap < minAisleMm && warnings < MAX_WARNINGS) {
                    warnings++;
                    issues.add(new Issue(Severity.WARNING, "AISLE_TOO_NARROW", "Aisle between " + codes.get(i) + " and "
                            + codes.get(j) + " is " + Math.round(gap) + " mm, less than " + minAisleMm + " mm",
                            codes.get(j)));
                }
            }
        }
    }

    private static Issue overlap(String a, String b) {
        return error("RACKS_OVERLAP", "Racks " + a + " and " + b + " overlap", b);
    }

    /** Shortest distance between two non-overlapping outlines: corner of one to edge of the other, both ways. */
    private static double distance(Path2D a, Path2D b) {
        List<double[]> pa = corners(a);
        List<double[]> pb = corners(b);
        double best = Double.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            List<double[]> from = pass == 0 ? pa : pb;
            List<double[]> to = pass == 0 ? pb : pa;
            for (double[] p : from) {
                for (int k = 0; k < to.size(); k++) {
                    double[] s1 = to.get(k);
                    double[] s2 = to.get((k + 1) % to.size());
                    best = Math.min(best, Line2D.ptSegDist(s1[0], s1[1], s2[0], s2[1], p[0], p[1]));
                }
            }
        }
        return best;
    }

    private static List<double[]> corners(Path2D shape) {
        List<double[]> out = new ArrayList<>();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            if (it.currentSegment(c) != PathIterator.SEG_CLOSE) {
                out.add(new double[] {c[0], c[1]});
            }
        }
        return out;
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
        return negligible(rest);
    }

    private static boolean overlaps(Area a, Area b) {
        Area both = new Area(a);
        both.intersect(b);
        return !negligible(both);
    }

    /** Empty, or a sliver under 1 mm thick left by rounding along an angled edge. */
    private static boolean negligible(Area area) {
        Rectangle2D b = area.getBounds2D();
        return area.isEmpty() || b.getWidth() < 1 || b.getHeight() < 1;
    }

    private static Issue error(String code, String message, String ref) {
        return new Issue(Severity.ERROR, code, message, ref);
    }
}
