package com.kvid.embed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIDevice
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration
import kotlin.time.DurationUnit

// The iOS side of the helpers in commonTest's TestSupport.kt. The simulator reads host paths, so the
// fixture directories are used where the host wrote them.

internal actual fun fixtureFile(path: String): Path? =
    Path(path).takeIf { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }

/** `ios-<version>/<model>/<arch>`; on the simulator the model is the simulated device's identifier. */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual val testPlatform: String = run {
    val device = UIDevice.currentDevice
    val model = getenv("SIMULATOR_MODEL_IDENTIFIER")?.toKString()?.let { "simulator-$it" } ?: device.model
    "ios-${device.systemVersion}/$model/${Platform.cpuArchitecture.name.lowercase()}".replace(' ', '_')
}

internal actual val memoryKind: String = "footprint"

/** This task's `phys_footprint` (what iOS counts against an app's memory limit) in KiB, from `task_info(TASK_VM_INFO)`. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun memoryKb(): Long? = memScoped {
    val info = alloc<task_vm_info_data_t>()
    val count = alloc<mach_msg_type_number_tVar>()
    count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).toUInt()
    val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
    if (result != KERN_SUCCESS) null else (info.phys_footprint / 1024u).toLong()
}

internal actual class TestLatch actual constructor(count: Int) {
    private val condition = NSCondition()
    private var remaining = count

    actual fun countDown() {
        condition.lock()
        try {
            if (remaining > 0) remaining--
            if (remaining == 0) condition.broadcast()
        } finally {
            condition.unlock()
        }
    }

    actual fun await(timeout: Duration): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeout.toDouble(DurationUnit.SECONDS))
        condition.lock()
        try {
            while (remaining > 0) {
                if (!condition.waitUntilDate(deadline)) return remaining == 0
            }
            return true
        } finally {
            condition.unlock()
        }
    }
}
