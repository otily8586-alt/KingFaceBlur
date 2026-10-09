@file:OptIn(UnstableApi::class)

package com.aliiii.faceblur

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Exporter {

    /** باید روی Main thread صدا زده شود. */
    suspend fun export(
        ctx: Context, uri: Uri, tracks: FaceTracks, s: BlurSettings, out: File,
        onProgress: (Float) -> Unit,
    ) = coroutineScope {
        val mmr = MediaMetadataRetriever()
        var srcBitrate = 8_000_000
        try {
            mmr.setDataSource(ctx, uri)
            srcBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: srcBitrate
        } finally { mmr.release() }
        val target = (srcBitrate * 1.3).toInt().coerceIn(6_000_000, 50_000_000)

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(target)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setEnableFallback(true)
            .build()

        val videoEffects = listOf<Effect>(FaceBlurEffect(tracks, s))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(listOf<AudioProcessor>(), videoEffects))
            .build()

        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(ctx)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(item, out.absolutePath)
        val poll = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(300)
            }
        }
        try {
            done.await()
        } finally {
            poll.cancel()
            if (!done.isCompleted) transformer.cancel()
        }
    }

    suspend fun saveToGallery(ctx: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FaceBlur_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceBlur")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val dst = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("ذخیره در گالری ممکن نشد")
        resolver.openOutputStream(dst)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(dst, values, null, null)
        file.delete()
        dst
    }
}
