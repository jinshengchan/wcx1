package com.Johnny.wcx.utils.reflection

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import kotlin.time.Duration.Companion.seconds

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstructor get() = getConstructorInstance(ClassLoaders.HOST)

private object DexKitHolder {

    private val IDLE_TIMEOUT = 30.seconds

    // SupervisorJob so a cancelled timer job never takes down the whole scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var bridge: DexKitBridge? = null
    private var idleJob: Job? = null

    private var users = 0
    private var idleGeneration = 0L

    private fun scheduleCloseLocked() {
        idleJob?.cancel()
        val ticket = ++idleGeneration
        if (users != 0) return
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT)
            mutex.withLock {
                if (users == 0 && ticket == idleGeneration) {
                    bridge?.close()
                    bridge = null
                    idleJob = null
                }
            }
        }
    }

    fun get(): DexKitBridge = runBlocking {
        mutex.withLock {
            val current = bridge ?: DexKitBridge.create(HostInfo.appInfo.sourceDir).also { bridge = it }
            scheduleCloseLocked()
            current
        }
    }

    fun acquire(): DexKitBridge = runBlocking {
        mutex.withLock {
            val current = bridge ?: DexKitBridge.create(HostInfo.appInfo.sourceDir).also { bridge = it }
            idleJob?.cancel()
            idleGeneration++
            users++
            current
        }
    }

    fun release() = runBlocking {
        mutex.withLock {
            check(users > 0)
            users--
            if (users == 0) scheduleCloseLocked()
        }
    }
}

/** Protect the whole scan, including suspensions, from the shared holder's idle close. */
suspend fun <T> withDexKit(block: suspend (DexKitBridge) -> T): T {
    val bridge = DexKitHolder.acquire()
    try {
        return block(bridge)
    } finally {
        DexKitHolder.release()
    }
}

val DexKit: DexKitBridge get() = DexKitHolder.get()
