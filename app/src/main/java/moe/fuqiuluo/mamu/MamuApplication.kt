@file:Suppress("KotlinJniMissingFunction")

package moe.fuqiuluo.mamu

import android.app.Application
import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.fuqiuluo.mamu.data.settings.chunkSize
import moe.fuqiuluo.mamu.data.settings.compatibilityMode
import moe.fuqiuluo.mamu.data.settings.memoryAccessMode
import moe.fuqiuluo.mamu.data.settings.memoryBufferSize
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import java.io.File
import kotlin.system.exitProcess

private const val TAG = "MamuApplication"

class MamuApplication : Application() {
    companion object {
        lateinit var instance: MamuApplication
            private set

        init {
            System.loadLibrary("mamu_core")
        }
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 MMKV
        MMKV.initialize(this)

        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            if (throwable.message != null &&
                throwable.message!!.contains("agent.so")
            ) {
                clearCodeCache()
                Log.w(TAG, "FUck Xiaomi!!!!!!!!!!!!!")
            } else {
                Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            }
        }

        if (!initMamuCore()) {
            Log.e(TAG, "Failed to initialize Mamu Core")
            exitProcess(1)
        }

        // 初始化搜索引擎
        val mmkv = MMKV.defaultMMKV()
        val bufferSize = mmkv.memoryBufferSize.toLong() * 1024L * 1024L // MB -> bytes
        val chunkSizeBytes = mmkv.chunkSize.toLong() * 1024L // KB -> bytes
        val cacheDir = cacheDir.absolutePath

        if (!SearchEngine.initSearchEngine(bufferSize, cacheDir, chunkSizeBytes)) {
            Log.e(TAG, "Failed to initialize Search Engine")
            exitProcess(1)
        }

        WuwaDriver.setMemoryAccessMode(mmkv.memoryAccessMode) // 设置内存访问模式，同步到 WuwaDriver
        SearchEngine.setCompatibilityMode(mmkv.compatibilityMode) // 设置兼容模式，同步到 SearchEngine

        Log.d(TAG, "MamuApplication initialized")
    }

    private fun clearCodeCache() {
        val codeCacheDir = File(applicationInfo.dataDir, "code_cache")
        codeCacheDir.deleteRecursively()
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }

    /**
     * 初始化 Mamu Core 库
     * @return 初始化是否成功
     */
    private external fun initMamuCore(): Boolean
}