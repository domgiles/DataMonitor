package com.dom.datamonitor.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Number formatting and axis steps shared by the desktop charts and the terminal dashboard,
 * with the same rules as the web front end, so all three show the same numbers.
 */
public final class NumberFormats {

    private NumberFormats() {}

    /**
     * Formats a value for display, with the same rules as the web front end's
     * {@code formatValue}: 1,284 · 512.5 → 513 · 12.5 · 3.14 · 0.00412 · 3.4M. Values of 100 and
     * more have no decimals, 10–100 one, below 10 two (trailing zeros dropped); values below 1
     * keep three significant digits so small rates stay readable; millions and up are compact.
     */
    public static String formatValue(double value) {
        return formatValue(value, Locale.getDefault());
    }

    public static String formatValue(double value, Locale locale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "–";
        double abs = Math.abs(value);
        if (abs >= 1_000_000) {
            NumberFormat compact = NumberFormat.getCompactNumberInstance(locale, NumberFormat.Style.SHORT);
            compact.setMaximumFractionDigits(1);
            compact.setRoundingMode(RoundingMode.HALF_UP);
            return compact.format(value);
        }
        NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setRoundingMode(RoundingMode.HALF_UP);   // like the browser: 512.5 → 513, not 512
        if (abs > 0 && abs < 1) return formatSignificant(value, abs, locale);   // 0.00412, 0.125
        f.setMaximumFractionDigits(abs == 0 || abs >= 100 ? 0 : abs >= 10 ? 1 : 2);
        return f.format(value);
    }

    /**
     * Short value for axis labels, with the same rules as the web front end's
     * {@code formatCompact}: 0 · 0.002 · 2.5 · 300 · 12K · 3.4M.
     */
    public static String formatCompact(double value) {
        return formatCompact(value, Locale.getDefault());
    }

    public static String formatCompact(double value, Locale locale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "–";
        double abs = Math.abs(value);
        if (abs >= 1000) {
            NumberFormat compact = NumberFormat.getCompactNumberInstance(locale, NumberFormat.Style.SHORT);
            compact.setMaximumFractionDigits(1);
            compact.setRoundingMode(RoundingMode.HALF_UP);
            return compact.format(value);
        }
        if (abs > 0 && abs < 10) return formatSignificant(value, abs, locale);
        NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMaximumFractionDigits(0);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value);
    }

    /** Three significant digits: 0.00412, 2.5. */
    private static String formatSignificant(double value, double abs, Locale locale) {
        NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMaximumFractionDigits(Math.max(0, 2 - (int) Math.floor(Math.log10(abs))));
        return f.format(new BigDecimal(value).round(new MathContext(3, RoundingMode.HALF_UP)));
    }

    /**
     * Axis ticks from 0 with a round step (1, 2, 2.5 or 5 × a power of ten), aiming for about
     * four intervals, like the web charts: a maximum of 370 gives 0, 100, 200, 300, 400.
     * The last tick is the axis maximum (always &gt;= {@code max}).
     */
    public static double[] niceTicks(double max) {
        if (!(max > 0) || Double.isInfinite(max)) max = 1;
        double rough = max / 4;
        double mag   = Math.pow(10, Math.floor(Math.log10(rough)));
        double r     = rough / mag;
        double step  = (r <= 1 ? 1 : r <= 2 ? 2 : r <= 2.5 ? 2.5 : r <= 5 ? 5 : 10) * mag;
        int    count = (int) Math.ceil(max / step - 1e-9);
        double[] ticks = new double[count + 1];
        for (int i = 0; i <= count; i++) ticks[i] = i * step;
        return ticks;
    }
}
