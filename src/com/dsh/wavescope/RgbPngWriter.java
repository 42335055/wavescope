package com.dsh.wavescope;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Streams an RGB PNG one row at a time. */
public final class RgbPngWriter {

    public interface RowSource {
        void readRow(int y, byte[] rgb) throws IOException;
    }

    public interface Listener {
        void onProgress(int percent);

        boolean isCanceled();
    }

    private static final byte[] PNG_SIGNATURE = {
            (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private final OutputStream out;
    private final int width;
    private final int height;
    private final RowSource rows;
    private final Listener listener;
    private final String textMetadata;
    private final CRC32 crc = new CRC32();
    private final byte[] chunkType = new byte[4];

    public RgbPngWriter(OutputStream out, int width, int height,
                        RowSource rows, Listener listener) {
        this(out, width, height, rows, listener, null);
    }

    public RgbPngWriter(OutputStream out, int width, int height,
                        RowSource rows, Listener listener, String textMetadata) {
        this.out = out;
        this.width = width;
        this.height = height;
        this.rows = rows;
        this.listener = listener;
        this.textMetadata = textMetadata;
    }

    public void write() throws IOException {
        if (width <= 0 || height <= 0 || rows == null) {
            throw new IOException("无效的 PNG 尺寸");
        }
        out.write(PNG_SIGNATURE);
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;
        ihdr[9] = 2;
        ihdr[10] = 0;
        ihdr[11] = 0;
        ihdr[12] = 0;
        writeChunk("IHDR", ihdr, 13);
        if (textMetadata != null && textMetadata.length() > 0) {
            byte[] key = "WaveScope".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] value = textMetadata.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] data = new byte[key.length + 1 + value.length];
            System.arraycopy(key, 0, data, 0, key.length);
            data[key.length] = 0;
            System.arraycopy(value, 0, data, key.length + 1, value.length);
            writeChunk("tEXt", data, data.length);
        }

        IdatChunkStream idat = new IdatChunkStream(out);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        DeflaterOutputStream pngData = new DeflaterOutputStream(idat, deflater, 1 << 15);
        byte[] row = new byte[width * 3];
        int tick = Math.max(1, height / 100);
        long lastProgress = -1L;
        try {
            for (int y = 0; y < height; y++) {
                if (listener != null && listener.isCanceled()) {
                    throw new IOException("导出已取消");
                }
                rows.readRow(y, row);
                pngData.write(0);
                pngData.write(row);
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
        for (int i = 0; i < 4; i++) {
            chunkType[i] = (byte) type.charAt(i);
        }
        writeInt(length);
        out.write(chunkType);
        if (length > 0) {
            out.write(data, 0, length);
        }
        crc.reset();
        crc.update(chunkType, 0, 4);
        if (length > 0) {
            crc.update(data, 0, length);
        }
        writeInt((int) crc.getValue());
    }

    private void writeInt(int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
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
        public void close() throws IOException {
            flushChunk();
        }
    }
}
