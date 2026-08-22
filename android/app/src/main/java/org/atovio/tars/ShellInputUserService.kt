package org.atovio.tars

import android.util.Log

/** Runs in the Shizuku user-service process after the user has authorized this app. */
class ShellInputUserService : IInputService.Stub() {
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean = try {
        val exitCode = ProcessBuilder(
            "/system/bin/input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString(),
        ).start().waitFor()
        if (exitCode != 0) Log.w(TAG, "input swipe exited with code $exitCode")
        exitCode == 0
    } catch (error: Exception) {
        Log.w(TAG, "input swipe process failed", error)
        false
    }

    override fun typeText(text: String): Boolean = try {
        val exitCode = ProcessBuilder("/system/bin/input", "text", text).start().waitFor()
        if (exitCode != 0) Log.w(TAG, "input text exited with code $exitCode")
        else Log.i(TAG, "input text completed")
        exitCode == 0
    } catch (error: Exception) {
        Log.w(TAG, "input text process failed", error)
        false
    }

    override fun destroy() = Unit

    companion object { private const val TAG = "TarsShizuku" }
}
