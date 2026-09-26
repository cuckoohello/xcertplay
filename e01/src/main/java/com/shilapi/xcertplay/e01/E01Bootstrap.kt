package com.shilapi.xcertplay.e01

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File

/**
 * Installs the accessory identity used by [LocalMfiAuthenticationClient].
 *
 * The identity files are packaged under `assets/offline-mfi/` and are copied into
 * `context.noBackupFilesDir/offline-mfi/` on the first run. The directory is created atomically:
 * copy into `offline-mfi-staging`, self-verify, then rename to `offline-mfi`. A failed install
 * removes the staging directory and rethrows so the activity can surface the error.
 *
 * Once installed, subsequent starts do not overwrite the directory: identity rotation requires
 * clearing app data or reinstalling.
 */
internal object E01Bootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        ready = true
    }
}
