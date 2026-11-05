package com.itsmebillah.appinternetcontrol

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Loads installed-app icons off the UI thread and reuses them as list rows are recycled. */
class PackageIconLoader(context: Context) : AutoCloseable {
    private val packageManager = context.applicationContext.packageManager
    private val cache = LruCache<String, Drawable>(MAX_CACHED_ICONS)
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingViews = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()

    fun bind(packageName: String, imageView: ImageView) {
        imageView.tag = packageName
        val cached = cache.get(packageName)
        if (cached != null) {
            imageView.setImageDrawable(cached.constantState?.newDrawable(imageView.resources)?.mutate() ?: cached)
            return
        }
        imageView.setImageResource(android.R.drawable.sym_def_app_icon)

        var shouldLoad = false
        val waitingViews = pendingViews.compute(packageName) { _, current ->
            (current ?: CopyOnWriteArrayList<WeakReference<ImageView>>().also { shouldLoad = true })
                .apply { add(WeakReference(imageView)) }
        } ?: return
        if (!shouldLoad) return

        executor.execute {
            val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            if (icon != null) cache.put(packageName, icon)
            val views = pendingViews.remove(packageName).orEmpty()
            mainHandler.post {
                views.forEach { reference ->
                    val target = reference.get()
                    if (target?.tag == packageName) {
                        target.setImageDrawable(icon?.constantState?.newDrawable(target.resources)?.mutate()
                            ?: target.context.getDrawable(android.R.drawable.sym_def_app_icon))
                    }
                }
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
        pendingViews.clear()
    }

    private companion object {
        const val MAX_CACHED_ICONS = 128
    }
}
