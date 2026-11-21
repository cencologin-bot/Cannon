#include <jni.h>
#include <cstdint>

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_cannon_autofire_NativeDetector_analyze(JNIEnv* env, jobject, jobject buffer,
        jint rowStride, jint pixelStride, jint left, jint top, jint width, jint height) {
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
                int r = base[off], g = base[off + 1], b = base[off + 2];
                ++total;
                if (g > 75 && g > r * 1.22 && g > b * 1.12) ++green;
                if (r > 85 && r > g * 1.22 && r > b * 1.12) ++red;
            }
        }
        if (total > 0) {
            double gr = static_cast<double>(green) / total;
            double rr = static_cast<double>(red) / total;
            res[0] = static_cast<jfloat>(gr);
            res[1] = static_cast<jfloat>(rr);
            if (gr > 0.075 && gr > rr * 1.25) res[2] = 2.f;
            else if (rr > 0.075 && rr > gr * 1.15) res[2] = 1.f;
        }
    }
    env->SetFloatArrayRegion(out, 0, 3, res);
    return out;
}
