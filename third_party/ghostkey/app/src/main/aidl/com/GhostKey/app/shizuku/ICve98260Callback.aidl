// ICve98260Callback.aidl
package com.GhostKey.app.shizuku;

interface ICve98260Callback {
    void onLog(String message);
    void onProgress(int stage, String status);
    void onComplete(int exitCode);
}
