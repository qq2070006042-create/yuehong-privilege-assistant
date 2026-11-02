package roro.stellar

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.stellar.server.IRemotePtyProcess

class StellarPtyProcess(private val remote: IRemotePtyProcess) {

    val ptyFd: ParcelFileDescriptor
        get() = try { remote.ptyFd } catch (e: RemoteException) { throw RuntimeException(e) }

    fun resize(columns: Int, rows: Int) = try {
        remote.resize(columns.toLong() or (rows.toLong() shl 16))
    } catch (e: RemoteException) { throw RuntimeException(e) }

    fun waitFor(): Int = try { remote.waitFor() } catch (e: RemoteException) { throw RuntimeException(e) }

    fun destroy() = try { remote.destroy() } catch (e: RemoteException) { throw RuntimeException(e) }
}
