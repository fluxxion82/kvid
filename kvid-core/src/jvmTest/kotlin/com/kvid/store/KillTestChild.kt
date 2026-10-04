package com.kvid.store

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Child process for [ProcessKillRecoveryTest]: commits two documents, then stages a third inside an
 * open transaction, signals readiness through a marker file, and waits to be killed.
 */
fun main(args: Array<String>) = runBlocking {
    val store = Kvid.create(args[0])
    store.put("committed 1")
    store.put("committed 2")
    store.transaction {
        put("staged, must vanish")
        File(args[1]).writeText("ready")
        delay(120_000)
    }
}
