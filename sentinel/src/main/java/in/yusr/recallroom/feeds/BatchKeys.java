package in.yusr.recallroom.feeds;

import java.util.Locale;

/**
 * Batch numbers arrive messy from every side: CDSCO rows carry stray spaces and line breaks
 * ("03BF226\n0"), FDA lot lists mix in punctuation, and photos of strips get OCR confusions
 * (O vs 0, I vs 1). Comparisons go through these keys. Ported from DawaGuard.
 */
public final class BatchKeys {

    private BatchKeys() {
    }

    /** Uppercase, letters and digits only. */
    public static String strict(String batchNo) {
        if (batchNo == null) {
            return "";
        }
        return batchNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** Strict key with commonly confused characters folded together, for tolerant matching. */
    public static String loose(String batchNo) {
        String key = strict(batchNo);
        StringBuilder out = new StringBuilder(key.length());
        for (char c : key.toCharArray()) {
            out.append(switch (c) {
                case 'O', 'Q', 'D' -> '0';
                case 'I', 'L' -> '1';
                case 'Z' -> '2';
                case 'S' -> '5';
                case 'B' -> '8';
                case 'G' -> '6';
                default -> c;
            });
        }
        return out.toString();
    }
}
