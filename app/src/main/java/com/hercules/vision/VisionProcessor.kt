package com.hercules.vision

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Samples a MediaProjection RGBA_8888 frame.
 * Only red-like pixels are candidates; friendly green HUD symbols are ignored.
 * All processing stays on the device. No game memory, packets or input are accessed.
 */
object VisionProcessor {
    data class Marker(val x: Float, val y: Float, val size: Float, val samples: Int)

    fun findRedMarkers(image: Image, sensitivity: Int): List<Marker> {
        val plane = image.planes.firstOrNull() ?: return emptyList()
        val width = image.width
        val height = image.height
        if (width < 100 || height < 100 || plane.pixelStride < 3) return emptyList()
        val stride = 4
        val cols = (width + stride - 1) / stride
        val rows = (height + stride - 1) / stride
        val total = cols * rows
        val mask = BooleanArray(total)
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // Ignore the red mission HUD and weapon HUD around the edges.
        val startX = cols / 12
        val endX = cols - startX
        val startY = rows / 9
        val endY = rows * 79 / 100
        val threshold = 155 - sensitivity.coerceIn(0, 100) / 3
        for (gy in startY until endY) {
            val py = gy * stride
            val line = py * rowStride
            for (gx in startX until endX) {
                val px = gx * stride
                val pos = line + px * pixelStride
                if (pos + 2 >= buffer.limit()) continue
                val r = buffer.get(pos).toInt() and 255
                val g = buffer.get(pos + 1).toInt() and 255
                val b = buffer.get(pos + 2).toInt() and 255
                if (r >= threshold && r > g + 57 && r > b + 48 &&
                    r > g * 1.40f && r > b * 1.28f) {
                    mask[gy * cols + gx] = true
                }
            }
        }

        val candidates = ArrayList<Marker>()
        for (gy in startY until endY) {
            for (gx in startX until endX) {
                val root = gy * cols + gx
                if (!mask[root] || visited[root]) continue
                var head = 0
                var tail = 1
                queue[0] = root
                visited[root] = true
                var count = 0
                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var sumX = 0L
                var sumY = 0L
                while (head < tail) {
                    val idx = queue[head++]
                    val x = idx % cols
                    val y = idx / cols
                    count++
                    sumX += x.toLong()
                    sumY += y.toLong()
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < startX || nx >= endX || ny < startY || ny >= endY) continue
                        val ni = ny * cols + nx
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true
                            queue[tail++] = ni
                        }
                    }
                }
                val spanX = maxX - minX + 1
                val spanY = maxY - minY + 1
                val aspect = max(spanX, spanY).toFloat() / max(1, min(spanX, spanY))
                if (count in 2..125 && spanX <= 20 && spanY <= 20 && aspect < 3.1f) {
                    val centerX = (sumX.toFloat() / count + .5f) * stride
                    val centerY = (sumY.toFloat() / count + .5f) * stride
                    candidates.add(Marker(centerX, centerY,
                        max(spanX, spanY).toFloat() * stride, count))
                }
            }
        }
        return candidates.sortedByDescending { it.samples }.take(30)
    }
}
