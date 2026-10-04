package com.kvid.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import java.io.File

/** Desktop entry point: `./gradlew :kvid-sample:shared:run`. The store lives in ~/.kvid-sample/notes.kvid. */
fun main() {
    val path = File(System.getProperty("user.home"), ".kvid-sample/notes.kvid").path
    val session = StoreSession(path, onCreate = SampleNotes::seed)
    application {
        Window(
            onCloseRequest = {
                runBlocking { session.closeWhenIdle() }
                exitApplication()
            },
            title = "kvid notes",
            state = rememberWindowState(width = 480.dp, height = 760.dp)
        ) {
            NotesApp(session)
        }
    }
}
