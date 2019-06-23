package com.kroegerama.kaiteki.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.Log
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import java.io.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.set

class ImageMagic(
    private val context: Context,
    lifecycle: Lifecycle? = null,
    private val fadeDuration: Int = 500,
    private val maxAge: Long = 5 * 60 * 1000L,
    private val cacheFolder: String = "image_magic",
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val debug: Boolean = false
) : LifecycleObserver, Closeable {

    init {
        lifecycle?.addObserver(this)
    }

    private val singleThreadDispatcher by lazy { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    private val supervisor by lazy { SupervisorJob() }

    private val jobs by lazy { ConcurrentHashMap<String, Job>() }

    private val cacheDir by lazy {
        File(context.cacheDir, cacheFolder).also {
            it.mkdirs()
        }
    }

    private fun getCacheFile(tag: String): File = File(
        cacheDir,
        "img-${ByteString.encodeUtf8(tag).md5().hex()}.dat"
    )

    fun loadImage(
        url: String,
        imageView: ImageView,
        tag: String = url,
        forceUpdate: Boolean = false
    ) {
        jobs.remove(tag)?.let {
            if (debug) Log.d(TAG, "Cancel current job... [$url]")
            it.cancel()
        }

        val imageRef = WeakReference(imageView)
        jobs[tag] = CoroutineScope(Dispatchers.IO + supervisor).launch {
            val cached = getCacheFile(tag)
            if (debug) Log.d(TAG, "Cache file: ${cached.name} (Exists: ${cached.exists()}) [$url]")

            var decodeError = false
            if (cached.exists()) {
                if (debug) Log.d(TAG, "Decoding cached image: ${cached.name} [$url]")

                val bmp: Bitmap? = withContext(singleThreadDispatcher) { BitmapFactory.decodeFile(cached.path) }
                if (bmp == null) {
                    if (debug) Log.d(TAG, "Could not read cached file: ${cached.name} [$url]")
                    decodeError = true
                } else {
                    withContext(Dispatchers.Main) { imageRef.get()?.setImageBitmap(bmp) }
                }
            }

            //lastModified is 0L, if file does not exist
            val delta = System.currentTimeMillis() - withContext(singleThreadDispatcher) { cached.lastModified() }
            if (forceUpdate || decodeError || delta > maxAge) {
                if (debug) Log.d(
                    TAG, "Cached will be updated. Reason: ${cached.name} " +
                            "ForceUpdate: $forceUpdate, " +
                            "DecodeError: $decodeError, " +
                            "Age: ${delta / 1000}s - maxAge: ${maxAge / 1000}s, " +
                            "Reload... [$url]"
                )
                update(url, cached, imageRef)
            }

            jobs.remove(tag)
        }
    }

    private suspend fun update(url: String, cached: File, imageRef: WeakReference<ImageView>) {
        if (debug) Log.d(TAG, "Updating cached file: ${cached.name} [$url]")
        val request = Request.Builder()
            .url(url)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (debug) Log.d(TAG, "Response was ${response.code()}: ${cached.name} [$url]")
                return
            }

            try {
                withContext(singleThreadDispatcher) {
                    response.body()?.byteStream()?.use { networkStream ->
                        FileOutputStream(cached).use { fos ->
                            networkStream.copyTo(fos)
                        }
                    }
                } ?: return
            } catch (c: CancellationException) {
                if (debug) Log.d(TAG, "Download cancelled: ${cached.name} [$url]")
                cached.delete()
                return
            }

            if (debug) Log.d(TAG, "Cache updated: ${cached.name} [$url]")
        } catch (e: IOException) {
            if (debug) Log.d(TAG, "Cache request failed for $url", e)
            return
        }

        val imageView = imageRef.get() ?: return
        val bmp: Bitmap? = withContext(singleThreadDispatcher) { BitmapFactory.decodeFile(cached.path) }

        if (bmp == null) {
            if (debug) Log.d(TAG, "Could not decode updated file: ${cached.name} [$url]")
            return
        }

        withContext(Dispatchers.Main) {
            val oldDrawable = imageView.drawable ?: ColorDrawable(Color.TRANSPARENT)
            val newDrawable = bmp.toDrawable(context.resources)

            val finalDrawable = if (fadeDuration > 0) {
                TransitionDrawable(arrayOf(oldDrawable, newDrawable)).apply {
                    isCrossFadeEnabled = true
                    startTransition(fadeDuration)
                }
            } else {
                newDrawable
            }
            imageView.setImageDrawable(finalDrawable)
        }
    }

    private suspend fun InputStream.copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes

            yield()

            bytes = read(buffer)
        }
        return bytesCopied
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    override fun close() {
        if (debug) Log.d(TAG, "Closing...")
        supervisor.cancel()
        singleThreadDispatcher.close()
    }

    companion object {
        private const val TAG = "ImageMagic"
    }
}