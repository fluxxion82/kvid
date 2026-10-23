package com.kvid.embed

import androidx.test.platform.app.InstrumentationRegistry

/** Device processes have no host environment; values come from instrumentation runner arguments. */
internal actual fun testEnvironment(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
