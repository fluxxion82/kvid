package com.kvid.sample

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIViewController

/**
 * The process-wide session for the iOS app. The store lives in the app's Documents directory and is
 * closed when the app enters the background, then reopened on the next use. iOS suspends a background
 * app within seconds, so the close runs inside a background task that ends once the store has closed.
 */
object IosSample {
    private val scope = MainScope()

    val session: StoreSession by lazy {
        StoreSession(documentsPath("notes.kvid"), onCreate = SampleNotes::seed).also { session ->
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue
            ) { _ -> closeInBackground(session) }
        }
    }

    private fun closeInBackground(session: StoreSession) {
        val application = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        fun end() {
            if (task != UIBackgroundTaskInvalid) application.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
        task = application.beginBackgroundTaskWithExpirationHandler { end() }
        scope.launch {
            try {
                session.closeWhenIdle().join()
            } finally {
                end()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun documentsPath(name: String): String {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )
        val path = directory?.path ?: error("no Documents directory")
        return "$path/$name"
    }
}

/** Called from the SwiftUI app in kvid-sample/iosApp. */
fun MainViewController(): UIViewController = ComposeUIViewController { NotesApp(IosSample.session) }
