package com.aliiii.faceblur

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** mode: 0 بلور، 1 موزاییک، 2 شیشه مات، 3 پارازیت، 4 مشکی
 *  shape: 0 بیضی، 1 دایره، 2 مربع، 3 گوشه‌گرد */
data class BlurSettings(
    val mode: Int = 0,
    val shape: Int = 0,
    val strength: Float = 0.9f,
    val pad: Float = 0.35f,
    val feather: Float = 0.25f,
    val highAccuracy: Boolean = true,
)

data class Pt(val t: Long, val cx: Float, val cy: Float, val w: Float, val h: Float)
class Track { val pts = ArrayList<Pt>() }

class FaceTracks(private val tracks: List<Track>) {
    companion object {
        const val HOLD = 350_000L
        const val MAX_GAP = 600_000L
    }

    /** خروجی: لیست [cx, cy, w, h] نرمالایز (محور y از بالا) */
    fun boxesAt(tUs: Long): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (tr in tracks) {
            val p = tr.pts
            if (p.isEmpty()) continue
            if (tUs < p.first().t - HOLD || tUs > p.last().t + HOLD) continue
            var lo = 0
            var hi = p.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (p[m].t < tUs) lo = m + 1 else hi = m
            }
            val next = if (lo < p.size) p[lo] else null
            val prev = if (lo > 0) p[lo - 1] else null
            val b: FloatArray? = when {
                prev == null -> next?.let { floatArrayOf(it.cx, it.cy, it.w, it.h) }
                next == null -> floatArrayOf(prev.cx, prev.cy, prev.w, prev.h)
                next.t - prev.t <= MAX_GAP -> {
                    val f = if (next.t == prev.t) 0f else (tUs - prev.t).toFloat() / (next.t - prev.t)
                    floatArrayOf(
                        prev.cx + (next.cx - prev.cx) * f,
                        prev.cy + (next.cy - prev.cy) * f,
                        prev.w + (next.w - prev.w) * f,
                        prev.h + (next.h - prev.h) * f,
                    )
                }
                tUs - prev.t <= HOLD -> floatArrayOf(prev.cx, prev.cy, prev.w, prev.h)
                next.t - tUs <= HOLD -> floatArrayOf(next.cx, next.cy, next.w, next.h)
                else -> null
            }
            if (b != null) out.add(b)
        }
        return out
    }
}

object Tracker {
    fun build(samples: List<Pair<Long, List<RectF>>>): FaceTracks {
        val all = ArrayList<Track>()
        val active = ArrayList<Track>()
        val lastIdx = HashMap<Track, Int>()
        for ((i, s) in samples.withIndex()) {
            val t = s.first
            val used = HashSet<Track>()
            for (d in s.second.sortedByDescending { it.width() * it.height() }) {
                val cx = d.centerX(); val cy = d.centerY(); val w = d.width(); val h = d.height()
                var best: Track? = null
                var bestDist = Float.MAX_VALUE
                for (tr in active) {
                    if (tr in used) continue
                    val gap = i - (lastIdx[tr] ?: continue)
                    if (gap > 8) continue
                    val l = tr.pts.last()
                    val dist = hypot(cx - l.cx, cy - l.cy)
                    val lim = max(max(w, h), max(l.w, l.h)) * 0.8f * (1f + 0.3f * gap)
                    if (dist < lim && dist < bestDist) { best = tr; bestDist = dist }
                }
                if (best == null) {
                    best = Track(); all.add(best); active.add(best)
                    best.pts.add(Pt(t, cx, cy, w, h))
                } else {
                    val l = best.pts.last()
                    val a = 0.65f
                    best.pts.add(Pt(t, a * cx + (1 - a) * l.cx, a * cy + (1 - a) * l.cy,
                        a * w + (1 - a) * l.w, a * h + (1 - a) * l.h))
                }
                lastIdx[best] = i
                used.add(best)
            }
            active.removeAll { i - (lastIdx[it] ?: 0) > 8 }
        }
        return FaceTracks(all)
    }

    fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun overlapMin(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val m = min(a.width() * a.height(), b.width() * b.height())
        return if (m <= 0f) 0f else inter / m
    }
}
