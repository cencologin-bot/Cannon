#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>

// ---------- HSV ----------
static inline void toHsv(int r, int g, int b, float& h, float& s, float& v) {
    int mx = std::max(r, std::max(g, b)), mn = std::min(r, std::min(g, b));
    float d = static_cast<float>(mx - mn);
    v = mx / 255.f;
    s = mx ? d / mx : 0.f;
    if (d == 0.f) { h = 0.f; return; }
    if (mx == r)      h = 60.f * std::fmod((g - b) / d, 6.f);
    else if (mx == g) h = 60.f * ((b - r) / d + 2.f);
    else              h = 60.f * ((r - g) / d + 4.f);
    if (h < 0.f) h += 360.f;
}
// Umbrales HSV (tono 0-360, saturación y brillo 0-1)
static constexpr float G_H0 = 70.f, G_H1 = 170.f, G_S = 0.40f, G_V = 0.45f;
static constexpr float R_H0 = 20.f, R_H1 = 340.f, R_S = 0.50f, R_V = 0.45f;

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_cannon_autofire_NativeDetector_analyze(JNIEnv* env, jobject, jobject buffer,
        jint rowStride, jint pixelStride, jint left, jint top, jint width, jint height, jboolean lite) {
    jfloat res[3] = {0.f, 0.f, 0.f};
    jfloatArray out = env->NewFloatArray(3);
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (base && width > 0 && height > 0 && pixelStride >= 4) {
        long total = 0, green = 0, red = 0;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                long off = static_cast<long>(top + y) * rowStride + static_cast<long>(left + x) * pixelStride;
                if (off + 3 >= cap) continue;
                float h, s, v;
                toHsv(base[off], base[off + 1], base[off + 2], h, s, v);
                ++total;
                if (h >= G_H0 && h <= G_H1 && s >= G_S && v >= G_V) ++green;
                else if ((h <= R_H0 || h >= R_H1) && s >= R_S && v >= R_V) ++red;
            }
        }
        if (total > 0) {
            double gr = static_cast<double>(green) / total;
            double rr = static_cast<double>(red) / total;
            res[0] = static_cast<jfloat>(gr);
            res[1] = static_cast<jfloat>(rr);
            if (lite) {
                // Modo Lite (fijo): verde >= 7 % es positivo, rojo >= 6 % es negativo.
                bool g = gr >= 0.07, r = rr >= 0.06;
                if (g && r) res[2] = (gr >= rr) ? 2.f : 1.f;
                else if (g) res[2] = 2.f;
                else if (r) res[2] = 1.f;
            } else if (gr > 0.075 && gr > rr * 1.25) res[2] = 2.f;
            else if (rr > 0.075 && rr > gr * 1.15) res[2] = 1.f;
        }
    }
    env->SetFloatArrayRegion(out, 0, 3, res);
    return out;
}

// ---------- Reconocimiento visual del menú ----------
// Región del menú (normalizada) y rejilla de colores medios usada como "firma".
static constexpr float ML = 0.02f, MT = 0.45f, MR = 0.98f, MB = 0.93f;
static constexpr int COLS = 12, ROWS = 10, SS = 4, TOL = 22;

static bool gridMean(const uint8_t* base, jlong cap, int rowStride, int pixelStride,
                     int W, int H, uint8_t* out) {
    if (!base || pixelStride < 4) return false;
    for (int cy = 0; cy < ROWS; ++cy) {
        for (int cx = 0; cx < COLS; ++cx) {
            float x0 = (ML + (MR - ML) * cx / COLS) * W, x1 = (ML + (MR - ML) * (cx + 1) / COLS) * W;
            float y0 = (MT + (MB - MT) * cy / ROWS) * H, y1 = (MT + (MB - MT) * (cy + 1) / ROWS) * H;
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int sy = 0; sy < SS; ++sy) for (int sx = 0; sx < SS; ++sx) {
                int px = static_cast<int>(x0 + (x1 - x0) * (sx + 0.5f) / SS);
                int py = static_cast<int>(y0 + (y1 - y0) * (sy + 0.5f) / SS);
                long off = static_cast<long>(py) * rowStride + static_cast<long>(px) * pixelStride;
                if (px < 0 || py < 0 || px >= W || py >= H || off + 3 >= cap) continue;
                sr += base[off]; sg += base[off + 1]; sb += base[off + 2]; ++n;
            }
            uint8_t* o = out + (cy * COLS + cx) * 3;
            if (n == 0) { o[0] = o[1] = o[2] = 0; continue; }
            o[0] = sr / n; o[1] = sg / n; o[2] = sb / n;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_cannon_autofire_NativeDetector_menuSignature(JNIEnv* env, jobject, jobject buffer,
        jint rowStride, jint pixelStride, jint fullW, jint fullH) {
    uint8_t sig[COLS * ROWS * 3] = {0};
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    gridMean(base, env->GetDirectBufferCapacity(buffer), rowStride, pixelStride, fullW, fullH, sig);
    jbyteArray out = env->NewByteArray(COLS * ROWS * 3);
    env->SetByteArrayRegion(out, 0, COLS * ROWS * 3, reinterpret_cast<jbyte*>(sig));
    return out;
}

// Devuelve la fracción de celdas (0..1) cuyo color coincide con la firma guardada.
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_cannon_autofire_NativeDetector_menuMatch(JNIEnv* env, jobject, jobject buffer,
        jint rowStride, jint pixelStride, jint fullW, jint fullH, jbyteArray signature) {
    if (!signature || env->GetArrayLength(signature) != COLS * ROWS * 3) return 0.f;
    uint8_t cur[COLS * ROWS * 3] = {0};
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!gridMean(base, env->GetDirectBufferCapacity(buffer), rowStride, pixelStride, fullW, fullH, cur)) return 0.f;
    jbyte* sg = env->GetByteArrayElements(signature, nullptr);
    int ok = 0;
    for (int i = 0; i < COLS * ROWS; ++i) {
        int dr = std::abs(cur[i * 3] - static_cast<uint8_t>(sg[i * 3]));
        int dg = std::abs(cur[i * 3 + 1] - static_cast<uint8_t>(sg[i * 3 + 1]));
        int db = std::abs(cur[i * 3 + 2] - static_cast<uint8_t>(sg[i * 3 + 2]));
        if (dr <= TOL && dg <= TOL && db <= TOL) ++ok;
    }
    env->ReleaseByteArrayElements(signature, sg, JNI_ABORT);
    return static_cast<jfloat>(ok) / (COLS * ROWS);
}
