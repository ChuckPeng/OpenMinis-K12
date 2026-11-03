package com.openminis.rclone.gomobile;

/** Same shape as the gomobile RPC result. See {@link Gomobile}. */
public final class RcloneRPCResult {
    public final String output;
    public final long status;

    public RcloneRPCResult(String output, long status) {
        this.output = output;
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public long getStatus() {
        return status;
    }
}
