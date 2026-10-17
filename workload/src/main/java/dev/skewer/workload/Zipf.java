package dev.skewer.workload;

import java.util.Arrays;
import java.util.Random;

/** Zipf-distributed ranks over {@code n} items: rank 0 is the hottest. Exponent 0 is uniform. */
public final class Zipf {

    private final double[] cdf;

    public Zipf(int n, double exponent) {
        cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 1.0 / Math.pow(i + 1, exponent);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
    }

    public int sample(Random rnd) {
        int i = Arrays.binarySearch(cdf, rnd.nextDouble());
        return Math.min(cdf.length - 1, i >= 0 ? i : -i - 1);
    }
}
