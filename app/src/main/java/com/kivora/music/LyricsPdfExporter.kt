package com.kivora.music

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Creates a paginated PDF containing user-provided song lyrics. */
internal object LyricsPdfExporter {

    fun write(output: OutputStream, title: String, text: String) {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 20f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.BOLD
            )
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
        }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null
        var y = 0f

        fun startPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            )
            canvas = page?.canvas
            canvas?.drawText(title.take(52), 48f, 48f, titlePaint)
            canvas?.drawText("A1 Family · Lyrics", 48f, 68f, bodyPaint)
            y = 100f
        }

        try {
            startPage()
            val maxWidth = 499f
            text.split("\n").forEach { rawLine ->
                val words = rawLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    y += 18f
                    if (y > 790f) startPage()
                } else {
                    var line = ""
                    words.forEach { word ->
                        val candidate = if (line.isEmpty()) word else "$line $word"
                        if (bodyPaint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                            if (y > 790f) startPage()
                            canvas?.drawText(line, 48f, y, bodyPaint)
                            y += 18f
                            line = word
                        } else {
                            line = candidate
                        }
                    }
                    if (y > 790f) startPage()
                    canvas?.drawText(line, 48f, y, bodyPaint)
                    y += 18f
                }
            }
            page?.let { document.finishPage(it) }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
