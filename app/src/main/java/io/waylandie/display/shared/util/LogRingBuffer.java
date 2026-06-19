package io.waylandie.display.shared.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * LogRingBuffer — process-wide bounded ring buffer of recent log lines.
 *
 * <p>Holds the last {@link #CAPACITY} lines appended from anywhere in
 * the app. {@link CrashHandler} dumps the snapshot into the crash
 * tombstone so the user can see what happened in the moments before
 * the crash.
 *
 * <p>Thread-safe. Lazily initialized via {@link #init()} from
 * {@code WayLandIEApplication.onCreate}. Calling {@link #append(String)}
 * before {@link #init()} is a no-op (defensive).
 *
 * <p>Capacity matches the prompt's "last 500 lines" requirement.
 */
public final class LogRingBuffer {

    private static final int CAPACITY = 500;

    private static final ArrayDeque<String> buffer = new ArrayDeque<>(CAPACITY);
    private static volatile boolean initialized = false;

    private LogRingBuffer() {}

    public static void init() {
        synchronized (buffer) {
            initialized = true;
        }
    }

    public static void append(String line) {
        if (!initialized || line == null) return;
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) {
                buffer.pollFirst();
            }
            buffer.addLast(line);
        }
    }

    /** Returns a defensive copy of the current buffer contents. */
    public static List<String> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    public static int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    public static void clear() {
        synchronized (buffer) {
            buffer.clear();
        }
    }
}
