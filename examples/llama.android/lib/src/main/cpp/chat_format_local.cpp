// Local copy of common_chat_format_single(...) that replaces the broken
// prefix-match diff algorithm with an anchor-based seam search.  Every
// other behaviour (calling common_chat_templates_apply, thread-safety
// model, add_bos/eos defaults) is byte-for-byte identical to the upstream.
//
// Why we don't just patch upstream common/chat.cpp:
//   - parent llama.cpp is shared by many targets (cli, server, libllama);
//     printf-style diagnostics there would touch unrelated builds.
//   - this file lives under :lib/src/main/cpp/ which already links liblog,
//     so __android_log_print resolves without extra CMake wiring and the
//     output goes straight to logcat.
//
// Kept under common.h / chat.h visibility, like ai_chat.cpp.  Drop this
// file (and the adjacent changes in ai_chat.cpp + CMakeLists.txt) once
// the upstream fix lands.

#include <android/log.h>

#include "chat.h"

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <sys/stat.h>

// ============================================================================
// Diagnostic utilities (kept in-tree for future debugging)
// ============================================================================

// ---- File dump helpers ------------------------------------------------

// Global directory set once by JNI (setDiagDir).  When non-empty, the
// per-render diagnostics below write full-text files here instead of
// trying to cram thousands of bytes into logcat.
static std::string g_fmt_dump_dir;

extern "C" void set_log_dump_dir(const std::string &path) {
    g_fmt_dump_dir = path;
    mkdir(path.c_str(), 0777);
}

static void write_to_file(const std::string &path, const std::string &contents) {
    std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
    if (!ofs) {
        __android_log_print(ANDROID_LOG_WARN, "fmt-single",
            "failed to open %s for write", path.c_str());
        return;
    }
    ofs.write(contents.data(), (std::streamsize) contents.size());
    ofs.close();
    __android_log_print(ANDROID_LOG_INFO, "fmt-single",
        "wrote %zu bytes to %s", contents.size(), path.c_str());
}

// ---- Logcat dump helper (tagged, chunked) -----------------------------

// logcat silently truncates lines longer than ~4 KiB.  dump_str splits a
// string into tagged chunks so every byte survives.
static constexpr size_t LOG_LINE_BUDGET = 1800;

#define LOG_ANCHOR_DUMP(label, value) \
    do { dump_str("fmt-anchor", (label), (value)); } while (0)

static void dump_str(const char *tag, const std::string &label, const std::string &s) {
    if (s.empty()) {
        __android_log_print(ANDROID_LOG_INFO, tag, "%s[empty]", label.c_str());
        return;
    }
    const size_t total = (s.size() + LOG_LINE_BUDGET - 1) / LOG_LINE_BUDGET;
    char hdr[96];
    for (size_t off = 0, i = 0; off < s.size(); ++i) {
        size_t n = s.size() - off;
        if (n > LOG_LINE_BUDGET) n = LOG_LINE_BUDGET;
        std::snprintf(hdr, sizeof(hdr), "%s[%zu/%zu chnk=%zu] ", label.c_str(), i + 1, total, n);
        __android_log_print(ANDROID_LOG_INFO, tag, "%s%.*s", hdr, (int) n, s.data() + off);
        off += n;
    }
}

// ============================================================================
// chat_format_local — anchor-based diff replacement
// ============================================================================

// Per-render sequence counter (global, not per-role) so every render
// gets a unique id for any file dumps.
static int g_render_seq = 0;

