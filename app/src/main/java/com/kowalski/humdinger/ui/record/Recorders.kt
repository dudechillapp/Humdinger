package com.kowalski.humdinger.ui.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** Records microphone audio to an .m4a file. */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    fun start(output: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(output.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    /** Returns false if the recording was too short to be valid. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // happens when stop() is called almost immediately after start()
        } finally {
            r.release()
        }
    }
}

/** Front-camera preview + video recording with CameraX. */
class VideoRecorder(private val context: Context) {
    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
        )
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null

    fun bind(lifecycleOwner: LifecycleOwner, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
            } catch (e: Exception) {
                onError("Couldn't start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        recording?.stop()
        recording = null
        provider?.unbindAll()
    }

    /** Only call after RECORD_AUDIO permission is granted (the screen checks this first). */
    @SuppressLint("MissingPermission")
    fun start(output: File, withSound: Boolean, onFinished: (File?) -> Unit) {
        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(output).build())
        val ready = if (withSound) pending.withAudioEnabled() else pending
        recording = ready.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                onFinished(if (output.exists() && output.length() > 0) output else null)
            }
        }
    }

    fun stop() {
        recording?.stop()
    }
}
