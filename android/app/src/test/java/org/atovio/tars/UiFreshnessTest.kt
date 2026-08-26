package org.atovio.tars

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFreshnessTest {
    @Test
    fun launchRejectsChangedTarsTimelineUntilTargetPackageAppears() {
        assertFalse(UiFreshness.isFresh(
            previousUiXml = "<node package=\"org.atovio.tars\" text=\"ready\"/>",
            previousPackage = "org.atovio.tars",
            currentUiXml = "<node package=\"org.atovio.tars\" text=\"launch executed\"/>",
            currentPackage = "org.atovio.tars",
            expectedPackage = "com.tencent.mobileqq",
        ))
    }

    @Test
    fun launchAcceptsTargetPackageTree() {
        assertTrue(UiFreshness.isFresh(
            previousUiXml = "<node package=\"org.atovio.tars\"/>",
            previousPackage = "org.atovio.tars",
            currentUiXml = "<node package=\"com.tencent.mobileqq\" text=\"QQ\"/>",
            currentPackage = "com.tencent.mobileqq",
            expectedPackage = "com.tencent.mobileqq",
        ))
    }

    @Test
    fun ordinaryActionAcceptsChangedTreeInSamePackage() {
        assertTrue(UiFreshness.isFresh(
            previousUiXml = "<node package=\"com.tencent.mobileqq\" text=\"list\"/>",
            previousPackage = "com.tencent.mobileqq",
            currentUiXml = "<node package=\"com.tencent.mobileqq\" text=\"chat\"/>",
            currentPackage = "com.tencent.mobileqq",
        ))
    }
}
