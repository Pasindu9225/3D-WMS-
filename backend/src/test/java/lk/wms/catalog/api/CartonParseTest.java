package lk.wms.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** v2.1: the CSV column carton_cm ("40x30x25", centimetres) into millimetre sides. */
class CartonParseTest {

    @Test
    void readsThreeSidesInCentimetres() {
        assertThat(ProductController.carton("40x30x25", 0)).isEqualTo(400);
        assertThat(ProductController.carton("40 × 30.5 × 25 cm", 1)).isEqualTo(305);
        assertThat(ProductController.carton("40*30*25", 2)).isEqualTo(250);
        assertThat(ProductController.carton("  ", 0)).isNull(); // no carton size: fine
        assertThat(ProductController.carton("40x30", 0)).isZero(); // unreadable: 0, which validation then refuses
    }
}
