package com.mbientlab.metawear.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a CSV into the app's cache and hands it to the system share sheet
 * (`ACTION_SEND` through the manifest-declared FileProvider).
 */
object CsvShare {

    fun share(context: Context, filename: String, csv: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(csv)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export $filename"))
    }

    /**
     * Share several CSVs in one go (`ACTION_SEND_MULTIPLE`) — one chooser for
     * a whole live-stream buffer instead of one per channel. A single file
     * falls back to [share].
     */
    fun shareAll(context: Context, files: List<Pair<String, String>>) {
        if (files.isEmpty()) return
        if (files.size == 1) return share(context, files[0].first, files[0].second)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        for ((filename, csv) in files) {
            val file = File(dir, filename)
            file.writeText(csv)
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "MetaWear export (${files.size} files)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export ${files.size} CSV files"))
    }
}
