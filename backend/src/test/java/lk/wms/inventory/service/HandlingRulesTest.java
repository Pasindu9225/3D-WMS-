package lk.wms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import lk.wms.catalog.service.ProductService.Carton;
import lk.wms.catalog.service.ProductService.Handling;
import lk.wms.layout.service.BinService.Candidate;
import org.junit.jupiter.api.Test;

class HandlingRulesTest {

    private static Candidate place(String zoneType, String hazards, String style, int level) {
        // a 1000 x 800 mm place, 1000 mm high
        return new Candidate(1, "B", "A-01-0" + level + "-01", zoneType, level, 0, false, 0, 0, "A", 7, 1, style, 1000, 800, 1000, hazards);
    }

    private static Handling h(String temp, String hazard, Integer stackMax, boolean fragile) {
        return new Handling(null, temp, hazard, stackMax, fragile);
    }

    private static String code(Handling h, Candidate c, List<String> rack, List<HandlingRules.Below> below, int units, Carton carton) {
        HandlingRules.Refusal r = HandlingRules.check("Fish", h, c, rack, below, BigDecimal.valueOf(units), carton);
        return r == null ? null : r.code();
    }

    @Test
    void coldGoodsOnlyInTheirOwnZone() {
        Handling frozen = h("FROZEN", null, null, false);
        assertThat(code(frozen, place("STORAGE", null, "PALLET", 1), List.of(), List.of(), 1, null)).isEqualTo("WRONG_TEMPERATURE");
        assertThat(code(frozen, place("COLD", null, "PALLET", 1), List.of(), List.of(), 1, null)).isEqualTo("WRONG_TEMPERATURE");
        assertThat(code(frozen, place("FROZEN", null, "PALLET", 1), List.of(), List.of(), 1, null)).isNull();
        assertThat(code(h("CHILLED", null, null, false), place("COLD", null, "PALLET", 1), List.of(), List.of(), 1, null)).isNull();
        assertThat(code(frozen, place("RECEIVING", null, "PALLET", 1), List.of(), List.of(), 1, null)).isNull(); // in transit
        assertThat(code(Handling.NONE, place("FROZEN", null, "PALLET", 1), List.of(), List.of(), 1, null)).isNull();
    }

    @Test
    void dangerousGoodsNeedAZoneThatTakesTheirMainClass() {
        Handling oxidiser = h("AMBIENT", "5.1", null, false);
        assertThat(code(oxidiser, place("STORAGE", null, "PALLET", 1), List.of(), List.of(), 1, null)).isEqualTo("HAZARD_ZONE");
        assertThat(code(oxidiser, place("STORAGE", "3,8", "PALLET", 1), List.of(), List.of(), 1, null)).isEqualTo("HAZARD_ZONE");
        assertThat(code(oxidiser, place("STORAGE", "3,5", "PALLET", 1), List.of(), List.of(), 1, null)).isNull();
    }

    @Test
    void someClassesAreKeptApartInARack() {
        Handling flammable = h("AMBIENT", "3", null, false);
        assertThat(code(flammable, place("STORAGE", "3,5,8", "PALLET", 1), List.of("5.1"), List.of(), 1, null)).isEqualTo("HAZARD_APART");
        assertThat(code(flammable, place("STORAGE", "3,5,8", "PALLET", 1), List.of("8"), List.of(), 1, null)).isNull();
        assertThat(HandlingRules.apart("4.3", "8")).isTrue();
        assertThat(HandlingRules.apart("1.1", "9")).isTrue();
        assertThat(HandlingRules.apart("1.1", "1.4")).isFalse();
        assertThat(HandlingRules.apart("3", "8")).isFalse();
    }

    @Test
    void floorStacksRespectEveryPalletInTheColumn() {
        Handling eggs = h("AMBIENT", null, 1, true);
        Handling rice = h("AMBIENT", null, 3, false);
        assertThat(code(eggs, place("STORAGE", null, "FLOOR", 2), List.of(), List.of(), 1, null)).isEqualTo("STACK_LIMIT");
        assertThat(code(eggs, place("STORAGE", null, "FLOOR", 1), List.of(), List.of(), 1, null)).isNull();
        // nothing on top of a fragile pallet, and the stack no higher than the lowest limit below
        assertThat(code(rice, place("STORAGE", null, "FLOOR", 2), List.of(), List.of(new HandlingRules.Below(1, eggs)), 1, null))
                .isEqualTo("STACK_LIMIT");
        assertThat(code(rice, place("STORAGE", null, "FLOOR", 3), List.of(), List.of(new HandlingRules.Below(1, h("AMBIENT", null, 2, false))), 1, null))
                .isEqualTo("STACK_LIMIT");
        assertThat(code(rice, place("STORAGE", null, "FLOOR", 3), List.of(), List.of(new HandlingRules.Below(1, rice)), 1, null)).isNull();
    }

    @Test
    void cartonsInARackPlaceStandNoHigherThanAllowed() {
        // 500 x 400 x 200 mm cartons of 6: 4 per layer on 1000 x 800, 5 layers fit in 1000 mm
        Carton carton = new Carton(500, 400, 200, 6);
        Candidate shelf = place("STORAGE", null, "SHELF", 1);
        assertThat(HandlingRules.mostUnits(carton, shelf, h("AMBIENT", null, 2, false))).isEqualTo(4 * 2 * 6);
        assertThat(HandlingRules.mostUnits(carton, shelf, h("AMBIENT", null, 2, true))).isEqualTo(4 * 6); // fragile: 1 layer
        assertThat(HandlingRules.mostUnits(carton, shelf, h("AMBIENT", null, 9, false))).isEqualTo(4 * 5 * 6); // the height wins
        assertThat(code(h("AMBIENT", null, 2, false), shelf, List.of(), List.of(), 49, carton)).isEqualTo("STACK_LIMIT");
        assertThat(code(h("AMBIENT", null, 2, false), shelf, List.of(), List.of(), 48, carton)).isNull();
        assertThat(code(Handling.NONE, shelf, List.of(), List.of(), 1000, carton)).isNull(); // no rule: capacity decides
    }
}
