package lk.wms.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HsSectionsTest {

    @Test
    void chaptersFallIntoTheirWorldCustomsSections() {
        assertThat(HsSections.of("010121")).isEqualTo(1); // live horses
        assertThat(HsSections.of("051199")).isEqualTo(1);
        assertThat(HsSections.of("090230")).isEqualTo(2); // black tea
        assertThat(HsSections.of("151311")).isEqualTo(3); // coconut oil
        assertThat(HsSections.of("282890")).isEqualTo(6); // bleach
        assertThat(HsSections.of("770000")).isEqualTo(15); // reserved chapter, inside base metals
        assertThat(HsSections.of("851660")).isEqualTo(16); // rice cooker
        assertThat(HsSections.of("970110")).isEqualTo(21);
        assertThat(HsSections.of("990000")).isZero();
        assertThat(HsSections.of(null)).isZero();
        assertThat(HsSections.roman(16)).isEqualTo("XVI");
    }
}
