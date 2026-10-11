package com.puremusic

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class App : Application(), ImageLoaderFactory {

    /**
     * 全局图片管线。
     *
     * 这播放器一屏全是封面：歌单 585 首、评论区四五千个头像——默认 ImageLoader 只给
     * 25% 内存 + 250MB 磁盘缓存且每次切换都有淡入动画，快速滚动时会不断解码 + 起新动画。
     * 这里把内存缓存拉满、淡入压到 100ms，滚动明显更稳。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.30)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(160L * 1024 * 1024)
                .build()
        }
        .crossfade(100)
        .build()

    override fun onCreate() {
        super.onCreate()
        // 崩溃兜底：弹 Toast 显示异常，并把完整堆栈写到可访问的文件，便于排查闪退。
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val stack = throwable.stackTraceToString()
            runCatching {
                File(filesDir, "crash.log").writeText(stack)
                getExternalFilesDir(null)?.let { File(it, "crash.log").writeText(stack) }
            }
            runCatching {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "崩溃: ${throwable.javaClass.simpleName}\n${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Thread.sleep(4000)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
