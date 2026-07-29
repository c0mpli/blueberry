// JNI bridge to llama.cpp.
//
// Two features justify the whole cost of building llama.cpp rather than dropping in MediaPipe,
// and both are exposed here:
//
//   * GBNF grammar-constrained decoding. A small model *cannot* emit malformed JSON if the
//     grammar will not allow it, which removes the single biggest failure mode of small-model
//     tool calling. It also cuts the token count hard, and decode is the bottleneck.
//
//   * KV cache save and restore. The system prompt is ~1K tokens of fixed prefix on every
//     request, and prefilling that on a phone costs seconds. Prefill it once, save the sequence
//     state to disk, restore it per request, and each turn then prefills only the user's ~20
//     tokens.
//
// Everything is handle-based (jlong) and none of it is thread-safe; the Kotlin side confines all
// calls to one dedicated worker thread.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "blueberry-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

llama_model   *as_model(jlong h)   { return reinterpret_cast<llama_model *>(h); }
llama_context *as_ctx(jlong h)     { return reinterpret_cast<llama_context *>(h); }
llama_sampler *as_sampler(jlong h) { return reinterpret_cast<llama_sampler *>(h); }

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void log_callback(ggml_log_level level, const char *text, void * /*user*/) {
    if (level == GGML_LOG_LEVEL_ERROR)      LOGE("%s", text);
    else if (level == GGML_LOG_LEVEL_WARN)  __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    // info/debug from ggml is extremely chatty during load; drop it.
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeInit(JNIEnv *, jobject) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("backend initialised");
}

JNIEXPORT jstring JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// ---------------------------------------------------------------------------------------------
// Model and context
// ---------------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeLoadModel(JNIEnv *env, jobject, jstring jpath) {
    const std::string path = to_string(env, jpath);
    llama_model_params params = llama_model_default_params();
    // llama.cpp on Android is CPU or Vulkan; there is no Hexagon path. Leave offload off here and
    // let the build decide, so a CPU-only build does not silently ask for a GPU that is not there.
    params.n_gpu_layers = 0;
    // mmap rather than reading the whole file: the pages the model does not touch never come in,
    // and the ones it does are evictable under pressure instead of counting as dirty anonymous
    // memory. On an 8 GB phone that is the difference between surviving a background app and not.
    // (`use_mmap` was replaced by this enum upstream.)
    params.load_mode    = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0;
    }
    LOGI("model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeFreeModel(JNIEnv *, jobject, jlong h) {
    if (h) llama_model_free(as_model(h));
}

JNIEXPORT jlong JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeNewContext(JNIEnv *, jobject, jlong hmodel, jint n_ctx, jint n_threads) {
    llama_model *model = as_model(hmodel);
    if (model == nullptr) return 0;

    llama_context_params params = llama_context_default_params();
    params.n_ctx         = static_cast<uint32_t>(n_ctx);
    params.n_threads     = n_threads;
    params.n_threads_batch = n_threads;
    params.embeddings    = false;

    // We only ever sample the last token of a batch, so exactly one set of logits is needed.
    //
    // This line is load-bearing. `n_outputs_max` defaults to `n_batch`, and the logits buffer is
    // n_outputs_max * n_vocab * 4 bytes. With a 2048 batch and Qwen3's 151,936-token vocabulary
    // that reserves ~1.2 GB *on top of* the weights — enough to push an 8 GB phone into swap, at
    // which point the prefill appears to hang rather than fail.
    params.n_outputs_max = 1;

    // A prompt longer than n_batch is split automatically, so a modest batch costs a little
    // prefill throughput and saves a lot of compute buffer.
    params.n_batch       = 512;
    params.n_ubatch      = 512;

    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeFreeContext(JNIEnv *, jobject, jlong h) {
    if (h) llama_free(as_ctx(h));
}

JNIEXPORT jint JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeContextSize(JNIEnv *, jobject, jlong h) {
    return h ? static_cast<jint>(llama_n_ctx(as_ctx(h))) : 0;
}

// ---------------------------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeTokenize(JNIEnv *env, jobject, jlong hmodel, jstring jtext,
                                                  jboolean add_special, jboolean parse_special) {
    llama_model *model = as_model(hmodel);
    if (model == nullptr) return nullptr;
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const std::string text = to_string(env, jtext);

    // Negative return is "buffer too small, and here is how much you need".
    int32_t needed = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                     nullptr, 0, add_special, parse_special);
    if (needed <= 0) needed = (int32_t) text.size() + 8;

    std::vector<llama_token> tokens(needed);
    const int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                     tokens.data(), needed, add_special, parse_special);
    if (n < 0) {
        LOGE("tokenize failed (%d)", n);
        return nullptr;
    }

    jintArray out = env->NewIntArray(n);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint *>(tokens.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeTokenToPiece(JNIEnv *env, jobject, jlong hmodel, jint token) {
    llama_model *model = as_model(hmodel);
    if (model == nullptr) return env->NewStringUTF("");
    const llama_vocab *vocab = llama_model_get_vocab(model);

    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, n).c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeIsEog(JNIEnv *, jobject, jlong hmodel, jint token) {
    llama_model *model = as_model(hmodel);
    if (model == nullptr) return JNI_TRUE;
    return llama_vocab_is_eog(llama_model_get_vocab(model), token) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------------------------
// Decode
// ---------------------------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeDecode(JNIEnv *env, jobject, jlong hctx, jintArray jtokens) {
    llama_context *ctx = as_ctx(hctx);
    if (ctx == nullptr) return -1;

    const jsize n = env->GetArrayLength(jtokens);
    if (n == 0) return 0;

    std::vector<llama_token> tokens(n);
    env->GetIntArrayRegion(jtokens, 0, n, reinterpret_cast<jint *>(tokens.data()));

    // Positions are tracked automatically, which is what makes decoding straight on top of a
    // restored KV state work without the caller having to know how many tokens the prefix held.
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    const int32_t rc = llama_decode(ctx, batch);
    if (rc != 0) LOGE("llama_decode failed (%d) for %d tokens", rc, (int) n);
    return rc;
}

/** Drop everything in the sequence so the next decode starts from position zero. */
JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeClearKv(JNIEnv *, jobject, jlong hctx) {
    llama_context *ctx = as_ctx(hctx);
    if (ctx == nullptr) return;
    llama_memory_clear(llama_get_memory(ctx), /*data=*/true);
}

// ---------------------------------------------------------------------------------------------
// Sampling
// ---------------------------------------------------------------------------------------------

/**
 * Build a sampler chain. When [jgrammar] is non-empty the chain is grammar-constrained, which is
 * how a tool call is made structurally incapable of being malformed.
 */
JNIEXPORT jlong JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeNewSampler(JNIEnv *env, jobject, jlong hmodel,
                                                    jstring jgrammar, jstring jroot,
                                                    jfloat temp, jint top_k, jfloat top_p, jint seed) {
    llama_model *model = as_model(hmodel);
    if (model == nullptr) return 0;
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const std::string grammar = to_string(env, jgrammar);
    const std::string root    = to_string(env, jroot);

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());

    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), root.c_str());
        if (g == nullptr) {
            // A grammar that will not parse must be loud. Silently sampling unconstrained would
            // produce plausible-looking JSON that then fails to parse at the far end.
            LOGE("grammar failed to parse; refusing to build an unconstrained sampler");
            llama_sampler_free(chain);
            return 0;
        }
        llama_sampler_chain_add(chain, g);
    }

    if (temp <= 0.0f) {
        // Routing is a classification problem. Greedy is both the most accurate and the fastest.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)  llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        if (top_p < 1.f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));
    }

    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeFreeSampler(JNIEnv *, jobject, jlong h) {
    if (h) llama_sampler_free(as_sampler(h));
}

