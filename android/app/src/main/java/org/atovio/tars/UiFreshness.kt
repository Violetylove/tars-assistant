package org.atovio.tars

/** Defines the evidence required before the next Agent observation may begin. */
object UiFreshness {
    fun isFresh(
        previousUiXml: String,
        previousPackage: String?,
        currentUiXml: String,
        currentPackage: String?,
        expectedPackage: String? = null,
    ): Boolean {
        val treePopulated = currentUiXml.isNotBlank()
        val xmlMatchesForeground = currentPackage != null &&
            currentUiXml.contains("package=\"$currentPackage\"")
        if (!treePopulated || !xmlMatchesForeground) return false
        return if (expectedPackage != null) {
            currentPackage == expectedPackage
        } else {
            currentPackage != previousPackage || currentUiXml != previousUiXml
        }
    }
}