// Forward-declared in ai_chat.cpp; must keep external linkage.
// NOLINTNEXTLINE
std::string chat_format_local(const struct common_chat_templates *tmpls,
                              const std::vector<common_chat_msg> &past_msg,
                              const common_chat_msg              &new_msg,
                              bool                                 add_ass,
                              bool                                 use_jinja,
                              bool                                 enable_thinking) {
    common_chat_templates_inputs inputs;
    inputs.use_jinja       = use_jinja;
    inputs.enable_thinking = enable_thinking;
    // tmpls' full definition is private inside common/chat.cpp (only a forward
    // declaration is exposed via chat.h), so we can't read add_bos / add_eos
    // from here. The Android caller doesn't pass BOS/EOS overrides anyway
    // (see InferenceEngineImpl::prepare: it calls common_chat_templates_init
    // with an empty chat_template_override string), so leaving them default
    // matches upstream behaviour for this configuration.
    inputs.add_bos         = false;
    inputs.add_eos         = false;

    std::string fmt_past_msg;
    if (!past_msg.empty()) {
        inputs.messages              = past_msg;
        inputs.add_generation_prompt = false;
        fmt_past_msg                 = common_chat_templates_apply(tmpls, inputs).prompt;
    }
    std::ostringstream ss;
    if (add_ass && !fmt_past_msg.empty() && fmt_past_msg.back() == '\n') {
        ss << "\n";
    }
    inputs.messages.push_back(new_msg);
    inputs.add_generation_prompt = add_ass;
    auto fmt_new_msg = common_chat_templates_apply(tmpls, inputs).prompt;

    // ---- Anchor-based seam search ------------------------------------
    //
    // Upstream's common_chat_format_single assumes fmt_new_msg starts with
    // fmt_past_msg as a literal prefix and uses substr(fmt_past_msg.size()).
    // That fails when the jinja template diverges between the two renders
    // (e.g. add_generation_prompt toggling can inject a leading newline or
    // strip thinking tags from historical assistant messages).
    //
    // Repair: use the *tail* of fmt_past_msg as a literal anchor and
    // rfind() it in fmt_new_msg.  Because fmt_new_msg embeds the same
    // past_tail bytes before the new messages, rfind pins the seam
    // precisely.  Multiple lengths are tried (64→32→16→8→4) so that even
    // if the tail is short or the anchor appears in user content, the
    // search still converges.  Past_msg.empty() is handled by the caller
    // below (returns fmt_new_msg whole).
    //
    // If no anchor length matches (pathological — rfind found nothing)
    // we fall back to returning the whole fmt_new_msg, which is always
    // safe (just slightly more tokenisation work for the LLM).

    size_t seam_off = std::string::npos;
    size_t anchor_len = 0;
    if (!fmt_past_msg.empty()) {
        const size_t max_anchor = std::min<size_t>(fmt_past_msg.size(), 64);
        for (size_t len = max_anchor; len >= 4; len >>= 1) {
            const size_t try_len = std::min(len, fmt_past_msg.size());
            const std::string anchor = fmt_past_msg.substr(fmt_past_msg.size() - try_len);
            size_t pos = fmt_new_msg.rfind(anchor);
            if (pos != std::string::npos && pos + try_len <= fmt_new_msg.size()) {
                seam_off = pos + try_len;
                anchor_len = try_len;
                break;
            }
        }
    }

    if (seam_off == std::string::npos) {
        __android_log_print(ANDROID_LOG_WARN, "fmt-single",
            "anchor rfind failed past_size=%zu new_size=%zu role='%s' content_len=%zu -- returning whole fmt_new_msg",
            fmt_past_msg.size(), fmt_new_msg.size(),
            new_msg.role.c_str(), new_msg.content.size());
        ss << fmt_new_msg;
        return ss.str();
    }

    // ---- Diagnostics --------------------------------------------------
    // Prints seam metadata + template source (once) to the file-dump dir.
    // The per-turn past/new file dumps below are commented out by default;
    // uncomment them when debugging prompt-rendering issues.
    {
        // Print the jinja template source once per process.
        // {
        //     static bool tmpl_printed = false;
        //     if (!tmpl_printed) {
        //         std::string tmpl_src = common_chat_templates_source(tmpls);
        //         const std::string dir = g_fmt_dump_dir;
        //         if (!dir.empty()) {
        //             write_to_file(dir + "/template_src.txt", tmpl_src);
        //             __android_log_print(ANDROID_LOG_INFO, "fmt-single",
        //                 "template source written to %s/template_src.txt (%zu bytes)",
        //                 dir.c_str(), tmpl_src.size());
        //         }
        //         tmpl_printed = true;
        //     }
        // }

        ++g_render_seq;
        char idbuf[32];
        std::snprintf(idbuf, sizeof(idbuf), "%s_%03d", new_msg.role.c_str(), g_render_seq);

        __android_log_print(ANDROID_LOG_INFO, "fmt-single",
            "=== render id=%s add_ass=%d role='%s' past_size=%zu new_size=%zu seam_off=%zu anchor_len=%zu seam_minus_past=%zd ===",
            idbuf, (int) add_ass, new_msg.role.c_str(),
            fmt_past_msg.size(), fmt_new_msg.size(),
            seam_off, anchor_len, (ssize_t) seam_off - (ssize_t) fmt_past_msg.size());

        // Uncomment the next block to dump past/new full text to files
        // per render.  Useful when investigating jinja template drift.
        // const std::string dir = g_fmt_dump_dir;
        // if (!dir.empty()) {
        //     write_to_file(dir + "/past_" + idbuf + ".txt", fmt_past_msg);
        //     write_to_file(dir + "/new_"  + idbuf + ".txt", fmt_new_msg);
        // }

        __android_log_print(ANDROID_LOG_INFO, "fmt-single", "=== render end id=%s ===", idbuf);
    }

    if (add_ass && !fmt_past_msg.empty() && fmt_past_msg.back() == '\n') {
        return std::string("\n") + fmt_new_msg.substr(seam_off);
    }
    return fmt_new_msg.substr(seam_off);
}
