package com.kvid.embed

internal actual fun testEnvironment(name: String): String? = System.getenv(name)
