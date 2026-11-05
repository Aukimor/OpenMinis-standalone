package com.openminis.app.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.openminis.app.logging.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device offline Chinese + Latin OCR and accessibility tree quality
 * evaluator ported from Mika's `PhoneOcr.kt` and `TreeQuality.kt`.
 */
object LocalScreenOcr {
    private const val TAG = "LocalScreenOcr"

    data class OcrTextBlock(
        val text: String,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int,
    )

    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Synchronously runs ML Kit Chinese+Latin OCR on [bitmap].
     */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 3_000L): List<OcrTextBlock> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val latch = CountDownLatch(1)
        val outRef = AtomicReference<List<OcrTextBlock>>(emptyList())
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            chineseRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val items = ArrayList<OcrTextBlock>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val txt = line.text.trim()
                            val box = line.boundingBox
                            if (txt.isNotEmpty() && box != null) {
                                items.add(
                                    OcrTextBlock(
                                        text = txt,
                                        bounds = box,
                                        centerX = box.centerX(),
                                        centerY = box.centerY(),
                                    )
                                )
                            }
                        }
                    }
                    items.sortWith(compareBy<OcrTextBlock> { it.bounds.top / 24 }.thenBy { it.bounds.left })
                    outRef.set(items)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    AppLogger.warning(TAG, "ML Kit OCR failed: ${e.message}")
                    latch.countDown()
                }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "recognize error: ${t.message}")
        }
        return outRef.get()
    }

    /**
     * Finds OCR text lines matching [query] (supports `|`-separated alternatives).
     */
    fun findMatching(bitmap: Bitmap, query: String, contains: Boolean = true): List<OcrTextBlock> {
        val candidates = query.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return emptyList()
        val all = recognize(bitmap)
        return all.filter { block ->
            candidates.any { cand ->
                if (contains) block.text.contains(cand, ignoreCase = true)
                else block.text.equals(cand, ignoreCase = true)
            }
        }
    }

    /**
     * Evaluates whether the accessibility node tree is `GOOD`, `PARTIAL`, or `UNUSABLE`
     * (ported from Mika's `TreeQuality.kt`).
     */
    fun assessTreeQuality(
        roots: List<AccessibilityNodeInfo>,
        screenW: Int = 1080,
        screenH: Int = 2400,
    ): String {
        if (roots.isEmpty()) return "UNUSABLE"
        var visibleCount = 0
        var labeledCount = 0
        var clickableCount = 0
        var unlabeledClickableCount = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25) return
            if (node.isVisibleToUser) {
                visibleCount++
                val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                if (hasLabel) labeledCount++
                if (node.isClickable) {
                    clickableCount++
                    if (!hasLabel) unlabeledClickableCount++
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        for (r in roots) walk(r, 0)
        if (visibleCount < 3 || labeledCount == 0) return "UNUSABLE"
        if (clickableCount >= 4 && unlabeledClickableCount * 2 >= clickableCount) return "PARTIAL"
        return "GOOD"
    }
}
