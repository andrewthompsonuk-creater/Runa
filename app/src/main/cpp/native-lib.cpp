#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct FrameStats {
    double motionEnergy = 0.0;
    double dogMaskRatio = 0.0;
    double movingDogRatio = 0.0;
    double centerX = -1.0;
    double centerY = -1.0;
    double velocity = 0.0;
    double score = 0.0;
    bool running = false;
};

int red(uint32_t argb) {
    return static_cast<int>((argb >> 16) & 0xff);
}

int green(uint32_t argb) {
    return static_cast<int>((argb >> 8) & 0xff);
}

int blue(uint32_t argb) {
    return static_cast<int>(argb & 0xff);
}

int luminance(uint32_t argb) {
    return (red(argb) * 299 + green(argb) * 587 + blue(argb) * 114) / 1000;
}

bool isDogLikePixel(uint32_t argb) {
    const int r = red(argb);
    const int g = green(argb);
    const int b = blue(argb);
    const int maxChannel = std::max({r, g, b});
    const int minChannel = std::min({r, g, b});
    const int chroma = maxChannel - minChannel;
    const int lum = luminance(argb);

    const bool brownOrTan = r > 60 && g > 35 && r >= g && g >= b && chroma > 20;
    const bool blackFur = lum < 65 && chroma < 45;
    const bool whiteFur = lum > 185 && chroma < 35;
    return brownOrTan || blackFur || whiteFur;
}

double clamp01(double value) {
    return std::max(0.0, std::min(1.0, value));
}

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (char ch : value) {
        if (ch == '"' || ch == '\\') {
            out << '\\' << ch;
        } else if (ch == '\n') {
            out << "\\n";
        } else {
            out << ch;
        }
    }
    return out.str();
}

std::string analyzeFrames(const std::vector<uint32_t>& frames, int frameCount, int width, int height,
                          double fps) {
    const int pixelCount = width * height;
    std::vector<FrameStats> stats(frameCount);

    double previousX = -1.0;
    double previousY = -1.0;

    for (int frame = 0; frame < frameCount; ++frame) {
        const uint32_t* current = frames.data() + (frame * pixelCount);
        const uint32_t* previous = frame > 0 ? frames.data() + ((frame - 1) * pixelCount) : nullptr;

        int dogPixels = 0;
        int movingPixels = 0;
        int movingDogPixels = 0;
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightTotal = 0.0;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const int index = y * width + x;
                const uint32_t pixel = current[index];
                const bool dogLike = isDogLikePixel(pixel);
                const int diff = previous == nullptr ? 0 : std::abs(luminance(pixel) - luminance(previous[index]));
                const bool moving = diff > 28;

                if (dogLike) {
                    ++dogPixels;
                }
                if (moving) {
                    ++movingPixels;
                }
                if (dogLike && moving) {
                    ++movingDogPixels;
                    weightedX += x;
                    weightedY += y;
                    weightTotal += 1.0;
                }
            }
        }

        FrameStats& frameStats = stats[frame];
        frameStats.motionEnergy = static_cast<double>(movingPixels) / pixelCount;
        frameStats.dogMaskRatio = static_cast<double>(dogPixels) / pixelCount;
        frameStats.movingDogRatio = static_cast<double>(movingDogPixels) / pixelCount;

        if (weightTotal > 0.0) {
            frameStats.centerX = weightedX / weightTotal / width;
            frameStats.centerY = weightedY / weightTotal / height;
        }

        if (previousX >= 0.0 && frameStats.centerX >= 0.0) {
            const double dx = frameStats.centerX - previousX;
            const double dy = frameStats.centerY - previousY;
            frameStats.velocity = std::sqrt(dx * dx + dy * dy) * fps;
        }
        if (frameStats.centerX >= 0.0) {
            previousX = frameStats.centerX;
            previousY = frameStats.centerY;
        }

        const double motionScore = clamp01((frameStats.motionEnergy - 0.012) / 0.12);
        const double dogScore = clamp01((frameStats.dogMaskRatio - 0.018) / 0.16);
        const double overlapScore = clamp01((frameStats.movingDogRatio - 0.002) / 0.045);
        const double speedScore = clamp01(frameStats.velocity / 0.55);
        frameStats.score = (motionScore * 0.25) + (dogScore * 0.20) + (overlapScore * 0.35) +
                           (speedScore * 0.20);
    }

    int consecutive = 0;
    for (FrameStats& frameStats : stats) {
        if (frameStats.score >= 0.42 && frameStats.movingDogRatio >= 0.003) {
            ++consecutive;
        } else {
            consecutive = 0;
        }
        frameStats.running = consecutive >= 2;
    }

    std::ostringstream json;
    json << std::fixed << std::setprecision(3);
    json << "{\"version\":\"heuristic-v0.1\",\"fps\":" << fps << ",\"frames\":[";
    bool first = true;
    for (int frame = 0; frame < frameCount; ++frame) {
        if (!stats[frame].running) {
            continue;
        }
        if (!first) {
            json << ",";
        }
        first = false;
        json << "{\"index\":" << frame
             << ",\"timeMs\":" << static_cast<int>((static_cast<double>(frame) / fps) * 1000.0)
             << ",\"score\":" << stats[frame].score
             << ",\"motion\":" << stats[frame].motionEnergy
             << ",\"dogMask\":" << stats[frame].dogMaskRatio
             << ",\"movingDog\":" << stats[frame].movingDogRatio
             << ",\"speed\":" << stats[frame].velocity << "}";
    }

    std::string note = "Experimental heuristic: flags frames with dog-colored foreground and sustained motion. "
                       "For production, replace the dog mask with a TFLite detector/pose model.";
    json << "],\"note\":\"" << jsonEscape(note) << "\"}";
    return json.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dogrundetector_MainActivity_analyzeFramesNative(
        JNIEnv* env,
        jobject,
        jintArray pixels,
        jint frameCount,
        jint width,
        jint height,
        jdouble fps) {
    if (frameCount <= 0 || width <= 0 || height <= 0 || fps <= 0.0) {
        return env->NewStringUTF("{\"version\":\"heuristic-v0.1\",\"frames\":[],\"note\":\"No frames supplied.\"}");
    }

    const int64_t expectedSize = static_cast<int64_t>(frameCount) * width * height;
    const jsize actualSize = env->GetArrayLength(pixels);
    if (expectedSize > actualSize) {
        return env->NewStringUTF("{\"version\":\"heuristic-v0.1\",\"frames\":[],\"note\":\"Frame buffer was incomplete.\"}");
    }

    jint* raw = env->GetIntArrayElements(pixels, nullptr);
    if (raw == nullptr) {
        return env->NewStringUTF("{\"version\":\"heuristic-v0.1\",\"frames\":[],\"note\":\"Unable to read frame buffer.\"}");
    }

    std::vector<uint32_t> frames(static_cast<size_t>(expectedSize));
    for (int64_t index = 0; index < expectedSize; ++index) {
        frames[static_cast<size_t>(index)] = static_cast<uint32_t>(raw[index]);
    }
    env->ReleaseIntArrayElements(pixels, raw, JNI_ABORT);

    const std::string result = analyzeFrames(frames, frameCount, width, height, fps);
    return env->NewStringUTF(result.c_str());
}
