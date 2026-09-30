package vn.lienson.acesport.g2probe.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetCopier {
    @Throws(IOException::class)
    fun copyTree(context: Context, assetPath: String, destination: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyFile(context, assetPath, destination)
            return
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Cannot create $destination")
        }
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(destination, child))
        }
    }

    @Throws(IOException::class)
    fun copyFile(context: Context, assetPath: String, destination: File) {
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create $parent")
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
