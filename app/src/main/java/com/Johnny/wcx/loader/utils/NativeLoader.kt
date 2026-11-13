package com.Johnny.wcx.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import com.tencent.mmkv.MMKV
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.createDirsSafe
import java.io.File
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private val nativeLoadLock = Any()
    private var installedNativeLibraryDir: File? = null
    private var nativeLibrariesLoaded = false

    fun init(hostCtx: Context, modulePath: String) {
        synchronized(nativeLoadLock) {
            if (!nativeLibrariesLoaded) {
                val instructionSet = if (Process.is64Bit()) "arm64" else "arm"
                installedNativeLibraryDir = File(File(modulePath).parentFile, "lib/$instructionSet")
                    .takeIf { it.isDirectory }
                if (installedNativeLibraryDir?.resolve("libandroidx.graphics.path.so")?.isFile == true) {
                    loadLibrary("androidx.graphics.path")
                }
                for (name in listOf("dexkit", "wekit_native")) {
                    loadLibrary(name)
                }
                nativeLibrariesLoaded = true
            }
        }
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString(), MMKV.LibLoader { name -> loadLibrary(name) })

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    // Legacy packaging extracts compressed libraries beside the installed APK.
    // A copied APK used by external injectors may still rely on its class loader.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibrary(name: String) = synchronized(nativeLoadLock) {
        val directory = installedNativeLibraryDir
        if (directory == null) {
            System.loadLibrary(name)
        } else {
            val library = File(directory, "lib$name.so")
            require(library.isFile && library.canRead()) { "native library is unavailable: $library" }
            System.load(library.absolutePath)
        }
    }
}
