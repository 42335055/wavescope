package com.dsh.wavescope;

/**
 * Small allocation-free iterative radix-2 FFT.
 * The transform is done in place on separate real / imaginary arrays.
 */
public final class Fft {

    private Fft() {
    }

    public static void transform(float[] re, float[] im) {
        final int n = re.length;
        if (n < 2 || (n & (n - 1)) != 0 || im.length != n) {
            throw new IllegalArgumentException("FFT length must be a power of two");
        }

        // Bit-reversal permutation.
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }

        // Butterfly stages.
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wRe = (float) Math.cos(angle);
            float wIm = (float) Math.sin(angle);
            int half = len >> 1;
            for (int start = 0; start < n; start += len) {
                float curRe = 1.0f;
                float curIm = 0.0f;
                int end = start + half;
                for (int k = start; k < end; k++) {
                    int l = k + half;
                    float vRe = re[l] * curRe - im[l] * curIm;
                    float vIm = re[l] * curIm + im[l] * curRe;

                    float uRe = re[k];
                    float uIm = im[k];
                    re[k] = uRe + vRe;
                    im[k] = uIm + vIm;
                    re[l] = uRe - vRe;
                    im[l] = uIm - vIm;

                    float nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
