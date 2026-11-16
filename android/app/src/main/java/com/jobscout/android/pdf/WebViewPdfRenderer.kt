package com.jobscout.android.pdf

import android.content.Context
import android.print.PdfPrintHelper
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebViewPdfRenderer {

    suspend fun renderHtmlToPdf(context: Context, htmlContent: String, outFile: File): File =
        withContext(Dispatchers.Main) {
            outFile.parentFile?.mkdirs()
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = false
                webView.settings.loadsImagesAutomatically = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val attributes = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()

                        val adapter = view.createPrintDocumentAdapter("JobScoutDoc")
                        PdfPrintHelper.print(
                            adapter,
                            attributes,
                            outFile,
                            object : PdfPrintHelper.Callback {
                                override fun onSuccess(file: File) {
                                    webView.destroy()
                                    if (cont.isActive) cont.resume(file)
                                }

                                override fun onFailure(error: String) {
                                    webView.destroy()
                                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
                                }
                            }
                        )
                    }
                }

                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                cont.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }

    fun copyToDownloads(context: Context, sourceFile: File, displayName: String) {
        if (!sourceFile.exists()) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/JobScout"
                    )
                }
                // Remove existing file with same name in JobScout folder if present
                resolver.delete(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(displayName)
                )
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "JobScout"
                )
                dir.mkdirs()
                sourceFile.copyTo(File(dir, displayName), overwrite = true)
            }
        }
    }
}
