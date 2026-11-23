package com.dsh.wavescope;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Matroska (MKV) writer for one H.264 video track.  MediaMuxer does not
 * provide an MKV output format, so this writes the EBML/Matroska structures
 * needed by players.  Samples must be in AVCC (4-byte length-prefixed NALU)
 * form and codecPrivate must be an AVCDecoderConfigurationRecord.
 */
public final class MatroskaH264Writer {

    private static final byte[] ID_EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] ID_SEGMENT = {0x18, 0x53, (byte) 0x80, 0x67};
    private static final byte[] ID_INFO = {0x15, 0x49, (byte) 0xA9, 0x66};
    private static final byte[] ID_TRACKS = {0x16, 0x54, (byte) 0xAE, 0x6B};
    private static final byte[] ID_CLUSTER = {0x1F, 0x43, (byte) 0xB6, 0x75};
    private static final byte[] ID_CUES = {0x1C, 0x53, (byte) 0xBB, 0x6B};

    private final CountingOutputStream out;
    private final int fps;
    private final String videoCodecId;
    private boolean finished;
    private long segmentDataStart;
    private final List<long[]> cues = new ArrayList<>();

    public MatroskaH264Writer(OutputStream out, int width, int height, int fps,
                              byte[] codecPrivate, long durationUs) throws IOException {
        this(out, width, height, fps, codecPrivate, durationUs, 0, 0, null,
                "V_MPEG4/ISO/AVC");
    }

    public MatroskaH264Writer(OutputStream out, int width, int height, int fps,
                              byte[] codecPrivate, long durationUs,
                              int audioSampleRate, int audioChannels,
                              byte[] audioCodecPrivate) throws IOException {
        this(out, width, height, fps, codecPrivate, durationUs, audioSampleRate,
                audioChannels, audioCodecPrivate, "V_MPEG4/ISO/AVC");
    }

    public MatroskaH264Writer(OutputStream out, int width, int height, int fps,
                              byte[] codecPrivate, long durationUs,
                              int audioSampleRate, int audioChannels,
                              byte[] audioCodecPrivate, String videoCodecId)
            throws IOException {
        this.out = new CountingOutputStream(out);
        this.fps = Math.max(1, fps);
        this.videoCodecId = videoCodecId == null ? "V_MPEG4/ISO/AVC" : videoCodecId;
        writeHeader(width, height, codecPrivate, durationUs,
                audioSampleRate, audioChannels, audioCodecPrivate);
    }

    private void writeHeader(int width, int height, byte[] codecPrivate,
                             long durationUs, int audioSampleRate,
                             int audioChannels, byte[] audioCodecPrivate) throws IOException {
        byte[] ebml = ebmlHeader();
        writeId(ID_EBML);
        writeSize(ebml.length);
        out.write(ebml);

        writeId(ID_SEGMENT);
        // Unknown-sized segment: 8-byte unknown vint.
        out.write(new byte[]{(byte) 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        segmentDataStart = out.getCount();

        byte[] info = infoElement(durationUs);
        writeId(ID_INFO);
        writeSize(info.length);
        out.write(info);

        byte[] tracks = tracksElement(width, height, codecPrivate,
                audioSampleRate, audioChannels, audioCodecPrivate);
        writeId(ID_TRACKS);
        writeSize(tracks.length);
        out.write(tracks);
        out.flush();
    }

    private byte[] ebmlHeader() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        element(b, new byte[]{0x42, (byte) 0x86}, uintBytes(1)); // EBMLVersion
        element(b, new byte[]{0x42, (byte) 0xF7}, uintBytes(1)); // EBMLReadVersion
        element(b, new byte[]{0x42, (byte) 0xF2}, uintBytes(4)); // EBMLMaxIDLength
        element(b, new byte[]{0x42, (byte) 0xF3}, uintBytes(8)); // EBMLMaxSizeLength
        element(b, new byte[]{0x42, (byte) 0x82},
                "matroska".getBytes(StandardCharsets.US_ASCII)); // DocType
        element(b, new byte[]{0x42, (byte) 0x87}, uintBytes(4)); // DocTypeVersion
        element(b, new byte[]{0x42, (byte) 0x85}, uintBytes(2)); // DocTypeReadVersion
        return b.toByteArray();
    }

    private byte[] infoElement(long durationUs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // 1000 ns per tick = 1 microsecond.
        element(b, new byte[]{0x2A, (byte) 0xD7, (byte) 0xB1}, uintBytes(1000));
        element(b, new byte[]{0x4D, (byte) 0x80},
                "AudioVisualizer".getBytes(StandardCharsets.US_ASCII));
        element(b, new byte[]{0x57, (byte) 0x41},
                "AudioVisualizer".getBytes(StandardCharsets.US_ASCII));
        byte[] duration = new byte[8];
        long bits = Double.doubleToLongBits(durationUs);
        for (int i = 0; i < 8; i++) {
            duration[i] = (byte) ((bits >>> (56 - i * 8)) & 0xFF);
        }
        element(b, new byte[]{0x44, (byte) 0x89}, duration);
        return b.toByteArray();
    }

    private byte[] tracksElement(int width, int height, byte[] codecPrivate,
                                 int audioSampleRate, int audioChannels,
                                 byte[] audioCodecPrivate) throws IOException {
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        element(video, new byte[]{(byte) 0x9A}, uintBytes(0)); // progressive
        element(video, new byte[]{(byte) 0xB0}, uintBytes(width));
        element(video, new byte[]{(byte) 0xBA}, uintBytes(height));

        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        element(entry, new byte[]{(byte) 0xD7}, uintBytes(1)); // TrackNumber
        element(entry, new byte[]{(byte) 0x73, (byte) 0xC5}, uintBytes(1)); // TrackUID
        element(entry, new byte[]{(byte) 0x83}, uintBytes(1)); // TrackType video
        element(entry, new byte[]{(byte) 0x9C}, uintBytes(0)); // FlagLacing
        element(entry, new byte[]{(byte) 0x86},
                videoCodecId.getBytes(StandardCharsets.US_ASCII)); // CodecID
        if (codecPrivate != null && codecPrivate.length > 0) {
            byte[] privateData = "V_MPEG4/ISO/AVC".equals(videoCodecId)
                    ? ensureAvcC(codecPrivate) : codecPrivate;
            element(entry, new byte[]{(byte) 0x63, (byte) 0xA2}, privateData);
        }
        long defaultDurationNs = 1_000_000_000L / Math.max(1, fps);
        element(entry, new byte[]{(byte) 0x23, (byte) 0xE3, (byte) 0x83},
                uintBytes(defaultDurationNs));
        element(entry, new byte[]{(byte) 0xE0}, video.toByteArray());

        ByteArrayOutputStream tracks = new ByteArrayOutputStream();
        element(tracks, new byte[]{(byte) 0xAE}, entry.toByteArray());

        if (audioCodecPrivate != null && audioCodecPrivate.length > 0
                && audioSampleRate > 0 && audioChannels > 0) {
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            element(audio, new byte[]{(byte) 0xB5}, doubleBytes(audioSampleRate));
            element(audio, new byte[]{(byte) 0x9F}, uintBytes(audioChannels));
            element(audio, new byte[]{(byte) 0x62, (byte) 0x64}, uintBytes(16));

            ByteArrayOutputStream audioEntry = new ByteArrayOutputStream();
            element(audioEntry, new byte[]{(byte) 0xD7}, uintBytes(2));
            element(audioEntry, new byte[]{(byte) 0x73, (byte) 0xC5}, uintBytes(2));
            element(audioEntry, new byte[]{(byte) 0x83}, uintBytes(2));
            element(audioEntry, new byte[]{(byte) 0x9C}, uintBytes(0));
            element(audioEntry, new byte[]{(byte) 0x86},
                    "A_AAC".getBytes(StandardCharsets.US_ASCII));
            element(audioEntry, new byte[]{(byte) 0x63, (byte) 0xA2}, audioCodecPrivate);
            long audioDefaultDurationNs = 1_024L * 1_000_000_000L
                    / Math.max(1, audioSampleRate);
            element(audioEntry, new byte[]{(byte) 0x23, (byte) 0xE3, (byte) 0x83},
                    uintBytes(audioDefaultDurationNs));
            element(audioEntry, new byte[]{(byte) 0xE1}, audio.toByteArray());
            element(tracks, new byte[]{(byte) 0xAE}, audioEntry.toByteArray());
        }
        return tracks.toByteArray();
    }

    private int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private byte[] ensureAvcC(byte[] data) {
        if (data == null || data.length == 0) return data;
        if (data[0] == 1) return data; // already AVCDecoderConfigurationRecord
        List<byte[]> spsList = new ArrayList<>();
        List<byte[]> ppsList = new ArrayList<>();
        int i = 0;
        while (i + 3 < data.length) {
            int start;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                start = i + 3;
            } else if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1) {
                start = i + 4;
            } else {
                i++;
                continue;
            }
            int next = findStartCode(data, start);
            int end = next >= 0 ? next : data.length;
            if (end <= start) break;
            int type = data[start] & 0x1F;
            byte[] nal = new byte[end - start];
            System.arraycopy(data, start, nal, 0, nal.length);
            if (type == 7) spsList.add(nal);
            else if (type == 8) ppsList.add(nal);
            i = end;
        }
        if (spsList.isEmpty() || ppsList.isEmpty()) return data;
        byte[] sps = spsList.get(0);
        byte[] pps = ppsList.get(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(sps[1]);
        out.write(sps[2]);
        out.write(sps[3]);
        out.write(0xFF);
        out.write(0xE1);
        out.write((sps.length >> 8) & 0xFF);
        out.write(sps.length & 0xFF);
        out.write(sps, 0, sps.length);
        out.write(1);
        out.write((pps.length >> 8) & 0xFF);
        out.write(pps.length & 0xFF);
        out.write(pps, 0, pps.length);
        return out.toByteArray();
    }

    private byte[] doubleBytes(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) ((bits >>> (56 - i * 8)) & 0xFF);
        return b;
    }

    public void writeSample(byte[] data, long ptsUs, boolean keyFrame) throws IOException {
        writeSample(data, ptsUs, keyFrame, 1);
    }

    public void writeAudioSample(byte[] data, long ptsUs) throws IOException {
        writeSample(data, ptsUs, true, 2);
    }

    private void writeSample(byte[] data, long ptsUs, boolean keyFrame,
                             int trackNumber) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }
        long timeMs = Math.max(0L, ptsUs);
        byte[] timecode = uintBytes(timeMs);
        int timecodeElementSize = 1 + 1 + timecode.length; // ID + size + value
        long blockPayload = 4L + data.length;
        int blockSizeLen = vintLength(blockPayload);
        long clusterSize = timecodeElementSize + 1L + blockSizeLen + blockPayload;
        long clusterPosition = out.getCount() - segmentDataStart;

        writeId(ID_CLUSTER);
        writeSize(clusterSize);
        out.write(0xE7);
        writeSize(timecode.length);
        out.write(timecode);

        out.write(0xA3);
        writeSize(blockPayload);
        out.write(trackNumber == 2 ? 0x82 : 0x81);
        out.write(0);
        out.write(0); // relative timecode
        out.write(keyFrame ? 0x80 : 0x00);
        out.write(data);
        if (trackNumber == 1 && keyFrame) {
            cues.add(new long[]{timeMs, clusterPosition});
        }
    }

    public void finish() throws IOException {
        if (!finished) {
            finished = true;
            writeCues();
            out.flush();
        }
    }

    private void writeCues() throws IOException {
        if (cues.isEmpty()) return;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (long[] cue : cues) {
            ByteArrayOutputStream point = new ByteArrayOutputStream();
            element(point, new byte[]{(byte) 0xB3}, uintBytes(cue[0]));
            ByteArrayOutputStream position = new ByteArrayOutputStream();
            element(position, new byte[]{(byte) 0xF7}, uintBytes(1));
            element(position, new byte[]{(byte) 0xF1}, uintBytes(cue[1]));
            element(point, new byte[]{(byte) 0xB7}, position.toByteArray());
            element(payload, new byte[]{(byte) 0xBB}, point.toByteArray());
        }
        writeId(ID_CUES);
        writeSize(payload.size());
        out.write(payload.toByteArray());
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long getCount() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private void element(ByteArrayOutputStream b, byte[] id, byte[] payload)
            throws IOException {
        b.write(id);
        writeSizeTo(b, payload.length);
        b.write(payload);
    }

    private void writeId(byte[] id) throws IOException {
        out.write(id);
    }

    private void writeSize(long value) throws IOException {
        int len = vintLength(value);
        for (int i = len - 1; i >= 0; i--) {
            int v = (int) ((value >>> (i * 8)) & 0xFF);
            if (i == len - 1) {
                v |= (1 << (8 - len));
            }
            out.write(v);
        }
    }

    private void writeSizeTo(ByteArrayOutputStream b, long value) {
        int len = vintLength(value);
        for (int i = len - 1; i >= 0; i--) {
            int v = (int) ((value >>> (i * 8)) & 0xFF);
            if (i == len - 1) {
                v |= (1 << (8 - len));
            }
            b.write(v);
        }
    }

    private int vintLength(long value) {
        if (value < (1L << 7)) return 1;
        if (value < (1L << 14)) return 2;
        if (value < (1L << 21)) return 3;
        if (value < (1L << 28)) return 4;
        if (value < (1L << 35)) return 5;
        if (value < (1L << 42)) return 6;
        if (value < (1L << 49)) return 7;
        return 8;
    }

    private byte[] uintBytes(long value) {
        if (value < 0) value = 0;
        int len = 1;
        if (value > 0xFFL) len = 2;
        if (value > 0xFFFFL) len = 3;
        if (value > 0xFFFFFFL) len = 4;
        if (value > 0xFFFFFFFFL) len = 8;
        byte[] outBytes = new byte[len];
        for (int i = 0; i < len; i++) {
            outBytes[len - 1 - i] = (byte) ((value >>> (i * 8)) & 0xFF);
        }
        return outBytes;
    }
}
