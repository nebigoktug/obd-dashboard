package com.nebi.obdbridge.dash

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Kayıtları paylaşmak için en küçük dosya sağlayıcı (androidx FileProvider bağımlılığı eklememek için).
 * Yalnız cache/share/ altındaki dosyaları, yalnız okuma için verir.
 */
class ShareProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.share"
        fun uriFor(ctx: Context, f: File): Uri = Uri.parse("content://${authority(ctx)}/${Uri.encode(f.name)}")
    }

    private fun file(uri: Uri): File? {
        val dir = File(context!!.cacheDir, "share")
        val f = File(dir, uri.lastPathSegment ?: return null)
        return f.takeIf { it.canonicalPath.startsWith(dir.canonicalPath + File.separator) && it.exists() }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("yalnız okuma")
        val f = file(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun getType(uri: Uri) = when {
        uri.lastPathSegment?.endsWith(".zip") == true -> "application/zip"
        uri.lastPathSegment?.endsWith(".jpg") == true -> "image/jpeg"   // tema şablonu
        else -> "application/octet-stream"
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0
}
