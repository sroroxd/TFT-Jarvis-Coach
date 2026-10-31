#include <jni.h>
#include <llama.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>

namespace {
std::mutex runtime_lock;
std::atomic<bool> stop_requested{false};
// Qwen2.5-Instruct uses ChatML control tokens (<|im_start|>/<|im_end|>).
// If parse_special=false, those sequences become ordinary text and the
// assistant role boundary is not represented correctly in the token stream.
constexpr bool kParseSpecial = true;
static_assert(kParseSpecial, "Qwen2.5-Instruct requires special ChatML token parsing");
llama_model * model = nullptr;
llama_context * ctx = nullptr;
llama_sampler * sampler = nullptr;

void unload() {
    if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}
std::string from_java(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) return {};
    std::string s(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return s;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pete_tftjarvis_JarvisOfflineEngine_nativeLoad(
        JNIEnv * env, jclass, jstring file) {
    std::lock_guard<std::mutex> lock(runtime_lock);
    stop_requested = true;
    unload();
    std::string path = from_java(env, file);
    if (path.empty()) return JNI_FALSE;
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // deterministic CPU-only arm64 baseline
    model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return JNI_FALSE;
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 256;
    cp.n_threads = 4;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) { unload(); return JNI_FALSE; }
    auto params = llama_sampler_chain_default_params();
    sampler = llama_sampler_chain_init(params);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    stop_requested = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pete_tftjarvis_JarvisOfflineEngine_nativeGenerate(
        JNIEnv * env, jclass, jstring promptValue, jint maxTokens) {
    std::lock_guard<std::mutex> lock(runtime_lock);
    if (!model || !ctx || !sampler) return env->NewStringUTF("");
    const std::string prompt = from_java(env, promptValue);
    if (prompt.empty() || prompt.size() > 12000) return env->NewStringUTF("");
    stop_requested = false;
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_sampler_reset(sampler);
    const auto * vocab = llama_model_get_vocab(model);
    const int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, kParseSpecial);
    if (n >= 0 || -n > 1700 || -n <= 0) return env->NewStringUTF("");
    std::vector<llama_token> tokens((size_t) -n);
    if (llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                      tokens.data(), (int) tokens.size(), true, kParseSpecial) < 0) {
        return env->NewStringUTF("");
    }
    int offset = 0;
    while (offset < (int) tokens.size() && !stop_requested) {
        const int batchSize = std::min(256, (int) tokens.size() - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, batchSize);
        if (llama_decode(ctx, batch) != 0) return env->NewStringUTF("");
        offset += batchSize;
    }
    if (stop_requested) return env->NewStringUTF("");
    std::string output;
    const int limit = std::max(1, std::min((int) maxTokens, 256));
    for (int i = 0; i < limit && !stop_requested; ++i) {
        llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        char piece[2048];
        int len = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (len > 0 && len <= (int) sizeof(piece)) output.append(piece, (size_t) len);
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    // NewStringUTF requires modified UTF-8; construct a Java String via UTF-8 charset.
    jbyteArray bytes = env->NewByteArray((jsize) output.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize) output.size(),
                            reinterpret_cast<const jbyte *>(output.data()));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring utf8 = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(strClass, ctor, bytes, utf8);
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(bytes);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pete_tftjarvis_JarvisOfflineEngine_nativeStop(
        JNIEnv *, jclass) {
    stop_requested = true; // never blocks on the generation mutex
}

extern "C" JNIEXPORT void JNICALL
Java_com_pete_tftjarvis_JarvisOfflineEngine_nativeUnload(
        JNIEnv *, jclass) {
    stop_requested = true;
    std::lock_guard<std::mutex> lock(runtime_lock);
    unload();
}
