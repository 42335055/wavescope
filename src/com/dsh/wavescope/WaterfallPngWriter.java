package com.dsh.wavescope;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Streams a scaled RGB PNG without allocating the whole output image.  This is
 * used for very large waterfall exports (the source is the full-file spectrum
 * raster; dimensions above the analysed raster are smoothly interpolated).
 */
public final class WaterfallPngWriter {

    public interface Listener {
        void onProgress(int percent);

        boolean isCanceled();
    }

    private static final byte[] PNG_SIGNATURE = {
            (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private final OutputStream out;
    private final int[] source;
    private final int sourceWidth;
    private final int sourceHeight;
    private final int width;
    private final int height;
    private final Listener listener;
    private final CRC32 crc = new CRC32();
    private final byte[] chunkType = new byte[4];

    public WaterfallPngWriter(OutputStream out, int[] sourcePixels,
                              int sourceWidth, int sourceHeight,
                              int width, int height, Listener listener) {
        this.out = out;
        this.source = sourcePixels;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.width = width;
        this.height = height;
        this.listener = listener;
    }

    public void write() throws IOException {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0
                || source == null || source.length < sourceWidth * sourceHeight) {
            throw new IOException("无效的瀑布图尺寸");
        }
        out.write(PNG_SIGNATURE);

        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // truecolour RGB
        ihdr[10] = 0;  // compression
        ihdr[11] = 0;  // filter
        ihdr[12] = 0;  // no interlace
        writeChunk("IHDR", ihdr, 13);

        IdatChunkStream idat = new IdatChunkStream(out);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        DeflaterOutputStream pngData = new DeflaterOutputStream(idat, deflater, 1 << 15);

        final int maxStep = 1 << 16;
        long stepX = ((long) (sourceWidth - 1) << 16) / Math.max(1, width - 1);
        if (stepX <= 0 && width > 1) {
            stepX = 1;
        }
        int tick = Math.max(1, height / 100);
        long lastProgress = -1L;
        byte[] line = new byte[1 << 16];

        try {
            for (int y = 0; y < height; y++) {
                if (listener != null && listener.isCanceled()) {
                    throw new IOException("导出已取消");
                }
                double sy = height <= 1 ? 0.0 : y * (sourceHeight - 1.0) / (height - 1.0);
                int y0 = (int) sy;
                int y1 = Math.min(sourceHeight - 1, y0 + 1);
                double fy = sy - y0;
                int row0 = y0 * sourceWidth;
                int row1 = y1 * sourceWidth;

                pngData.write(0); // filter type: none
                long fxFixed = 0L;
                int p = 0;
                for (int x = 0; x < width; x++) {
                    int x0 = (int) (fxFixed >> 16);
                    if (x0 >= sourceWidth) {
                        x0 = sourceWidth - 1;
                    }
                    int x1 = Math.min(sourceWidth - 1, x0 + 1);
                    double fx = (fxFixed & 0xFFFF) / 65536.0;
                    fxFixed += stepX;
                    if (fxFixed < 0) {
                        fxFixed = 0;
                    }
                    if (fxFixed > (long) maxStep * Math.max(1, sourceWidth - 1)) {
                        fxFixed = (long) maxStep * Math.max(1, sourceWidth - 1);
                    }

                    int p00 = source[row0 + x0];
                    int p10 = source[row0 + x1];
                    int p01 = source[row1 + x0];
                    int p11 = source[row1 + x1];

                    double topR = ((p00 >> 16) & 0xFF) * (1.0 - fx)
                            + ((p10 >> 16) & 0xFF) * fx;
                    double topG = ((p00 >> 8) & 0xFF) * (1.0 - fx)
                            + ((p10 >> 8) & 0xFF) * fx;
                    double topB = (p00 & 0xFF) * (1.0 - fx) + (p10 & 0xFF) * fx;
                    double botR = ((p01 >> 16) & 0xFF) * (1.0 - fx)
                            + ((p11 >> 16) & 0xFF) * fx;
                    double botG = ((p01 >> 8) & 0xFF) * (1.0 - fx)
                            + ((p11 >> 8) & 0xFF) * fx;
                    double botB = (p01 & 0xFF) * (1.0 - fx) + (p11 & 0xFF) * fx;

                    int r = (int) Math.round(topR * (1.0 - fy) + botR * fy);
                    int g = (int) Math.round(topG * (1.0 - fy) + botG * fy);
                    int b = (int) Math.round(topB * (1.0 - fy) + botB * fy);
                    if (r < 0) r = 0;
                    if (r > 255) r = 255;
                    if (g < 0) g = 0;
                    if (g > 255) g = 255;
                    if (b < 0) b = 0;
                    if (b > 255) b = 255;
                    line[p++] = (byte) r;
                    line[p++] = (byte) g;
                    line[p++] = (byte) b;
                    if (p >= line.length - 3) {
                        pngData.write(line, 0, p);
                        p = 0;
                    }
                }
                if (p > 0) {
                    pngData.write(line, 0, p);
                }

                if (listener != null && (y % tick == 0 || y == height - 1)) {
                    long percent = 100L * (y + 1L) / height;
                    if (percent != lastProgress) {
                        lastProgress = percent;
                        listener.onProgress((int) percent);
                    }
                }
            }

            try {
                pngData.finish();
            } finally {
                pngData.close();
            }
            writeChunk("IEND", new byte[0], 0);
            out.flush();
        } finally {
            deflater.end();
        }
    }

    private void putInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

    private void writeChunk(String type, byte[] data, int length) throws IOException {
        if (type.length() != 4) {
            throw new IOException("无效的 PNG chunk");
        }
        for (int i = 0; i < 4; i++) {
            chunkType[i] = (byte) type.charAt(i);
        }
        writeInt(out, length);
        out.write(chunkType);
        if (length > 0) {
            out.write(data, 0, length);
        }
        crc.reset();
        crc.update(chunkType, 0, 4);
        if (length > 0) {
            crc.update(data, 0, length);
        }
        writeInt(out, (int) crc.getValue());
    }

    private void writeInt(OutputStream stream, int value) throws IOException {
        stream.write((value >>> 24) & 0xFF);
        stream.write((value >>> 16) & 0xFF);
        stream.write((value >>> 8) & 0xFF);
        stream.write(value & 0xFF);
    }

    private final class IdatChunkStream extends OutputStream {
        private final OutputStream target;
        private final byte[] buffer = new byte[1 << 18];
        private int used;

        IdatChunkStream(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            if (used >= buffer.length) {
                flushChunk();
            }
            buffer[used++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (used >= buffer.length) {
                    flushChunk();
                }
                int count = Math.min(len, buffer.length - used);
                System.arraycopy(b, off, buffer, used, count);
                used += count;
                off += count;
                len -= count;
            }
        }

        private void flushChunk() throws IOException {
            if (used > 0) {
                writeChunk("IDAT", buffer, used);
                used = 0;
            }
        }

        @Override
        public void flush() throws IOException {
            // IDAT chunks may not be emitted by a plain flush; keeping the
            // compressed data buffered is fine.
        }

        @Override
        public void close() throws IOException {
            flushChunk();
        }
    }
}
