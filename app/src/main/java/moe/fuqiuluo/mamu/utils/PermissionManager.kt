package moe.fuqiuluo.mamu.utils

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * 应用所需权限配置
 */
object PermissionConfig {
    /**
     * 应用需要通过 pm grant 授予的运行时权限（Android 12 及以下）
     * 注意：这里只包含可以通过 pm grant 授予的权限
     */
    private val LEGACY_STORAGE_PERMISSIONS = listOf(
        "android.permission.READ_EXTERNAL_STORAGE",  // Android 12 及以下
        "android.permission.WRITE_EXTERNAL_STORAGE"  // Android 12 及以下
    )

    /**
     * Android 13+ 的媒体权限和通知权限
     */
    private val MEDIA_PERMISSIONS_API_33 = listOf(
        "android.permission.READ_MEDIA_IMAGES",   // 读取图片
        "android.permission.READ_MEDIA_VIDEO",    // 读取视频
        "android.permission.READ_MEDIA_AUDIO",    // 读取音频
        "android.permission.POST_NOTIFICATIONS"   // 发送通知
    )

    /**
     * 根据 Android 版本获取需要的存储权限
     */
    val REQUIRED_PERMISSIONS: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: 使用细粒度媒体权限
            MEDIA_PERMISSIONS_API_33
        } else {
            // Android 12 及以下: 使用传统存储权限
            LEGACY_STORAGE_PERMISSIONS
        }

    /**
     * 应用需要的 AppOps 权限
     * 这些权限需要通过 appops set 命令授予
     */
    val REQUIRED_APP_OPS = listOf(
        "SYSTEM_ALERT_WINDOW",           // 悬浮窗权限
        "MANAGE_EXTERNAL_STORAGE"        // Android 11+ 所有文件访问权限
    )

    /**
     * 安装时权限（在 AndroidManifest.xml 中声明后自动授予）
     * 这些权限不需要运行时授权，只需要在 manifest 中声明即可
     */
    val INSTALL_TIME_PERMISSIONS = listOf(
        "android.permission.QUERY_ALL_PACKAGES",  // 查询所有应用包名
        "android.permission.INTERNET",            // 网络访问
    )
}

/**
 * 权限管理器
 * 负责授予应用所需的所有权限
 */
object PermissionManager {
    private const val TAG = "PermissionManager"

    /**
     * 检查结果
     */
    data class CheckResult(
        val hasRoot: Boolean,
        val allPermissionsGranted: Boolean,
        val driverInstalled: Boolean,
        val missingPermissions: List<String> = emptyList()
    ) {
        /**
         * 是否所有条件都满足
         */
        val allSatisfied: Boolean
            get() = hasRoot && allPermissionsGranted && driverInstalled
    }

    /**
     * 快速检查所有权限和驱动状态
     * @param context 应用上下文
     * @return 检查结果
     */
    suspend fun quickCheck(context: Context): CheckResult {
        return withContext(Dispatchers.IO) {
            // 检查Root权限
            val hasRoot = checkRootAccess()

            // 检查所有权限
            val missingPermissions = checkMissingPermissions(context)
            val allPermissionsGranted = missingPermissions.isEmpty()

            // 检查驱动
            val driverInstalled = DriverInstaller.isDriverInstalled(context)

            Log.d(
                TAG,
                "Quick check - hasRoot: $hasRoot, allPermissionsGranted: $allPermissionsGranted, driverInstalled: $driverInstalled"
            )

            CheckResult(
                hasRoot = hasRoot,
                allPermissionsGranted = allPermissionsGranted,
                driverInstalled = driverInstalled,
                missingPermissions = missingPermissions
            )
        }
    }

    /**
     * 检查Root权限
     */
    private fun checkRootAccess(): Boolean {
        return try {
            RootPermissionUtils.checkRootAccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access", e)
            false
        }
    }

    /**
     * 检查缺失的权限列表
     */
    private fun checkMissingPermissions(context: Context): List<String> {
        val missingPermissions = mutableListOf<String>()

        // 检查普通权限（pm grant 授予）
        for (permission in PermissionConfig.REQUIRED_PERMISSIONS) {
            if (context.checkPermission(
                    permission,
                    Process.myPid(),
                    Process.myUid()
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missingPermissions.add(permission)
            }
        }

        // 检查 AppOps 权限
        // 1. 悬浮窗权限
        val hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasOverlayPermission) {
            missingPermissions.add("SYSTEM_ALERT_WINDOW")
        }

        // 2. MANAGE_EXTERNAL_STORAGE 权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasManageStorage = android.os.Environment.isExternalStorageManager()
            if (!hasManageStorage) {
                missingPermissions.add("MANAGE_EXTERNAL_STORAGE")
            }
        }

