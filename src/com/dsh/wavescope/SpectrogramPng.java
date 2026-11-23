package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reads a waterfall PNG and its WaveScope plot/crop metadata. */
public final class SpectrogramPng {

    public static final class Info {
        public int imageWidth;
        public int imageHeight;
        public int plotLeft;
        public int plotTop;
        public int plotWidth;
        public int plotHeight;
        public int sampleRate = 44100;
        public int fftSize = 4096;
        public int channels = 2;
        public float speedPps;
        public boolean hasMetadata;
        public final Map<String, String> meta = new HashMap<>();
    }

    private SpectrogramPng() {
    }

    public static Info inspect(Context context, Uri uri) throws IOException {
        Info info = new Info();
        readMetadata(context, uri, info);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开图片");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        info.imageWidth = bounds.outWidth;
        info.imageHeight = bounds.outHeight;
        if (info.imageWidth <= 0 || info.imageHeight <= 0) {
            throw new IOException("无法读取图片尺寸");
        }
        if (!validPlot(info)) {
            inferPlot(info);
            info.hasMetadata = false;
        }
        return info;
    }

    private static boolean validPlot(Info info) {
        return info.plotWidth >= 16 && info.plotHeight >= 16
                && info.plotLeft >= 0 && info.plotTop >= 0
                && info.plotLeft + info.plotWidth <= info.imageWidth
                && info.plotTop + info.plotHeight <= info.imageHeight;
    }

    private static void inferPlot(Info info) {
        int base = Math.max(10, Math.min(38, info.imageHeight / 48));
        int left = Math.min(base * 5, Math.max(24, info.imageWidth / 4));
        int top = Math.min(base * 4, Math.max(20, info.imageHeight / 4));
        int bottom = Math.min(base * 3, Math.max(16, info.imageHeight / 5));
        int right = Math.min(base / 2 + 2, Math.max(6, info.imageWidth / 40));
        info.plotLeft = left;
        info.plotTop = top;
        info.plotWidth = info.imageWidth - left - right;
        info.plotHeight = info.imageHeight - top - bottom;
        info.fftSize = info.plotHeight > 2048 ? 8192 : 4096;
    }

    private static void readMetadata(Context context, Uri uri, Info info) throws IOException {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) return;
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
            byte[] sig = new byte[8];
            in.readFully(sig);
            if (sig[0] != (byte) 137 || sig[1] != 80 || sig[2] != 78 || sig[3] != 71) {
                return;
            }
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (Exception e) {
                    return;
                }
                if (length < 0 || length > 32 * 1024 * 1024) return;
                byte[] type = new byte[4];
                in.readFully(type);
                String typeName = new String(type, StandardCharsets.ISO_8859_1);
                if ("tEXt".equals(typeName)) {
                    byte[] data = new byte[length];
                    in.readFully(data);
                    int zero = -1;
                    for (int i = 0; i < data.length; i++) {
                        if (data[i] == 0) { zero = i; break; }
                    }
                    if (zero > 0) {
                        String key = new String(data, 0, zero, StandardCharsets.ISO_8859_1);
                        if ("WaveScope".equals(key)) {
                            String value = new String(data, zero + 1,
                                    data.length - zero - 1, StandardCharsets.ISO_8859_1);
                            parseMeta(value, info);
                        }
                    }
                } else if ("IDAT".equals(typeName) || "IEND".equals(typeName)) {
                    // Metadata is written before IDAT; no need to scan pixels.
                    return;
                } else {
                    skipFully(in, length);
                }
                // Skip CRC.
                skipFully(in, 4);
            }
        } catch (Exception e) {
            // Fall through to inferred layout; still allow synthesis.
        }
    }

    private static void parseMeta(String value, Info info) {
        String[] parts = value.split(";");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq);
            String v = part.substring(eq + 1);
            info.meta.put(key, v);
            try {
                switch (key) {
                    case "plot_left": info.plotLeft = Integer.parseInt(v); break;
                    case "plot_top": info.plotTop = Integer.parseInt(v); break;
                    case "plot_width": info.plotWidth = Integer.parseInt(v); break;
                    case "plot_height": info.plotHeight = Integer.parseInt(v); break;
                    case "sample_rate": info.sampleRate = Integer.parseInt(v); break;
                    case "fft_size": info.fftSize = Integer.parseInt(v); break;
                    case "channels": info.channels = Integer.parseInt(v); break;
                    case "speed_pps": info.speedPps = Float.parseFloat(v); break;
                    default: break;
                }
            } catch (Exception ignored) {}
        }
        info.hasMetadata = info.plotWidth > 0 && info.plotHeight > 0;
    }

    private static void skipFully(DataInputStream in, int count) throws IOException {
        int left = count;
        while (left > 0) {
            int skipped = in.skipBytes(left);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            left -= skipped;
        }
    }

    public static Bitmap decodePlot(Context context, Uri uri, Info info, int sampleSize)
            throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sampleSize);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开图片");
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("图片解码失败");
        float sx = decoded.getWidth() / (float) info.imageWidth;
        float sy = decoded.getHeight() / (float) info.imageHeight;
        int left = Math.max(0, Math.min(decoded.getWidth() - 1, Math.round(info.plotLeft * sx)));
        int top = Math.max(0, Math.min(decoded.getHeight() - 1, Math.round(info.plotTop * sy)));
        int right = Math.max(left + 1, Math.min(decoded.getWidth(),
                Math.round((info.plotLeft + info.plotWidth) * sx)));
        int bottom = Math.max(top + 1, Math.min(decoded.getHeight(),
                Math.round((info.plotTop + info.plotHeight) * sy)));
        Bitmap crop = Bitmap.createBitmap(decoded, left, top, right - left, bottom - top);
        if (crop != decoded) decoded.recycle();
        return crop;
    }

    public static String describe(Info info) {
        return String.format(Locale.US, "%d × %d · %.1f kHz · FFT %d · 速度 %.1f px/s",
                info.plotWidth, info.plotHeight, info.sampleRate / 1000.0,
                info.fftSize, info.speedPps);
    }
}
