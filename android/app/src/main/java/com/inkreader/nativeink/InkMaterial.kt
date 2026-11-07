package com.inkreader.nativeink

import android.graphics.*
import java.util.Random

/** Achromatic ink and controls under a subtly warm paper-like optical substrate. */
internal class InkMaterial {
    private val runtime get() = sharedRuntime
    private val paint = Paint().apply { shader = sharedRuntime }
    private var oldBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private var previousAppearance = InkAppearance()
    private var targetAppearance = InkAppearance()

    fun setInitial(bitmap: Bitmap, appearance: InkAppearance = InkAppearance()) {
        previousAppearance = appearance; targetAppearance = appearance
        oldBitmap = bitmap; nextBitmap = bitmap
        bind()
    }

    fun update(bitmap: Bitmap, appearance: InkAppearance = targetAppearance) {
        val old = nextBitmap ?: run { setInitial(bitmap,appearance); return }
        oldBitmap = old; nextBitmap = bitmap
        previousAppearance = targetAppearance; targetAppearance = appearance
        bind()
    }

    fun settle() {
        if(oldBitmap !== nextBitmap || previousAppearance != targetAppearance) {
            oldBitmap=nextBitmap;previousAppearance=targetAppearance;bind()
        }
    }

    private fun bind() {
        fun sample(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        runtime.setInputBuffer("oldPage", sample(oldBitmap!!))
        runtime.setInputBuffer("newPage", sample(nextBitmap!!))
    }

    fun draw(canvas: Canvas, elapsedMs: Long, active: Boolean, full: Boolean, tiltX: Float, tiltY: Float, viewport:ReaderViewport?=null,surface:Boolean=false,
        ambient:AmbientTone=AmbientTone.NEUTRAL,pageTurn:Boolean=false,actualElapsed:Long=elapsedMs,actualDuration:Long=InkResponse.PARTIAL_MS,pageFlash:Float=.55f) {
        // The current viewport owns the window extent, including native recording surfaces.
        val width=viewport?.windowWidth?:canvas.width;val height=viewport?.windowHeight?:canvas.height
        require(width>0&&height>0)
        runtime.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        runtime.setFloatUniform("viewPose", tiltX, tiltY)
        runtime.setFloatUniform("surfaceBounds",viewport?.left?.toFloat()?:0f,viewport?.top?.toFloat()?:0f,(width-(viewport?.right?:0)).toFloat(),(height-(viewport?.bottom?:0)).toFloat())
        runtime.setFloatUniform("surfaceScale",viewport?.density?:1f)
        runtime.setFloatUniform("readingPresets", previousAppearance.preset.toFloat(), targetAppearance.preset.toFloat())
        runtime.setFloatUniform("nightLight",if(previousAppearance.night) 1f else 0f,if(targetAppearance.night) 1f else 0f)
        runtime.setFloatUniform("darkMode",if(previousAppearance.dark) 1f else 0f,if(targetAppearance.dark) 1f else 0f)
        runtime.setFloatUniform("temperature",previousAppearance.warmth.toFloat(),targetAppearance.warmth.toFloat())
        runtime.setFloatUniform("contrastLevel",previousAppearance.contrast.toFloat(),targetAppearance.contrast.toFloat())
        runtime.setFloatUniform("surfaceLighting",if(previousAppearance.optical)1f else 0f,if(targetAppearance.optical)1f else 0f)
        runtime.setFloatUniform("lightLevel",previousAppearance.light.toFloat(),targetAppearance.light.toFloat())
        runtime.setFloatUniform("elapsed", elapsedMs.toFloat())
        runtime.setFloatUniform("active", if (active) 1f else 0f)
        runtime.setFloatUniform("fullRefresh", if (full) 1f else 0f)
        runtime.setFloatUniform("surfaceTransition",if(surface)1f else 0f)
        runtime.setFloatUniform("ambientTone", ambient.paper, ambient.ink, ambient.veil)
        runtime.setFloatUniform("pageTurn", if(pageTurn)1f else 0f)
        runtime.setFloatUniform("actualElapsed", actualElapsed.toFloat())
        runtime.setFloatUniform("actualDuration", actualDuration.toFloat().coerceAtLeast(1f))
        runtime.setFloatUniform("pageFlash", pageFlash.coerceIn(0f,1f))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        // One shader compile and one 1 MiB microstructure for the whole process. Each material only
        // rebinds its own page buffers; viewport changes no longer recompile the shader.
        private val sharedRuntime: RuntimeShader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            RuntimeShader(SOURCE).also { shader ->
                val texture = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val random = Random(0x494E4B4C41594552L)
                val values = IntArray(1024 * 1024) {
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                }
                texture.setPixels(values, 0, 1024, 0, 0, 1024, 1024)
                shader.setInputBuffer("structure", BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                })
            }
        }
        // Fixed microstructure; no animated noise, no blurred or displaced text.
        // Independent physical cues: protective matte surface, laminated edge, and ink below it.
        private val SOURCE = """
            uniform shader oldPage;
            uniform shader newPage;
            uniform shader structure;
            uniform float2 resolution;
            uniform float4 surfaceBounds;
            uniform float surfaceScale;
            uniform float2 viewPose;
            uniform float2 readingPresets;
            uniform float2 nightLight;
            uniform float2 darkMode;
            uniform float2 lightLevel;
            uniform float2 temperature;
            uniform float2 contrastLevel;
            uniform float2 surfaceLighting;
            uniform float elapsed;
            uniform float active;
            uniform float fullRefresh;
            uniform float surfaceTransition;
            uniform float3 ambientTone;
            uniform float pageTurn;
            uniform float actualElapsed;
            uniform float actualDuration;
            uniform float pageFlash;

            float windowDistance(float2 p, float inset, float radius) {
                float2 q = abs(p - (surfaceBounds.xy+surfaceBounds.zw) * 0.5) - ((surfaceBounds.zw-surfaceBounds.xy) * 0.5 - inset - radius);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            half4 main(float2 p) {
                float scale = surfaceScale;
                if(p.x<surfaceBounds.x || p.y<surfaceBounds.y || p.x>surfaceBounds.z || p.y>surfaceBounds.w) {
                    float outsideDark=darkMode.y;
                    return half4(half3(mix(float3(0.957,0.957,0.950),float3(0.085),outsideDark)),1.0);
                }
                // All material lengths use density-independent coordinates.
                float2 dp = (p-surfaceBounds.xy)/scale;
                float grain = structure.eval(dp*1.86).r;
                float local = structure.eval(dp/1.6).g;
                float settling = active > 0.5 && fullRefresh > 0.5
                    ? smoothstep(630.0 + local * 55.0, 1000.0 + local * 20.0, elapsed)
                    : active > 0.5 && surfaceTransition > 0.5 ? smoothstep(0.0,365.0,elapsed-95.0) : 1.0;
                float optical = mix(surfaceLighting.x,surfaceLighting.y,settling);
                float2 pose = viewPose*optical;
                float2 inkPoint = p + pose*(0.22*scale);
                float oldInk = 1.0-oldPage.eval(inkPoint).r;
                float newInk = 1.0-newPage.eval(inkPoint).r;
                float2 uv = (p-surfaceBounds.xy)/(surfaceBounds.zw-surfaceBounds.xy);
                float3 beforeMode = float3(readingPresets.x < 0.5 ? 1.0 : 0.0,
                    readingPresets.x > 0.5 && readingPresets.x < 1.5 ? 1.0 : 0.0, readingPresets.x > 1.5 ? 1.0 : 0.0);
                float3 afterMode = float3(readingPresets.y < 0.5 ? 1.0 : 0.0,
                    readingPresets.y > 0.5 && readingPresets.y < 1.5 ? 1.0 : 0.0, readingPresets.y > 1.5 ? 1.0 : 0.0);
                float3 modes = mix(beforeMode,afterMode,settling);
                // Paper tooth scales are chosen for a 308 PPI panel: 2-4 physical pixels, below Nyquist.
                float grainScale = dot(modes,float3(0.98,0.88,1.18));
                float fineScale = dot(modes,float3(1.30,1.18,1.62));
                float capsuleScale = dot(modes,float3(0.62,0.55,1.02));
                float bodyGrain = structure.eval(dp*grainScale+float2(3.0,17.0)).r - structure.eval(dp*grainScale+float2(5.3,20.1)).r;
                float ultra = structure.eval(dp*2.42+float2(211.0,73.0)).r - structure.eval(dp*2.42+float2(212.6,74.4)).r;
                float night = mix(nightLight.x,nightLight.y,settling);
                float dark = mix(darkMode.x,darkMode.y,settling);
                float lampPower = mix(lightLevel.x,lightLevel.y,settling)*0.25+0.5;
                float contrast = mix(contrastLevel.x,contrastLevel.y,settling)-1.0;
                float ink = newInk;

                if (active > 0.5 && pageTurn > 0.5) {
                    // Partial e-ink refresh, not a flash: a narrow gate-line boundary retires the
                    // old page and deposits the next one. The panel never goes blank, white or black.
                    // Fixed 220 ms by product decision; no lateral motion.
                    float span = max(actualDuration, 1.0);
                    float row = clamp((dp.y - surfaceBounds.y) / max(surfaceBounds.w - surfaceBounds.y, 1.0), 0.0, 1.0);
                    float front = clamp(actualElapsed / span * 1.26 - row * 0.26, 0.0, 1.0);
                    float settle = smoothstep(0.0, 1.0, front);
                    ink = mix(oldInk, newInk, settle);
                    // A restrained wavefront keeps the change from feeling like a hard digital cut;
                    // it never becomes a full-screen pigment flood.
                    float edge = sin(3.14159 * front);
                    ink += (grain - 0.5) * 0.012 * edge;
                    ink += (structure.eval(floor(p) + float2(0.5)).r - 0.5) * 0.006 * edge;
                } else if (active > 0.5 && fullRefresh < 0.5) {
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

                ink = clamp(ink,0.0,1.0);
                // Edge roughness: particle boundaries are not mathematically crisp. Zero at solid ink.
                float edgeNoise = structure.eval(dp*3.7+float2(9.0,5.0)).r - 0.5;
                ink += edgeNoise * dot(modes,float3(0.040,0.050,0.085)) * (ink*(1.0-ink))*4.0;
                // Bright environments add a bounded veiling reflection that lifts solid ink slightly.
                ink = mix(ink, 0.0, clamp(ambientTone.z*(1.0-dark),0.0,0.08));
                float quantized = clamp(floor(ink*15.0+grain)/15.0,0.0,1.0);

                // The substrate, neutral pigment and surface microstructure have separate roles.
                // Texture never changes solid ink density or the selected contrast level.
                // Three deliberately different physical papers: dense porcelain, soft warm gentle,
                // and coarser fibrous paper. Differences are texture/density, not just brightness.
                float paperBase = mix(dot(modes,float3(0.947,0.936,0.930)),0.082,dark);
                float frontEnergy = night*(0.18+0.62*lampPower);
                paperBase += (1.0-paperBase)*frontEnergy*mix(1.0,0.06,dark);
                float pigment = mix(0.085-contrast*0.045,0.80+contrast*0.06,dark);
                pigment += dot(modes,float3(0.0,0.010,-0.006))*(1.0-dark);
                // Ambient light shifts the light substrate and its pigment floor; dark mode keeps its
                // accepted polarity. Scene "indoor" is exactly neutral, so steady rooms are unchanged.
                paperBase = clamp(paperBase + ambientTone.x*(1.0-dark), 0.0, 0.985);
                pigment = clamp(pigment + ambientTone.y*(1.0-dark), 0.0, 0.92);

                float matte = structure.eval(dp*1.6+float2(113.0,9.0)).r;
                // Each material has its own physical scale: dense porcelain tooth, warm short
                // gentle fibers, and coarse fibrous paper with visible capsule clusters.
                float fine = structure.eval(dp*fineScale+float2(17.0,43.0)).r - structure.eval(dp*fineScale+float2(19.6,44.9)).r;
                float capsule = structure.eval(dp*capsuleScale+float2(71.0,29.0)).g - structure.eval(dp*capsuleScale+float2(74.4,31.7)).g;
                float2 etchedPoint = dp/0.9+float2(41.0,131.0);
                float slopeX = structure.eval(etchedPoint+float2(0.65,0.0)).r-structure.eval(etchedPoint-float2(0.65,0.0)).r;
                float slopeY = structure.eval(etchedPoint+float2(0.0,0.65)).r-structure.eval(etchedPoint-float2(0.0,0.65)).r;
                float3 normal = normalize(float3(-slopeX*0.28,-slopeY*0.28,1.0));
                float3 keyLight = normalize(float3(float2(-0.38,-0.62)*optical+pose*0.45,0.90));
                float illumination = optical*(dot(float2(0.5)-uv,-keyLight.xy)*0.0015-0.0003*(uv.x*uv.y-0.25));
                float bandCoordinate = uv.x*0.66+uv.y*0.34;
                float bandCenter = 0.27+pose.x*0.14+pose.y*0.09;
                float reflection = optical*(exp(-pow((bandCoordinate-bandCenter)/0.52,2.0))-0.65)*0.0007;
                float microLight = optical*(dot(normal,keyLight)-keyLight.z)*0.012;
                // Paired samples have zero expected mean; no broad thickness clouds or fiber bands.
                float fiberLong = dot(modes,float3(13.0,10.0,5.0));
                float fiberShort = dot(modes,float3(0.85,0.95,0.58));
                float2 fiberX = float2(dp.x/fiberLong,dp.y/fiberShort)+float2(59.0,7.0);
                float2 fiberY = float2(dp.x/fiberShort,dp.y/fiberLong)+float2(19.0,127.0);
                float fibers = structure.eval(fiberX).g-structure.eval(fiberX+float2(0.0,1.0)).g
                    +structure.eval(fiberY).b-structure.eval(fiberY+float2(1.0,0.0)).b;
                // Paper also has a diagonal long-fiber layer; porcelain stays nearly isotropic.
                float diagLong = dot(modes,float3(0.0,17.0,8.0));
                float diagShort = dot(modes,float3(0.0,1.15,0.78));
                float2 fiberD = float2((dp.x*0.707+dp.y*0.707)/max(diagLong,0.001),(dp.x*0.707-dp.y*0.707)/max(diagShort,0.001))+float2(137.0,11.0);
                float diagonalFibers = structure.eval(fiberD).b-structure.eval(fiberD+float2(1.0,0.0)).b;
                float surfaceGrain = (grain-0.5)*dot(modes,float3(0.0070,0.0062,0.0052))
                    +(fine-0.5)*dot(modes,float3(0.0194,0.0142,0.0155))
                    +(ultra-0.5)*dot(modes,float3(0.0060,0.0080,0.0120))
                    +(capsule-0.5)*dot(modes,float3(0.0133,0.0092,0.0038))
                    +(matte-0.5)*dot(modes,float3(0.0040,0.0042,0.0028))
                    +bodyGrain*dot(modes,float3(0.0,0.0028,0.0064))
                    +fibers*dot(modes,float3(0.0,0.0032,0.0022))
                    +diagonalFibers*dot(modes,float3(0.0,0.0018,0.0014))
                    +microLight;
                // Guided front light approaches the top of the range; scale tooth so it never clips white.
                surfaceGrain *= mix(1.0,0.55,night);
                surfaceGrain *= mix(1.0,0.86,dark);
                float paper = paperBase+illumination+reflection+surfaceGrain;

                // No simulated bezel/laminate is painted on the reading surface. The paper and
                // its tooth continue uniformly to every edge; this removes corner grey casts and
                // keeps the outermost material at the same precision as the centre.
                float window = 1.0;
                float tone = clamp(mix(paper,pigment,quantized),0.0,1.0);

                // A zero-luminance direction in linear sRGB changes warmth without brightness.
                // One gamut bound for the whole surface prevents local channel clipping/patches.
                float lowY = toLinearSrgb(float3(clamp(paperBase-0.014,0.0,1.0))).r;
                float highY = toLinearSrgb(float3(clamp(max(paperBase+0.014,0.98),0.0,1.0))).r;
                float gamut = clamp(min(lowY,1.0-highY)/0.075,0.0,1.0);
                float warmth = (mix(temperature.x,temperature.y,settling)-2.0)*0.5
                    +dot(modes,float3(0.008,0.130,0.050))+night*0.024;
                float3 neutral = toLinearSrgb(float3(tone));
                float3 tint = float3(0.020,0.00102041387,-0.069)*warmth*gamut*(1.0-quantized)*(1.0-dark)*window;
                float3 encoded = fromLinearSrgb(neutral+tint);
                // Unbiased output rounding preserves the mean across warmth steps on 8-bit surfaces.
                // Shared-channel noise stays achromatic; solid pigment is never dithered.
                float roundingNoise = structure.eval(floor(p)+float2(0.5)).r-0.5;
                encoded += float3(roundingNoise/255.0)*(1.0-quantized);
                return half4(half3(clamp(encoded,0.0,1.0)),1.0);
            }
        """.trimIndent()
    }
}