        return missingPermissions
    }

    /**
     * 授予所有必需的权限
     * @param app Application实例
     * @param onProgress 进度回调 (current, total, permissionName)
     * @return Pair<授予成功的数量, 总数量>
     */
    suspend fun grantAllPermissions(
        app: Application,
        onProgress: (Int, Int, String) -> Unit
    ): Pair<Int, Int> {
        var grantedCount = 0
        val totalCount =
            PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size

        RootShellExecutor.withPersistentShell(suCmd = RootConfigManager.getCustomRootCommand()) {
            //Log.d(TAG, "开始自动授权 ${app.packageName}")

            // 授予普通权限
            PermissionConfig.REQUIRED_PERMISSIONS.forEachIndexed { index, permission ->
                //Log.d(TAG, "授权权限: $permission")
                val current = index + 1
                onProgress(current, totalCount, permission)

                val result = grantPermission(this, app.packageName, permission)
                if (result) {
                    grantedCount++
                    Log.d(TAG, "Granted permission: $permission")
                } else {
                    Log.w(TAG, "Failed to grant permission: $permission")
                }
            }

            // 授予AppOps权限
            PermissionConfig.REQUIRED_APP_OPS.forEachIndexed { index, appOp ->
                //Log.d(TAG, "授权 AppOp: $appOp")
                val current = PermissionConfig.REQUIRED_PERMISSIONS.size + index + 1
                onProgress(current, totalCount, appOp)

                val result = grantAppOp(this, app.packageName, appOp)
                if (result) {
                    grantedCount++
                    Log.d(TAG, "Granted AppOp: $appOp")
                } else {
                    Log.w(TAG, "Failed to grant AppOp: $appOp")
                }
            }

            return@withPersistentShell null
        }

        return Pair(grantedCount, totalCount)
    }

    /**
     * 授予单个权限
     */
    private suspend fun grantPermission(
        shell: Shell,
        packageName: String,
        permission: String,
    ): Boolean {
        return withTimeoutOrNull(3.seconds) {
            suspendCancellableCoroutine { continuation ->
                val command = "pm grant $packageName $permission"
                shell.executeAsync(
                    suCmd = RootConfigManager.getCustomRootCommand(),
                    command = command,
                ) { result ->
                    when (result) {
                        is ShellResult.Success -> {
                            Log.d(
                                TAG,
                                "Successfully grantPermission $permission, output: ${result.output}"
                            )
                            continuation.resume(true)
                        }

                        is ShellResult.Error -> {
                            Log.e(
                                TAG,
                                "Failed to grantPermission $permission: ${result.message}, code: ${result.exitCode}"
                            )
                            continuation.resume(false)
                        }

                        is ShellResult.Timeout -> {
                            Log.e(TAG, "Timeout granting permission $permission")
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /**
     * 授予AppOp权限
     */
    private suspend fun grantAppOp(
        shell: Shell,
        packageName: String,
        appOp: String
    ): Boolean {
        return withTimeoutOrNull(3.seconds) {
            suspendCancellableCoroutine { continuation ->
                val command = "appops set $packageName $appOp allow"
                shell.executeAsync(
                    suCmd = RootConfigManager.getCustomRootCommand(),
                    command = command
                ) { result ->
                    when (result) {
                        is ShellResult.Success -> {
                            Log.d(
                                TAG,
                                "Successfully grantAppOp $appOp, output: ${result.output}"
                            )
                            continuation.resume(true)
                        }

                        is ShellResult.Error -> {
                            Log.e(
                                TAG,
                                "Failed to grantAppOp $appOp: ${result.message}, code: ${result.exitCode}"
                            )
                            continuation.resume(false)
                        }

                        is ShellResult.Timeout -> {
                            Log.e(TAG, "Timeout granting AppOp $appOp")
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    continuation.resume(false)
                }
            }
        } ?: false
    }
}