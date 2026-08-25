package org.atovio.tars

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/** High-privilege bridge backed by Shizuku's official UserService API. */
class ShizukuGateway {
    enum class ConnectionState {
        READY,
        SERVICE_UNAVAILABLE,
        AUTHORIZATION_REQUIRED,
    }

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

    fun isAvailable(): Boolean = connectionState() == ConnectionState.READY

    fun connectionState(): ConnectionState = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> ConnectionState.SERVICE_UNAVAILABLE
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ConnectionState.AUTHORIZATION_REQUIRED
            else -> ConnectionState.READY
        }
    } catch (_: Throwable) {
        ConnectionState.SERVICE_UNAVAILABLE
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

    fun typeText(text: String): Boolean {
        if (!isAvailable() || text.isEmpty() || text.length > MAX_TEXT_LENGTH) {
            Log.w(TAG, "Shizuku text input rejected before execution")
            return false
        }
        val service = userService() ?: run {
            Log.w(TAG, "Shizuku UserService binding timed out")
            return false
        }
        return try {
            service.typeText(text).also { success -> Log.i(TAG, "Shizuku text input success=$success") }
        } catch (error: Throwable) {
            Log.w(TAG, "Shizuku UserService text input failed", error)
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
            val deadline = android.os.SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS
            while (remote == null) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
            return remote
        }
    }

    companion object {
        private const val TAG = "TarsShizuku"
        private const val BIND_TIMEOUT_MS = 3_000L
        private const val MAX_TEXT_LENGTH = 2_000
    }
}
