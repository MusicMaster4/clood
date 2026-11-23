package dev.slop.clood

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Exact OKLCH/OKLab glass-orb color mapping matching `src/components/orb-color.ts`. */
object OrbColor {
    data class Vec3(val x: Float, val y: Float, val z: Float)
    data class OrbTones(val deep: Vec3, val mid: Vec3, val light: Vec3, val glow: Vec3)

    private const val MID_L = -0.08
    private const val LIGHT_L = 0.14
    private const val LIGHT_C = 0.55
    private const val LIGHT_HUE = 10.0
    private const val DEEP_L = 0.24
    private const val DEEP_C = 0.04
    private const val DEEP_HUE = -12.0
    private const val GLOW_C = 0.85
    private const val YELLOW_HUE = 100.0
    private const val YELLOW_WIDTH = 22.0

    val PEARL_TONES: OrbTones = OrbTones(
        deep = lchToLabMapped(0.24, 0.008, 255.0),
        mid = lchToLabMapped(0.64, 0.014, 250.0),
        light = lchToLabMapped(0.985, 0.008, 235.0),
        glow = lchToLabMapped(0.80, 0.014, 245.0),
    )

    fun hexToTones(hex: String): OrbTones {
        val rgb = parseHex(hex)
        val lab = linearToLab(rgb.x.toDouble(), rgb.y.toDouble(), rgb.z.toDouble())
        val rawC = hypot(lab.y.toDouble(), lab.z.toDouble())
        var h = atan2(lab.z.toDouble(), lab.y.toDouble()) * 180.0 / PI
        if (h < 0.0) h += 360.0

        val l = min(0.86, max(0.56, lab.x.toDouble()))
        val c = min(0.26, rawC)
        val y = exp(-(((h - YELLOW_HUE) / YELLOW_WIDTH).pow(2.0)))

        return OrbTones(
            deep = lchToLabMapped(DEEP_L, min(c * 0.4, DEEP_C) * (1.0 - 0.8 * y), h + DEEP_HUE - 25.0 * y),
            mid = lchToLabMapped(l + MID_L * (1.0 - y), c, h),
            light = lchToLabMapped(min(0.97, l + LIGHT_L), c * LIGHT_C, h + LIGHT_HUE),
            glow = lchToLabMapped(min(0.88, l + 0.04), c * GLOW_C, h),
        )
    }

    fun mixLab(a: Vec3, b: Vec3, t: Float): Vec3 = Vec3(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t,
    )

    fun labToLinear(lab: Vec3): Vec3 {
        val L = lab.x.toDouble()
        val a = lab.y.toDouble()
        val b = lab.z.toDouble()
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).pow(3.0)
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).pow(3.0)
        val s = (L - 0.0894841775 * a - 1.291485548 * b).pow(3.0)
        return Vec3(
            max(0.0, 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s).toFloat(),
            max(0.0, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s).toFloat(),
            max(0.0, -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s).toFloat(),
        )
    }

    private fun decodeSrgb(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun parseHex(hex: String): Vec3 {
        val clean = hex.trim().removePrefix("#")
        val full = if (clean.length == 3) {
            clean.map { "$it$it" }.joinToString("")
        } else {
            clean.padEnd(6, '0').take(6)
        }
        val n = full.toIntOrNull(16) ?: return Vec3(0.45f, 0.5f, 0.95f)
        return Vec3(
            decodeSrgb(((n shr 16) and 255) / 255.0).toFloat(),
            decodeSrgb(((n shr 8) and 255) / 255.0).toFloat(),
            decodeSrgb((n and 255) / 255.0).toFloat(),
        )
    }

    private fun lchToLinearRaw(l: Double, c: Double, h: Double): Triple<Double, Double, Double> {
        val r = h * PI / 180.0
        val a = c * cos(r)
        val b = c * sin(r)
        val ll = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3.0)
        val mm = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3.0)
        val ss = (l - 0.0894841775 * a - 1.291485548 * b).pow(3.0)
        return Triple(
            4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
            -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
            -0.0041960863 * ll - 0.7034186147 * mm + 1.707614701 * ss,
        )
    }

    private fun inGamut(rgb: Triple<Double, Double, Double>): Boolean =
        rgb.first in -1e-4..1.0001 && rgb.second in -1e-4..1.0001 && rgb.third in -1e-4..1.0001

    private fun lchToLabMapped(l: Double, c: Double, h: Double): Vec3 {
        var rgb = lchToLinearRaw(l, c, h)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = c
            repeat(18) {
                val mid = (lo + hi) * 0.5
                if (inGamut(lchToLinearRaw(l, mid, h))) lo = mid else hi = mid
            }
            rgb = lchToLinearRaw(l, lo, h)
        }
        return linearToLab(
            rgb.first.coerceIn(0.0, 1.0),
            rgb.second.coerceIn(0.0, 1.0),
            rgb.third.coerceIn(0.0, 1.0),
        )
    }

    private fun linearToLab(r: Double, g: Double, b: Double): Vec3 {
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Vec3(
            (0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s).toFloat(),
            (1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s).toFloat(),
            (0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s).toFloat(),
        )
    }
}
