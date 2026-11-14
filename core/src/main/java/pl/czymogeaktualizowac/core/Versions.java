package pl.czymogeaktualizowac.core;

import java.util.Comparator;

/** Version ordering for WordPress.org plugin versions ("1.9.10", "11.3.0-beta.1", "11.3.0-rc.1"). */
public final class Versions {

    public static final Comparator<String> ORDER = Versions::compare;

    private Versions() {
    }

    public static int compare(String a, String b) {
        String[] pa = split(a);
        String[] pb = split(b);
        int c = compareCore(pa[0], pb[0]);
        if (c != 0) {
            return c;
        }
        boolean preA = !pa[1].isEmpty();
        boolean preB = !pb[1].isEmpty();
        if (preA != preB) {
            return preA ? -1 : 1; // a release sorts after its own pre-releases
        }
        return comparePre(pa[1], pb[1]);
    }

    public static boolean isPrerelease(String v) {
        return !split(v)[1].isEmpty();
    }

    public static String major(String v) {
        String core = split(v)[0];
        int dot = core.indexOf('.');
        return dot < 0 ? core : core.substring(0, dot);
    }

    /** "11.2.2" -> "11.2" (WordPress "major" is major.minor; WooCommerce uses the same convention). */
    public static String majorMinor(String v) {
        String[] parts = split(v)[0].split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
    }

    private static String[] split(String v) {
        String s = v == null ? "" : v.trim();
        int dash = s.indexOf('-');
        if (dash < 0) {
            return new String[]{s, ""};
        }
        return new String[]{s.substring(0, dash), s.substring(dash + 1)};
    }

    private static int compareCore(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long vx = i < x.length ? num(x[i]) : 0;
            long vy = i < y.length ? num(y[i]) : 0;
            if (vx != vy) {
                return Long.compare(vx, vy);
            }
        }
        return 0;
    }

    private static int comparePre(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (i >= x.length) {
                return -1;
            }
            if (i >= y.length) {
                return 1;
            }
            boolean nx = x[i].matches("\\d+");
            boolean ny = y[i].matches("\\d+");
            int c;
            if (nx && ny) {
                c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            } else {
                c = x[i].compareTo(y[i]); // alpha < beta < rc
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static long num(String s) {
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
