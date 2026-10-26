package com.inkreader.nativeink

import android.graphics.*
import java.util.Random

/** Achromatic ink and controls under a subtly warm paper-like optical substrate. */
internal class InkMaterial {
    private val runtime = RuntimeShader(SOURCE)
    private val paint = Paint().apply { shader = runtime }
    private val microstructure = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
        val random = Random(0x494E4B4C41594552L)
        val values = IntArray(width * height) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        setPixels(values, 0, width, 0, 0, width, height)
    }
    init {
        runtime.setInputBuffer("structure", BitmapShader(microstructure, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
    }
    private var oldBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private var history: Bitmap? = null
    private var previousAppearance = InkAppearance()
    private var targetAppearance = InkAppearance()
    private val density = ColorMatrixColorFilter(floatArrayOf(
        0f,0f,0f,0f,255f,
        0f,0f,0f,0f,255f,
        0f,0f,0f,0f,255f,
        -1f,0f,0f,0f,255f
    ))
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = density }

    fun setInitial(bitmap: Bitmap, appearance: InkAppearance = InkAppearance()) {
        previousAppearance = appearance; targetAppearance = appearance
        oldBitmap = bitmap; nextBitmap = bitmap
        history = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        bind()
    }

    fun update(bitmap: Bitmap, full: Boolean, appearance: InkAppearance = targetAppearance) {
        val old = nextBitmap ?: run { setInitial(bitmap,appearance); return }
        val memory = history ?: return
        oldBitmap = old; nextBitmap = bitmap
        previousAppearance = targetAppearance; targetAppearance = appearance
        if (full) memory.eraseColor(Color.TRANSPARENT) else {
            val c = Canvas(memory)
            // Decay accumulated history, then retain erased ink at its original pixel position.
            c.drawColor(0x66000000, BlendMode.DST_OUT)
            val layer = c.saveLayer(null, Paint().apply { alpha = 8 })
            mask.blendMode = BlendMode.SRC_OVER
            c.drawBitmap(old, 0f, 0f, mask)
            mask.blendMode = BlendMode.DST_OUT
            c.drawBitmap(bitmap, 0f, 0f, mask)
            c.restoreToCount(layer)
            // New dark pixels cover older residue rather than receiving a duplicate outline.
            c.drawBitmap(bitmap, 0f, 0f, mask)
            mask.blendMode = null
        }
        bind()
    }

    private fun bind() {
        fun sample(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        runtime.setInputBuffer("oldPage", sample(oldBitmap!!))
        runtime.setInputBuffer("newPage", sample(nextBitmap!!))
        runtime.setInputBuffer("memory", sample(history!!))
    }

    fun draw(canvas: Canvas, elapsedMs: Long, active: Boolean, full: Boolean, tiltX: Float, tiltY: Float) {
        runtime.setFloatUniform("resolution", canvas.width.toFloat(), canvas.height.toFloat())
        runtime.setFloatUniform("viewPose", tiltX, tiltY)
        runtime.setFloatUniform("readingPresets", previousAppearance.preset.toFloat(), targetAppearance.preset.toFloat())
        runtime.setFloatUniform("nightLight",if(previousAppearance.night) 1f else 0f,if(targetAppearance.night) 1f else 0f)
        runtime.setFloatUniform("darkMode",if(previousAppearance.dark) 1f else 0f,if(targetAppearance.dark) 1f else 0f)
        runtime.setFloatUniform("lightLevel",previousAppearance.light.toFloat(),targetAppearance.light.toFloat())
        runtime.setFloatUniform("elapsed", elapsedMs.toFloat())
        runtime.setFloatUniform("active", if (active) 1f else 0f)
        runtime.setFloatUniform("fullRefresh", if (full) 1f else 0f)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
    }

    companion object {
        // Independent physical cues: protective matte surface, laminated edge, and ink below it.
        // Fixed microstructure, shallow optical layers and guided front light; no animated noise or text blur.
        private val SOURCE = """
            uniform shader oldPage;
            uniform shader newPage;
            uniform shader memory;
            uniform shader structure;
            uniform float2 resolution;
            uniform float2 viewPose;
            uniform float2 readingPresets;
            uniform float2 nightLight;
            uniform float2 darkMode;
            uniform float2 lightLevel;
            uniform float elapsed;
            uniform float active;
            uniform float fullRefresh;

            float windowDistance(float2 p, float inset, float radius) {
                float2 q = abs(p - resolution * 0.5) - (resolution * 0.5 - inset - radius);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            half4 main(float2 p) {
                float scale = min(resolution.x, resolution.y) / 1136.0;
                // Small sub-pixel refraction separates the stationary top film from ink below.
                // Page layout is unchanged; every ink/history sample shares the same optical ray.
                float2 inkPoint = p + viewPose * (1.1 * scale);
                float oldInk = 1.0 - oldPage.eval(inkPoint).r;
                float newInk = 1.0 - newPage.eval(inkPoint).r;
                float2 uv = p / resolution;
                float grain = structure.eval(p * 0.93).r;
                float local = structure.eval(p / 3.2).g;
                // Material changes arrive with ink deposition, after the erase phase.
                // Fixed capsule thresholds keep this a spatial settling response, not a UI fade.
                float settling = active > 0.5 && fullRefresh > 0.5
                    ? smoothstep(630.0 + local * 55.0, 1000.0 + local * 20.0, elapsed) : 1.0;
                float3 beforeMode = float3(readingPresets.x < 0.5 ? 1.0 : 0.0,
                    readingPresets.x > 0.5 && readingPresets.x < 1.5 ? 1.0 : 0.0, readingPresets.x > 1.5 ? 1.0 : 0.0);
                float3 afterMode = float3(readingPresets.y < 0.5 ? 1.0 : 0.0,
                    readingPresets.y > 0.5 && readingPresets.y < 1.5 ? 1.0 : 0.0, readingPresets.y > 1.5 ? 1.0 : 0.0);
                float3 modes = mix(beforeMode, afterMode, settling);
                float night = mix(nightLight.x,nightLight.y,settling);
                float dark = mix(darkMode.x,darkMode.y,settling);
                float lampPower = mix(lightLevel.x,lightLevel.y,settling) * 0.25 + 0.5;
                float ink = newInk;

                if (active > 0.5 && fullRefresh < 0.5) {
                    // Black deposition and clearing respond differently to the old -> new change.
                    float t = max(0.0, elapsed - 95.0);
                    float clearing = smoothstep(0.0, 245.0 + local * 48.0, t);
                    float deposition = smoothstep(12.0, 155.0 + local * 45.0, t);
                    float response = newInk > oldInk ? deposition : clearing;
                    ink = mix(oldInk, newInk, response);
                    float difference = abs(newInk - oldInk);
                    ink += difference * 0.035 * sin(clamp(t / 330.0, 0.0, 1.0) * 3.14159);
                }

                if (active > 0.5 && fullRefresh > 0.5) {
                    // Screen-wide erase/invert/deposit sequence; no spatial travelling wipe.
                    float t = elapsed + local * 12.0;
                    if (t < 95.0) ink = oldInk;
                    else if (t < 225.0) ink = mix(oldInk, 0.95 - oldInk * 0.68, smoothstep(95.0, 185.0, t));
                    else if (t < 385.0) ink = mix(0.95 - oldInk * 0.68, 0.015, smoothstep(225.0, 365.0, t));
                    else if (t < 535.0) ink = mix(0.015, 0.86, smoothstep(385.0, 470.0, t));
                    else if (t < 690.0) ink = mix(0.86, 0.0, smoothstep(535.0, 670.0, t));
                    else ink = newInk * smoothstep(690.0, 1000.0, t);
                }

                float history = memory.eval(inkPoint).a;
                float historyWeight = (active > 0.5 && fullRefresh < 0.5) ? smoothstep(95.0, 430.0, elapsed) : 1.0;
                ink = clamp(ink + history * historyWeight * (1.0 - ink), 0.0, 1.0);
                // The ink is an underlying pigment plane, separate from the protective surface.
                float quantized = floor(ink * 15.0 + grain) / 15.0;
                float cleanCapsules = structure.eval(inkPoint / 0.86 + float2(97.0, 41.0)).b;
                float gentleCapsules = (structure.eval(inkPoint / 1.35 + float2(57.0, 171.0)).g
                    + structure.eval(inkPoint * 0.91 + float2(117.0, 27.0)).b) * 0.5;
                float paperCapsules = structure.eval(inkPoint / 1.10 + float2(97.0, 41.0)).b;
                float capsuleCores = dot(modes, float3(smoothstep(0.18, 0.86, cleanCapsules),
                    smoothstep(0.12, 0.88, gentleCapsules), smoothstep(0.22, 0.80, paperCapsules)));
                float thickness = structure.eval(inkPoint / (72.0 * scale) + float2(31.0, 73.0)).g;
                float matte = structure.eval(p / 5.1 + float2(113.0, 9.0)).r;
                float3 keyLight = normalize(float3(-0.38 + viewPose.x * 0.45, -0.62 + viewPose.y * 0.45, 0.90));
                float illumination = 0.014 + dot(float2(0.5) - uv, -keyLight.xy) * 0.034 - 0.010 * uv.x * uv.y;
                float whiteGrain = dot(modes, float3(0.006, 0.008, 0.018));
                float substrate = dot(modes,float3(0.879,0.824,0.846)) + illumination + (capsuleCores - 0.5) * whiteGrain + (thickness - 0.5) * 0.006;
                // Ink density varies within strokes as well as along anti-aliased contours.
                float pigment = dot(modes, float3(0.067, 0.114, 0.091)) + capsuleCores * dot(modes, float3(0.025,0.022,0.047));
                float inkPlane = mix(substrate, pigment, clamp(quantized, 0.0, 1.0));

                // Etched micro-normal field. Features stay fixed; their light response changes
                // with the actual tablet pose instead of playing an animated noise texture.
                float2 etchedPoint = p / 1.8 + float2(41.0, 131.0);
                float slopeX = structure.eval(etchedPoint + float2(0.65, 0.0)).r - structure.eval(etchedPoint - float2(0.65, 0.0)).r;
                float slopeY = structure.eval(etchedPoint + float2(0.0, 0.65)).r - structure.eval(etchedPoint - float2(0.0, 0.65)).r;
                float3 normal = normalize(float3(-slopeX * 0.24, -slopeY * 0.24, 1.0));
                float3 viewDirection = normalize(float3(-viewPose * 0.44, 1.0));
                float3 halfDirection = normalize(keyLight + viewDirection);
                float softLobe = pow(max(dot(normal, halfDirection), 0.0), 11.0);
                float fresnel = 0.035 + 0.965 * pow(1.0 - max(dot(normal, viewDirection), 0.0), 5.0);
                float bandCoordinate = uv.x * 0.66 + uv.y * 0.34;
                float bandCenter = 0.27 + viewPose.x * 0.14 + viewPose.y * 0.09;
                float diffuseReflection = exp(-pow((bandCoordinate - bandCenter) / 0.52, 2.0));
                float veil = 0.006 + 0.012 * diffuseReflection + softLobe * fresnel * 0.50;
                float microLight = (dot(normal, keyLight) - keyLight.z) * 0.045;
                float surfaceGrain = (grain - 0.5) * 0.008 + (matte - 0.5) * 0.004 + microLight;
                surfaceGrain *= dot(modes,float3(0.35,0.55,1.0));
                // Interlocked pulp varies independently from the capsule field.
                float fibers = structure.eval(float2(p.x/(31.0*scale),p.y/(1.6*scale))+float2(59.0,7.0)).g
                    + structure.eval(float2(p.x/(1.7*scale),p.y/(23.0*scale))+float2(19.0,127.0)).b - 1.0;
                float pulp = (fibers * 0.010 + (thickness-0.5)*0.009) * modes.z;
                float visibleInk = mix(inkPlane, 0.96, veil) + surfaceGrain + pulp * (1.0-quantized*0.7);
                float darkSubstrate = 0.082 + illumination*0.18 + surfaceGrain*0.6 + pulp*0.6;
                float palePigment = 0.78 + capsuleCores*0.045;
                float darkPlane = mix(darkSubstrate,palePigment,clamp(quantized,0.0,1.0));
                visibleInk = mix(visibleInk,darkPlane,dark);

                // The optical window is under the front surface. Its edge exposes a thin
                // bond line, shallow sidewall and directional contact shadow, not a tablet body.
                float inset = 19.0 * scale;
                float left = p.x - inset;
                float top = p.y - inset;
                float right = resolution.x - inset - p.x;
                float bottom = resolution.y - inset - p.y;
                float shadow = (0.052 + max(-keyLight.y, 0.0) * 0.048) * exp(-max(top, 0.0) / (7.5 * scale))
                    + (0.031 + max(-keyLight.x, 0.0) * 0.035) * exp(-max(left, 0.0) / (5.5 * scale))
                    + (0.012 + max(keyLight.y, 0.0) * 0.035) * exp(-max(bottom, 0.0) / (3.2 * scale))
                    + (0.010 + max(keyLight.x, 0.0) * 0.035) * exp(-max(right, 0.0) / (3.2 * scale));
                visibleInk -= shadow * mix(1.0,0.22,dark);

                float distance = windowDistance(p, inset, 2.6 * scale);
                float frontEdge = windowDistance(p, 6.0 * scale, 7.0 * scale);
                float outerSurface = mix(0.665,0.115,dark) + illumination * mix(1.6,0.25,dark) + surfaceGrain;
                float laminate = mix(0.735,0.145,dark) + illumination * mix(0.8,0.2,dark) + surfaceGrain;
                // Separate, softly lit front edge and darker lower bond seam.
                float frontHighlight = 0.055 * exp(-abs(frontEdge + 0.45 * scale) / (0.75 * scale));
                float frontShadow = 0.055 * exp(-max(-frontEdge, 0.0) / (1.8 * scale));
                float rim = mix(outerSurface, laminate, 1.0 - smoothstep(-0.65 * scale, 0.65 * scale, frontEdge));
                rim += frontHighlight - frontShadow;
                float bondLine = mix(0.22,0.045,dark) * exp(-abs(distance) / (0.6 * scale));
                rim -= bondLine;
                float innerHighlight = 0.018 * exp(-abs(distance + 1.2 * scale) / (0.55 * scale));
                visibleInk += innerHighlight;
                float window = 1.0 - smoothstep(-0.7 * scale, 0.7 * scale, distance);
                float tone = mix(rim, visibleInk, window);
                tone = mix(tone,tone * 0.965 + 0.014,modes.y*(1.0-dark));
                // A normal front light is mixed inside the guide before it reaches the page.
                // Keep only a very broad, sub-percent extraction variation: no exposed LEDs,
                // periodic scallops, bright edge dots or local light-leak defects.
                float guideUniformity = 1.0 - uv.y*0.006 + uv.x*0.003;
                float frontEnergy = (0.12+0.48*lampPower)*guideUniformity;
                float scattering = mix(0.035+0.965*(1.0-clamp(quantized,0.0,1.0)),
                    0.06+0.44*clamp(quantized,0.0,1.0),dark);
                // Front light adds reflected illumination inside the optical window.
                // It must brighten paper at every level, without dimming the ambient substrate.
                float nightTone = tone+(1.0-tone)*frontEnergy*scattering*window;
                tone = mix(tone,nightTone,night);
                // Warmth belongs to the light substrate, not colored pigment or colored UI.
                // Dark ink stays neutral; the small channel spread never becomes a sepia wash.
                float paperWeight = smoothstep(0.18, 0.78, tone);
                // Keep the default near-neutral; comfort is a small substrate bias, not a yellow overlay.
                float warmth = dot(modes,float3(0.12,0.65,0.34)) + night*0.10;
                float3 paperTone = float3(tone) + float3(0.010, 0.004, -0.009) * paperWeight * warmth;
                return half4(half3(clamp(paperTone, 0.0, 1.0)), 1.0);
            }
        """.trimIndent()
    }
}
