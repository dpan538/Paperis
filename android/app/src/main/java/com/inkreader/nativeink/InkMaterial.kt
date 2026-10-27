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

    fun draw(canvas: Canvas, elapsedMs: Long, active: Boolean, full: Boolean, tiltX: Float, tiltY: Float, viewport:ReaderViewport?=null) {
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        // Independent physical cues: protective matte surface, laminated edge, and ink below it.
        // Fixed microstructure, shallow optical layers and guided front light; no animated noise or text blur.
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
                    ? smoothstep(630.0 + local * 55.0, 1000.0 + local * 20.0, elapsed) : 1.0;
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
                float night = mix(nightLight.x,nightLight.y,settling);
                float dark = mix(darkMode.x,darkMode.y,settling);
                float lampPower = mix(lightLevel.x,lightLevel.y,settling)*0.25+0.5;
                float contrast = mix(contrastLevel.x,contrastLevel.y,settling)-1.0;
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

                ink = clamp(ink,0.0,1.0);
                float quantized = clamp(floor(ink*15.0+grain)/15.0,0.0,1.0);

                // The substrate, neutral pigment and surface microstructure have separate roles.
                // Texture never changes solid ink density or the selected contrast level.
                float paperBase = mix(dot(modes,float3(0.944,0.905,0.932)),0.082,dark);
                float frontEnergy = night*(0.12+0.48*lampPower);
                paperBase += (1.0-paperBase)*frontEnergy*mix(1.0,0.06,dark);
                float pigment = mix(0.085-contrast*0.045,0.80+contrast*0.06,dark);

                float matte = structure.eval(dp*1.6+float2(113.0,9.0)).r;
                float2 etchedPoint = dp/0.9+float2(41.0,131.0);
                float slopeX = structure.eval(etchedPoint+float2(0.65,0.0)).r-structure.eval(etchedPoint-float2(0.65,0.0)).r;
                float slopeY = structure.eval(etchedPoint+float2(0.0,0.65)).r-structure.eval(etchedPoint-float2(0.0,0.65)).r;
                float3 normal = normalize(float3(-slopeX*0.24,-slopeY*0.24,1.0));
                float3 keyLight = normalize(float3(float2(-0.38,-0.62)*optical+pose*0.45,0.90));
                float illumination = optical*(dot(float2(0.5)-uv,-keyLight.xy)*0.0015-0.0003*(uv.x*uv.y-0.25));
                float bandCoordinate = uv.x*0.66+uv.y*0.34;
                float bandCenter = 0.27+pose.x*0.14+pose.y*0.09;
                float reflection = optical*(exp(-pow((bandCoordinate-bandCenter)/0.52,2.0))-0.65)*0.0007;
                float microLight = optical*(dot(normal,keyLight)-keyLight.z)*0.012;
                // Paired samples have zero expected mean; no broad thickness clouds or fiber bands.
                float2 fiberX = float2(dp.x/12.0,dp.y/0.8)+float2(59.0,7.0);
                float2 fiberY = float2(dp.x/0.85,dp.y/11.0)+float2(19.0,127.0);
                float fibers = structure.eval(fiberX).g-structure.eval(fiberX+float2(0.0,1.0)).g
                    +structure.eval(fiberY).b-structure.eval(fiberY+float2(1.0,0.0)).b;
                float surfaceGrain = (grain-0.5)*dot(modes,float3(0.003,0.004,0.007))
                    +(matte-0.5)*0.0015+fibers*0.0015*modes.z+microLight;
                surfaceGrain *= mix(1.0,0.60,dark);
                float paper = paperBase+illumination+reflection+surfaceGrain;

                // Shallow laminate depth stays at the perimeter, outside the uniform reading field.
                float inset = 2.2*scale;
                float left = p.x-surfaceBounds.x-inset;
                float top = p.y-surfaceBounds.y-inset;
                float right = surfaceBounds.z-inset-p.x;
                float bottom = surfaceBounds.w-inset-p.y;
                float shadow = (0.012+max(-keyLight.y,0.0)*0.008)*exp(-max(top,0.0)/(7.5*scale))
                    +(0.010+max(-keyLight.x,0.0)*0.006)*exp(-max(left,0.0)/(5.5*scale))
                    +(0.004+max(keyLight.y,0.0)*0.005)*exp(-max(bottom,0.0)/(3.2*scale))
                    +(0.004+max(keyLight.x,0.0)*0.005)*exp(-max(right,0.0)/(3.2*scale));
                paper -= shadow*mix(1.0,0.22,dark);
                float distance = windowDistance(p,inset,2.6*scale);
                float frontEdge = windowDistance(p,0.5*scale,1.2*scale);
                float outerSurface = mix(0.951,0.115,dark)+illumination+surfaceGrain;
                float laminate = mix(0.931,0.145,dark)+illumination+surfaceGrain;
                float rim = mix(outerSurface,laminate,1.0-smoothstep(-0.65*scale,0.65*scale,frontEdge));
                rim += 0.012*exp(-abs(frontEdge+0.45*scale)/(0.75*scale))
                    -0.012*exp(-max(-frontEdge,0.0)/(1.8*scale))
                    -mix(0.035,0.015,dark)*exp(-abs(distance)/(0.6*scale));
                paper += 0.018*exp(-abs(distance+1.2*scale)/(0.55*scale));
                float window = 1.0-smoothstep(-0.7*scale,0.7*scale,distance);
                paper = mix(rim,paper,window);
                float tone = clamp(mix(paper,pigment,quantized*window),0.0,1.0);

                // A zero-luminance direction in linear sRGB changes warmth without brightness.
                // One gamut bound for the whole surface prevents local channel clipping/patches.
                float lowY = toLinearSrgb(float3(clamp(paperBase-0.014,0.0,1.0))).r;
                float highY = toLinearSrgb(float3(clamp(max(paperBase+0.014,0.98),0.0,1.0))).r;
                float gamut = clamp(min(lowY,1.0-highY)/0.075,0.0,1.0);
                float warmth = (mix(temperature.x,temperature.y,settling)-2.0)*0.5
                    +dot(modes,float3(0.012,0.065,0.034))+night*0.010;
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
