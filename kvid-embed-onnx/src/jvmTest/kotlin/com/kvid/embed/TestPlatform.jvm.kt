package com.kvid.embed

import java.io.File

// The JVM side of the helpers jvmAndAndroidTest uses; androidDeviceTest has its own with the same names.

/** The fixture file at [path], or null when there is none. */
internal fun fixtureFile(path: String): File? = File(path).takeIf { it.isFile }

/** Names the platform in `[kvid-embedding]` lines. */
internal val testPlatform: String =
    "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}/java-${System.getProperty("java.version")}".replace(' ', '_')

/** Proportional set size in KiB; not measured on the JVM. */
internal fun pssKb(): Long? = null
