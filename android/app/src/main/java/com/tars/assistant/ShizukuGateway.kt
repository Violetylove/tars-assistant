package com.tars.assistant

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/** High-privilege bridge backed by Shizuku's official UserService API. */
class ShizukuGateway {
    enum class PermissionRequestResult {
        GRANTED,
        REQUESTED,
        RATIONALE_REQUIRED,
        UNAVAILABLE,
    }

    private val lock = Object()
    @Volatile private var remote: IInputService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "Shizuku UserService connected")
            synchronized(lock) { remote = IInputService.Stub.asInterface(service); lock.notifyAll() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Shizuku UserService disconnected")
            synchronized(lock) { remote = null }
        }
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int): PermissionRequestResult {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return PermissionRequestResult.UNAVAILABLE
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return PermissionRequestResult.GRANTED
            if (Shizuku.shouldShowRequestPermissionRationale()) return PermissionRequestResult.RATIONALE_REQUIRED
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            return PermissionRequestResult.UNAVAILABLE
        }
        return PermissionRequestResult.REQUESTED
    }

    fun swipe(action: AgentAction): Boolean {
        val x1 = action.x1?.toInt() ?: return false
        val y1 = action.y1?.toInt() ?: return false
        val x2 = action.x2?.toInt() ?: return false
        val y2 = action.y2?.toInt() ?: return false
        val duration = action.durationMs ?: return false
        if (!isAvailable() || duration !in 1..10_000 || listOf(x1, y1, x2, y2).any { it !in 0..10_000 }) return false

        val service = userService() ?: run {
            Log.w(TAG, "Shizuku UserService binding timed out")
            return false
        }
        return try {
            service.swipe(x1, y1, x2, y2, duration)
        } catch (error: Throwable) {
            Log.w(TAG, "Shizuku UserService swipe failed", error)
            false
        }
    }

    private fun userService(): IInputService? {
        remote?.let { return it }
        synchronized(lock) {
            remote?.let { return it }
            val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellInputUserService::class.java.name))
                .daemon(false)
                .processNameSuffix("tars-input")
                .version(1)
            try {
                Shizuku.bindUserService(args, connection)
            } catch (error: Throwable) {
                Log.w(TAG, "Shizuku UserService bind failed", error)
                return null
            }
            lock.wait(BIND_TIMEOUT_MS)
            return remote
        }
    }

    companion object {
        private const val TAG = "TarsShizuku"
        private const val BIND_TIMEOUT_MS = 3_000L
    }
}
