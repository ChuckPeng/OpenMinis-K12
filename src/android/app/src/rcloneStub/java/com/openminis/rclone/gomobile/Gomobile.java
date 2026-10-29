package com.openminis.rclone.gomobile;

/**
 * Compile stand-in for the gomobile archive produced by
 * {@code deps/build_rclone_android.sh}. That archive is not stored in git.
 * When {@code app/libs/rclone.aar} is present, this source set is not compiled.
 * These methods do not talk to rclone or Tailscale.
 */
public final class Gomobile {
    private static final String UNAVAILABLE = "{\"ok\":false,\"error\":\"rclone library not linked\"}";

    private Gomobile() {}

    public static void rcloneInitialize() {}

    public static void rcloneFinalize() {}

    public static RcloneRPCResult rcloneRPC(String method, String input) {
        return new RcloneRPCResult(UNAVAILABLE, 503);
    }

    public static String rcloneSetSocks(String socks) {
        return UNAVAILABLE;
    }

    public static void tsSetLogger(LogSink sink) {}

    public static void tsSetDefaultRouteInterface(String ifName) {}

    public static String tsSetInterfaces(String interfacesJSON) {
        return UNAVAILABLE;
    }

    public static void tsNetworkChanged() {}

    public static String tsStart(String authKey, String hostname, String stateDir) {
        return UNAVAILABLE;
    }

    public static String tsSetAcceptRoutes(boolean on) {
        return UNAVAILABLE;
    }

    public static String tsSetExitNode(String ip) {
        return UNAVAILABLE;
    }

    public static String tsStop() {
        return UNAVAILABLE;
    }

    public static String tsLogout() {
        return UNAVAILABLE;
    }

    public static String tsStatus() {
        return UNAVAILABLE;
    }

    public static String tsRoutes() {
        return UNAVAILABLE;
    }

    public static String tsDialTest(String addr, long timeoutMs) {
        return UNAVAILABLE;
    }

    public static String netConfigure(String configJSON) {
        return UNAVAILABLE;
    }

    public static String netStop() {
        return UNAVAILABLE;
    }

    public static String netStatus() {
        return UNAVAILABLE;
    }

    public static String netHookRoutes(String tag, String localJSON) {
        return UNAVAILABLE;
    }

    public static String netHookRoutes2(String tag, String localJSON, String resolversJSON) {
        return UNAVAILABLE;
    }

    public static String netShellProbe(String resolver, String namesJSON, long timeoutMs) {
        return UNAVAILABLE;
    }

    public static String netXparentStart(String dir, String tag) {
        return UNAVAILABLE;
    }

    public static String netXparentStop() {
        return UNAVAILABLE;
    }

    public static String netXparentStatus() {
        return UNAVAILABLE;
    }

    public static String netDecide(String tag, String host) {
        return UNAVAILABLE;
    }

    public static String netProbe(String tag, String addr, long timeoutMs) {
        return UNAVAILABLE;
    }

    public static String netTestUpstream(String upstreamJSON, String target, long timeoutMs) {
        return UNAVAILABLE;
    }

    public static String netHealth() {
        return UNAVAILABLE;
    }

    public static String netSelfTest(long timeoutMs) {
        return UNAVAILABLE;
    }

    public static String netDiagnose(long timeoutMs) {
        return UNAVAILABLE;
    }
}
