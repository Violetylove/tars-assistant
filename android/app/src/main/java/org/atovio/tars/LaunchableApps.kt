package org.atovio.tars

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object LaunchableApps {
    fun installed(context: Context): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(context.packageManager)?.toString()?.trim().orEmpty().ifBlank { packageName }
                LaunchableApp(label, packageName)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<LaunchableApp> { it.label }.thenBy { it.packageName })
    }

    fun selectedInstalled(context: Context): List<LaunchableApp> {
        val selected = RuntimeSettings.allowedLaunchPackages(context)
        return installed(context).filter { it.packageName in selected }
    }

    fun isAllowedAndInstalled(context: Context, packageName: String): Boolean =
        selectedInstalled(context).any { it.packageName == packageName }
}
