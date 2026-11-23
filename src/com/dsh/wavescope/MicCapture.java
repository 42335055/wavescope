package com.dsh.wavescope;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/** Captures the microphone at the highest practical native rate and pushes every sample to the pipeline. */
public final class MicCapture {

    public interface Listener {
        void onStarted(int sampleRate, int channels);

        void onStopped();

        void onError(String message);
    }

    private static final int REQUESTED_RATE = 48000;

    private final AudioPipeline pipeline;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile boolean running;
    private AudioRecord record;
    private Thread thread;
    private int usedRate;
    private int usedChannels;
    private int session;

    public MicCapture(AudioPipeline pipeline, Listener listener) {
        this.pipeline = pipeline;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public int getSampleRate() {
        return usedRate;
    }

    public int getChannels() {
        return usedChannels;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            session++;
            final int mySession = session;
            thread = new Thread(() -> captureLoop(mySession), "WaveScope-Mic");
            thread.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            if (!running && thread == null) {
                return;
            }
            running = false;
            session++;
            AudioRecord r = record;
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
            }
            t = thread;
            thread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void captureLoop(int mySession) {
        AudioRecord rec = null;
        try {
            rec = buildRecord();
            if (rec == null) {
                postError(mySession, "无法初始化麦克风，请检查是否被其他应用占用");
                return;
            }
            record = rec;
            usedRate = rec.getSampleRate();
            usedChannels = rec.getChannelCount();
            if (usedRate <= 0) {
                usedRate = REQUESTED_RATE;
            }
            if (usedChannels <= 0) {
                usedChannels = 1;
            }
            pipeline.configure(usedRate, usedChannels);
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                postError(mySession, "麦克风启动失败，可能被其他应用占用");
                return;
            }
            final int rate = usedRate;
            final int channels = usedChannels;
            main.post(() -> {
                if (mySession == session && running) {
                    listener.onStarted(rate, channels);
                }
            });

            int frameSamples = Math.max(512, usedChannels * 512);
            short[] buffer = new short[frameSamples];
            while (running) {
                int count = rec.read(buffer, 0, buffer.length);
                if (count > 0) {
                    pipeline.append(buffer, 0, count);
                } else if (count == AudioRecord.ERROR_INVALID_OPERATION
                        || count == AudioRecord.ERROR_BAD_VALUE
                        || count == AudioRecord.ERROR_DEAD_OBJECT) {
                    if (mySession == session && running) {
                        postError(mySession, "麦克风采集中断（错误 " + count + "）");
                    }
                    break;
                } else if (count == 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (SecurityException e) {
            postError(mySession, "没有麦克风权限");
        } catch (Exception e) {
            if (mySession == session && running) {
                String msg = e.getMessage();
                postError(mySession, "麦克风采集失败" + (msg == null ? "" : "：" + msg));
            }
        } finally {
            if (mySession == session) {
                running = false;
            }
            if (record == rec) {
                record = null;
            }
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                try {
                    rec.release();
                } catch (Exception ignored) {
                }
            }
            main.post(() -> {
                if (mySession == session) {
                    listener.onStopped();
                }
            });
        }
    }

    private AudioRecord buildRecord() {
        int[] masks = {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};
        for (int mask : masks) {
            int channels = mask == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
            int minBuffer = AudioRecord.getMinBufferSize(REQUESTED_RATE, mask,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                continue;
            }
            int bufferSize = Math.max(minBuffer * 2,
                    REQUESTED_RATE / 10 * channels * 2);
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(REQUESTED_RATE)
                        .setChannelMask(mask)
                        .build();
                AudioRecord rec = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build();
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    return rec;
                }
                rec.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void postError(int mySession, String message) {
        main.post(() -> {
            if (mySession == session) {
                listener.onError(message);
            }
        });
    }
}
