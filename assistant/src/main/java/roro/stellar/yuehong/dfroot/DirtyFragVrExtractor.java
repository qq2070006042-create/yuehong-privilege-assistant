package roro.stellar.yuehong.dfroot;

/** Parses tracepoint.funcs from a boot image or OTA source using the bundled Rust extractor. */
public final class DirtyFragVrExtractor {
    static {
        System.loadLibrary("vrext");
    }

    private DirtyFragVrExtractor() {
    }

    /** Returns a JSON result containing the funcs member offset or an error. */
    public static native String nativeDerive(String input, String workDir, boolean insecure);

    /** Returns download progress as "phase done total". */
    public static native String nativeProgress();
}
