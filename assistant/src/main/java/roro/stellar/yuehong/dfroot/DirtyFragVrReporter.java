package roro.stellar.yuehong.dfroot;

/** Receives line-oriented output from the DirtyFrag VR native chain. */
public interface DirtyFragVrReporter {
    void report(String message);
}
