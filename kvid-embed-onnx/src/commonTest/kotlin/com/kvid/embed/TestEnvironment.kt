package com.kvid.embed

/** A setting of the test run: an environment variable, or an instrumentation argument on Android devices. */
internal expect fun testEnvironment(name: String): String?