JNIEXPORT jint JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeSample(JNIEnv *, jobject, jlong hctx, jlong hsampler) {
    llama_context *ctx = as_ctx(hctx);
    llama_sampler *smpl = as_sampler(hsampler);
    if (ctx == nullptr || smpl == nullptr) return -1;
    // -1 is "the logits of the last token in the last batch".
    return llama_sampler_sample(smpl, ctx, -1);
}

/** Feed the chosen token back so the grammar advances its state. */
JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeAccept(JNIEnv *, jobject, jlong hsampler, jint token) {
    if (hsampler) llama_sampler_accept(as_sampler(hsampler), token);
}

JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeResetSampler(JNIEnv *, jobject, jlong hsampler) {
    if (hsampler) llama_sampler_reset(as_sampler(hsampler));
}

// ---------------------------------------------------------------------------------------------
// KV cache state — the reason on-device routing is viable at all
// ---------------------------------------------------------------------------------------------

/**
 * Persist sequence 0 to [jpath] along with the tokens it represents. Called once after the
 * catalogue prefix has been prefilled, and again whenever the catalogue changes.
 */
JNIEXPORT jlong JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeSaveState(JNIEnv *env, jobject, jlong hctx,
                                                   jstring jpath, jintArray jtokens) {
    llama_context *ctx = as_ctx(hctx);
    if (ctx == nullptr) return 0;

    const std::string path = to_string(env, jpath);
    const jsize n = jtokens ? env->GetArrayLength(jtokens) : 0;
    std::vector<llama_token> tokens(n);
    if (n > 0) env->GetIntArrayRegion(jtokens, 0, n, reinterpret_cast<jint *>(tokens.data()));

    const size_t written = llama_state_seq_save_file(ctx, path.c_str(), /*seq_id=*/0,
                                                     tokens.data(), tokens.size());
    if (written == 0) LOGE("state save failed: %s", path.c_str());
    else              LOGI("state saved: %zu bytes, %d tokens", written, (int) n);
    return (jlong) written;
}

/**
 * Restore the prefilled prefix. Returns the tokens it covered, or null when the file is missing or
 * incompatible — in which case the caller re-prefills and re-saves rather than failing the turn.
 */
JNIEXPORT jintArray JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeLoadState(JNIEnv *env, jobject, jlong hctx, jstring jpath) {
    llama_context *ctx = as_ctx(hctx);
    if (ctx == nullptr) return nullptr;

    const std::string path = to_string(env, jpath);
    const size_t capacity = llama_n_ctx(ctx);
    std::vector<llama_token> tokens(capacity);
    size_t n_out = 0;

    const size_t read = llama_state_seq_load_file(ctx, path.c_str(), /*dest_seq_id=*/0,
                                                  tokens.data(), capacity, &n_out);
    if (read == 0) {
        LOGI("no usable saved state at %s", path.c_str());
        return nullptr;
    }

    jintArray out = env->NewIntArray((jsize) n_out);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, (jsize) n_out, reinterpret_cast<const jint *>(tokens.data()));
    LOGI("state restored: %zu bytes, %zu tokens", read, n_out);
    return out;
}

/**
 * Drop everything after [keep] positions. This is what makes the saved prefix reusable turn after
 * turn: restore once, then rewind to the end of the prefix instead of reloading it.
 */
JNIEXPORT void JNICALL
Java_com_blueberry_llm_LlamaBridge_nativeTrimTo(JNIEnv *, jobject, jlong hctx, jint keep) {
    llama_context *ctx = as_ctx(hctx);
    if (ctx == nullptr) return;
    llama_memory_seq_rm(llama_get_memory(ctx), /*seq_id=*/0, /*p0=*/keep, /*p1=*/-1);
}

} // extern "C"
