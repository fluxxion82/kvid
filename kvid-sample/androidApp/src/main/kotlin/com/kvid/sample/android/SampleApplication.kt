package com.kvid.sample.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kvid.sample.SampleNotes
import com.kvid.sample.StoreSession
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the process-wide store session, as the store's lifecycle rules recommend. The store is closed
 * whenever the app leaves the foreground, so a process killed in the background leaves a cleanly
 * closed file; the next use reopens it.
 */
class SampleApplication : Application() {
    private val scope = MainScope()

    val session: StoreSession by lazy {
        StoreSession(File(filesDir, "notes.kvid").path, onCreate = SampleNotes::seed)
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                scope.launch { session.closeWhenIdle() }
            }
        })
    }
}
