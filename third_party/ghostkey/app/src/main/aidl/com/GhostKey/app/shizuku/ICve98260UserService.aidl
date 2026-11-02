// ICve98260UserService.aidl
package com.GhostKey.app.shizuku;

import com.GhostKey.app.shizuku.ICve98260Callback;

interface ICve98260UserService {
    void executeExploit(in byte[] profileBlob, String managerPackage, String managerKsudPath,
                        in ICve98260Callback callback);
    String getKernelRelease();
    boolean isShizukuReady();
    void destroy();
}
