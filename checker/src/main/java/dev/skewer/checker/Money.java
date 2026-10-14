package dev.skewer.checker;

import java.util.Locale;

/** Formats cents as euros for reports. */
public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        String sign = cents < 0 ? "−" : "";
        long abs = Math.abs(cents);
        return String.format(Locale.ROOT, "%s€%,d.%02d", sign, abs / 100, abs % 100);
    }
}
