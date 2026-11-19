package lk.wms.catalog.service;

/**
 * The 21 sections of the Harmonized System (World Customs Organization): the world-standard grouping of the 97 HS
 * chapters into commodity groups, e.g. II Vegetable products = chapters 06-14. A 3PL client can be limited to some
 * sections; a product's section comes from the first two digits of its HS code.
 */
public final class HsSections {

    /** First chapter of each section, section 1 at index 0; a section runs to the chapter before the next one's. */
    private static final int[] FIRST_CHAPTER = {1, 6, 15, 16, 25, 28, 39, 41, 44, 47, 50, 64, 68, 71, 72, 84, 86, 90, 93, 94, 97};

    private HsSections() {
    }

    /** Section 1-21 of an HS code (digits, 6-10 long), or 0 when it is not a real chapter (00, 98, 99). */
    public static int of(String hsCode) {
        if (hsCode == null || hsCode.length() < 2 || !Character.isDigit(hsCode.charAt(0)) || !Character.isDigit(hsCode.charAt(1))) {
            return 0;
        }
        int chapter = Integer.parseInt(hsCode.substring(0, 2));
        if (chapter < 1 || chapter > 97) {
            return 0;
        }
        int section = 0;
        while (section < FIRST_CHAPTER.length && FIRST_CHAPTER[section] <= chapter) {
            section++;
        }
        return section;
    }

    /** Roman numeral as customs papers write sections (I-XXI). */
    public static String roman(int section) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII",
            "XIX", "XX", "XXI"};
        return section >= 1 && section <= 21 ? r[section - 1] : "?";
    }
}
