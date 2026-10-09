package com.aliiii.faceblur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

object Detector {
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite"
    private const val STEP_US = 100_000L   // هر ۰.۱ ثانیه یک بار تشخیص
    private const val MAX_SIDE = 960

    private fun modelFile(ctx: Context) = File(ctx.filesDir, "blaze_face_short_range.tflite")

    suspend fun ensureModel(ctx: Context): File = withContext(Dispatchers.IO) {
        val f = modelFile(ctx)
        if (f.exists() && f.length() > 100_000) return@withContext f
        val tmp = File(f.path + ".tmp")
        URL(MODEL_URL).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        tmp.renameTo(f)
        f
    }

    suspend fun scan(
        ctx: Context, uri: Uri, highAccuracy: Boolean, onProgress: (Float) -> Unit,
    ): FaceTracks = withContext(Dispatchers.Default) {
        val bytes = modelFile(ctx).readBytes()
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes); buf.rewind()
        val opts = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.35f)
            .build()
        val det = FaceDetector.createFromOptions(ctx, opts)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val durUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            var vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            var vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = vw; vw = vh; vh = x }
            val sc = MAX_SIDE.toFloat() / maxOf(vw, vh)
            val tw = (vw * sc).toInt().coerceAtLeast(64)
            val th = (vh * sc).toInt().coerceAtLeast(64)

            val regions = ArrayList<FloatArray>()
            regions.add(floatArrayOf(0f, 0f, 1f, 1f))
            if (highAccuracy) {
                regions.add(floatArrayOf(0f, 0f, .6f, .6f)); regions.add(floatArrayOf(.4f, 0f, 1f, .6f))
                regions.add(floatArrayOf(0f, .4f, .6f, 1f)); regions.add(floatArrayOf(.4f, .4f, 1f, 1f))
            }

            val samples = ArrayList<Pair<Long, List<RectF>>>()
            var t = 0L
            while (t <= durUs) {
                coroutineContext.ensureActive()
                var frame: Bitmap? = try {
                    mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
                } catch (e: Exception) { null }
                if (frame == null) frame = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    if (frame.config != Bitmap.Config.ARGB_8888) frame = frame.copy(Bitmap.Config.ARGB_8888, false)
                    samples.add(t to detectFrame(det, frame!!, regions))
                }
                onProgress(if (durUs > 0) (t.toFloat() / durUs).coerceIn(0f, 1f) else 1f)
                t += STEP_US
            }
            Tracker.build(samples)
        } finally {
            det.close()
            mmr.release()
        }
    }

    private fun detectFrame(det: FaceDetector, bmp: Bitmap, regions: List<FloatArray>): List<RectF> {
        val found = ArrayList<Pair<Float, RectF>>()
        val W = bmp.width; val H = bmp.height
        for (r in regions) {
            val x0 = (r[0] * W).toInt(); val y0 = (r[1] * H).toInt()
            val x1 = (r[2] * W).toInt(); val y1 = (r[3] * H).toInt()
            val cw = (x1 - x0).coerceAtLeast(1); val ch = (y1 - y0).coerceAtLeast(1)
            val crop = if (cw == W && ch == H) bmp else Bitmap.createBitmap(bmp, x0, y0, cw, ch)
            val res = det.detect(BitmapImageBuilder(crop).build())
            for (d in res.detections()) {
                val bb = d.boundingBox()
                val score = d.categories().firstOrNull()?.score() ?: 0f
                val rect = RectF(
                    ((x0 + bb.left) / W).coerceIn(0f, 1f), ((y0 + bb.top) / H).coerceIn(0f, 1f),
                    ((x0 + bb.right) / W).coerceIn(0f, 1f), ((y0 + bb.bottom) / H).coerceIn(0f, 1f),
                )
                if (rect.width() > 0.005f && rect.height() > 0.005f) found.add(score to rect)
            }
            if (crop !== bmp) crop.recycle()
        }
        // NMS
        found.sortByDescending { it.first }
        val keep = ArrayList<RectF>()
        for ((_, rc) in found) {
            if (keep.none { Tracker.iou(it, rc) > 0.3f || Tracker.overlapMin(it, rc) > 0.7f }) keep.add(rc)
        }
        return keep
    }
}
