package com.openminis.rclone.gomobile;

/** Same shape as the gomobile LogSink. See {@link Gomobile}. */
public interface LogSink {
    void line(String line);
}
