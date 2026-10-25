package com.kvid.embed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun testEnvironment(name: String): String? = getenv(name)?.toKString()
