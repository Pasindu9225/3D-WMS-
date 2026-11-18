package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import lk.wms.catalog.service.ProductService.Carton;
import lk.wms.catalog.service.ProductService.Handling;
import lk.wms.layout.service.BinService.Candidate;

/**
 * Where a product may be stored (v2.1), the way warehouse systems use handling classes: temperature, UN dangerous goods
 * class and stack limits, checked against the place. Pure: no database, so every rule is unit-tested on its own.
 * Putaway uses it for suggestions and for the scan.
 */
final class HandlingRules {

    /** Why a place is refused: a code for the screen and a sentence for the worker. */
    record Refusal(String code, String message) {
    }

    /** A pallet already standing lower in the same floor-row column. */
    record Below(int level, Handling handling) {
    }

    private HandlingRules() {
    }

    /**
     * Null when the product may go into this place.
     *
     * @param rackHazards hazard classes of the other products already in the place's rack
     * @param below pallets lower in the same floor-row column (FLOOR racks only)
     * @param unitsAfter units of this product the place would hold afterwards
     * @param carton the product's carton, or null when it has no carton size
     */
    static Refusal check(String name, Handling h, Candidate c, Collection<String> rackHazards, List<Below> below,
            BigDecimal unitsAfter, Carton carton) {
        boolean handlingZone = !List.of("RECEIVING", "DISPATCH", "QUARANTINE").contains(c.zoneType());
        if (handlingZone && "CHILLED".equals(h.temp()) && !"COLD".equals(c.zoneType())) {
            return new Refusal("WRONG_TEMPERATURE", name + " must be kept chilled; zone " + c.zoneCode() + " is not a chilled store");
        }
        if (handlingZone && "FROZEN".equals(h.temp()) && !"FROZEN".equals(c.zoneType())) {
            return new Refusal("WRONG_TEMPERATURE", name + " must be kept frozen; zone " + c.zoneCode() + " is not a freezer");
        }
        String hazard = h.hazardClass();
        if (hazard != null) {
            if (handlingZone && !allows(c.zoneHazards(), hazard)) {
                return new Refusal("HAZARD_ZONE", name + " is dangerous goods class " + hazard + "; zone " + c.zoneCode()
                        + " does not take class " + main(hazard));
            }
            for (String other : rackHazards) {
                if (apart(hazard, other)) {
                    return new Refusal("HAZARD_APART", name + " (class " + hazard + ") cannot go in " + c.code()
                            + ": class " + other + " is already in that rack and must be kept apart");
                }
            }
        }
        if ("FLOOR".equals(c.style())) {
            if (h.stackMax() != null && c.level() > h.stackMax()) {
                return new Refusal("STACK_LIMIT", name + " may be stacked " + h.stackMax() + " high; " + c.code() + " is level " + c.level());
            }
            for (Below b : below) {
                if (b.handling().fragile()) {
                    return new Refusal("STACK_LIMIT", c.code() + " would stand on a fragile pallet (level " + b.level() + ")");
                }
                if (b.handling().stackMax() != null && c.level() > b.handling().stackMax()) {
                    return new Refusal("STACK_LIMIT", c.code() + " would make the stack " + c.level()
                            + " high; the pallet on level " + b.level() + " allows " + b.handling().stackMax());
                }
            }
        } else if (carton != null && (h.fragile() || h.stackMax() != null)) {
            long most = mostUnits(carton, c, h);
            if (most >= 0 && unitsAfter.compareTo(BigDecimal.valueOf(most)) > 0) {
                int layers = h.fragile() ? 1 : h.stackMax();
                return new Refusal("STACK_LIMIT", name + " may be stacked " + layers + " carton" + (layers == 1 ? "" : "s")
                        + " high; " + c.code() + " holds at most " + most + " of it");
            }
        }
        return null;
    }

    /**
     * Most units of the product that fit the place when its cartons stand no higher than allowed: as many per layer
     * as fit (turned if that fits more, as the 3D view stacks them) times the allowed layers. -1 when not even one
     * carton fits (the capacity check decides then).
     */
    static long mostUnits(Carton carton, Candidate c, Handling h) {
        long asIs = (long) (c.placeWidthMm() / carton.lengthMm()) * (c.placeDepthMm() / carton.widthMm());
        long turned = (long) (c.placeWidthMm() / carton.widthMm()) * (c.placeDepthMm() / carton.lengthMm());
        long perLayer = Math.max(asIs, turned);
        int fitHigh = c.placeHeightMm() / carton.heightMm();
        if (perLayer == 0 || fitHigh == 0) {
            return -1;
        }
        int limit = h.fragile() ? 1 : h.stackMax() == null ? fitHigh : h.stackMax();
        return perLayer * Math.min(fitHigh, limit) * carton.units();
    }

    /** A zone's list ("3,8") takes a class by its main number: 5.1 needs 5. */
    static boolean allows(String zoneHazards, String hazardClass) {
        return zoneHazards != null && List.of(zoneHazards.split(",")).contains(main(hazardClass));
    }

    /**
     * Classes that must not share a rack. Simplified from the ADR/IMDG segregation tables:
     * explosives (1) apart from every other class; oxidisers and peroxides (5.x) apart from flammables (2.1, 3, 4.x);
     * water-reactive (4.3) apart from corrosives (8).
     */
    // ponytail: a short fixed table, not the full IMDG matrix; extend here when a customer stores more classes.
    static boolean apart(String a, String b) {
        if ("1".equals(main(a)) != "1".equals(main(b))) {
            return true;
        }
        return oneWay(a, b) || oneWay(b, a);
    }

    private static boolean oneWay(String a, String b) {
        boolean flammable = "2.1".equals(b) || "3".equals(main(b)) || "4".equals(main(b));
        return ("5".equals(main(a)) && flammable) || ("4.3".equals(a) && "8".equals(main(b)));
    }

    private static String main(String hazardClass) {
        int dot = hazardClass.indexOf('.');
        return dot < 0 ? hazardClass : hazardClass.substring(0, dot);
    }
}
