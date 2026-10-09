@file:OptIn(UnstableApi::class)

package com.aliiii.faceblur

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** اگر بلور وارونه (بالا/پایین) افتاد، این را false کن */
private const val FLIP_Y = true
private const val MAX_BOXES = 8

class FaceBlurEffect(private val tracks: FaceTracks, private val s: BlurSettings) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FaceBlurProgram(tracks, s, useHdr)
}

class FaceBlurProgram(
    private val tracks: FaceTracks,
    private val s: BlurSettings,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram
    private var w = 1
    private var h = 1

    init {
        try {
            program = GlProgram(VERTEX, buildFragment())
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, false)
            program.setFloatsUniform("uSize", floatArrayOf(w.toFloat(), h.toFloat()))
            program.setFloatUniform("uMode", s.mode.toFloat())
            program.setFloatUniform("uShape", s.shape.toFloat())
            program.setFloatUniform("uStrength", s.strength)
            program.setFloatUniform("uFeather", s.feather)
            program.setFloatUniform("uTime", presentationTimeUs / 1_000_000f)

            val boxes = tracks.boxesAt(presentationTimeUs).sortedByDescending { it[2] * it[3] }.take(MAX_BOXES)
            for (i in 0 until MAX_BOXES) {
                val b = boxes.getOrNull(i)
                val v = if (b == null) floatArrayOf(0f, 0f, 0f, 0f) else {
                    val bw = b[2] * (1f + s.pad)
                    val bh = b[3] * (1f + s.pad)
                    val cy = if (FLIP_Y) 1f - b[1] else b[1]
                    floatArrayOf(b[0] * w, cy * h, bw * w / 2f, bh * h / 2f)
                }
                program.setFloatsUniform("uBox$i", v)
            }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

        private fun buildFragment(): String {
            val sb = StringBuilder()
            sb.append("""
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vUv;
uniform vec2 uSize;
uniform float uMode;
uniform float uShape;
uniform float uStrength;
uniform float uFeather;
uniform float uTime;
""")
            for (i in 0 until MAX_BOXES) sb.append("uniform vec4 uBox$i;\n")
            sb.append("""
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

float sdf(vec2 p, vec4 b) {
  vec2 q = p - b.xy;
  vec2 h = b.zw;
  if (uShape < 0.5) {
    return (length(q / h) - 1.0) * min(h.x, h.y);
  } else if (uShape < 1.5) {
    return length(q) - max(h.x, h.y);
  } else if (uShape < 2.5) {
    vec2 d = abs(q) - h;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
  } else {
    float r = min(h.x, h.y) * 0.45;
    vec2 d = abs(q) - h + r;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
  }
}

vec3 blurAt(vec2 p, float radius) {
  vec3 acc = texture2D(uTexSampler, vUv).rgb;
  float wsum = 1.0;
  float rot = hash(p) * 6.2831853;
  for (int i = 1; i <= 64; i++) {
    float fi = float(i);
    float r = sqrt(fi / 64.0);
    float a = fi * 2.39996323 + rot;
    vec2 o = vec2(cos(a), sin(a)) * r * radius;
    float wt = exp(-r * r * 1.5);
    acc += texture2D(uTexSampler, vUv + o / uSize).rgb * wt;
    wsum += wt;
  }
  return acc / wsum;
}

vec3 effectColor(vec2 p, vec2 bh) {
  float m = min(bh.x, bh.y);
  float t = floor(uTime * 20.0);
  if (uMode < 0.5) {
    return blurAt(p, uStrength * m * 0.55);
  } else if (uMode < 1.5) {
    float blk = max(4.0, m * 0.30 * uStrength);
    vec2 c = (floor(p / blk) + 0.5) * blk;
    return texture2D(uTexSampler, c / uSize).rgb;
  } else if (uMode < 2.5) {
    vec3 b = blurAt(p, uStrength * m * 0.40);
    float n = hash(p + vec2(t, t * 1.3)) - 0.5;
    return clamp(b + vec3(n * 0.22), 0.0, 1.0);
  } else if (uMode < 3.5) {
    vec2 g = floor(p / 3.0);
    vec3 n = vec3(hash(g + vec2(t, 1.0)), hash(g + vec2(t, 7.0)), hash(g + vec2(t, 13.0)));
    float band = step(0.85, hash(vec2(floor(p.y / 6.0), t)));
    vec3 b = blurAt(p, m * 0.5);
    return mix(b * 0.25, n, 0.75 + 0.25 * band);
  } else {
    return vec3(0.0);
  }
}

void main() {
  vec4 base = texture2D(uTexSampler, vUv);
  vec2 p = vUv * uSize;
  float best = 0.0;
  vec2 bh = vec2(1.0);
""")
            for (i in 0 until MAX_BOXES) {
                sb.append("""
  if (uBox$i.z > 0.0) {
    float d = sdf(p, uBox$i);
    float f = max(1.0, uFeather * min(uBox$i.z, uBox$i.w));
    float mk = 1.0 - smoothstep(-f, f, d);
    if (mk > best) { best = mk; bh = uBox$i.zw; }
  }
""")
            }
            sb.append("""
  if (best <= 0.0) { gl_FragColor = base; return; }
  vec3 fx = effectColor(p, bh);
  gl_FragColor = vec4(mix(base.rgb, fx, best), base.a);
}
""")
            return sb.toString()
        }
    }
}
