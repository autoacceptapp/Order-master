package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testParseVersionCode_allFormats() {
    assertEquals(12, GitHubUpdateManager.parseVersionCode("debug-apk-build-12-1"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("debug-apk-build-12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("v1.0.12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("1.0.12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("build-12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("v12"))
    assertEquals(12, GitHubUpdateManager.parseVersionCode("unknown-tag", "Order Master v1.0.12 (Build #12)"))
    assertEquals(15, GitHubUpdateManager.parseVersionCode("unknown-tag", "Debug APK Build #15"))
  }

  @Test
  fun testVersionComparison_preventsInfiniteUpdateLoop() {
    // Current app has versionCode 12 (installed from build 12)
    val currentVersionCode = 12
    val currentVersionName = "1.0.12"

    // Remote release on GitHub is from build 12 (debug-apk-build-12-1)
    val isNewerSameVersion = GitHubUpdateManager.isVersionNewer(
      remoteVersion = "debug-apk-build-12-1",
      currentVersion = currentVersionName,
      currentVersionCode = currentVersionCode
    )
    // Must be false so it doesn't continuously prompt
    assertFalse("Installed app matching remote release must not trigger update prompt", isNewerSameVersion)

    // Newer remote release (build 13)
    val isNewerBuild13 = GitHubUpdateManager.isVersionNewer(
      remoteVersion = "debug-apk-build-13-1",
      currentVersion = currentVersionName,
      currentVersionCode = currentVersionCode
    )
    assertTrue("Higher versionCode must trigger update prompt", isNewerBuild13)

    // Older remote release (build 11)
    val isNewerBuild11 = GitHubUpdateManager.isVersionNewer(
      remoteVersion = "debug-apk-build-11-1",
      currentVersion = currentVersionName,
      currentVersionCode = currentVersionCode
    )
    assertFalse("Lower versionCode must not trigger update prompt", isNewerBuild11)
  }

  @Test
  fun testAndroid13Check() {
    // PermissionUtils.isAndroid13OrHigher checks SDK_INT >= 33 (TIRAMISU)
    // On JVM test environment Build.VERSION.SDK_INT is accessible
    val isA13 = PermissionUtils.isAndroid13OrHigher()
    // Verify call completes without exception
    assertNotNull(isA13)
  }
}
