package com.stellar.server;

interface IRemotePtyProcess {
    ParcelFileDescriptor getPtyFd();
    void resize(long size);
    int waitFor();
    void destroy();
}
