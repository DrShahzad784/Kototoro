"""Kototoro's embedding adaptations to the pinned OpenJDK Mobile source.

These edits target OpenJDK's iOS/Zero port, Darwin's public ucontext and pthread APIs, and the static libraries
actually produced by this build. No TachiyomiAZ patch, export list or symbol-retention source is consumed.
OpenJDK remains under its own GPLv2/Classpath-exception notices.
"""
import re
import subprocess
from pathlib import Path


def replace(source, old, new):
    if source.count(old) != 1:
        raise RuntimeError("Pinned OpenJDK source anchor changed: " + old[:90])
    return source.replace(old, new, 1)


def patch(mobile):
    mobile = Path(mobile)
    bsd = mobile / "src/hotspot/os/bsd/os_bsd.cpp"
    text = bsd.read_text()
    text = replace(text, '#include "classfile/vmSymbols.hpp"',
                   '#include <TargetConditionals.h>\n#include "classfile/vmSymbols.hpp"')
    apple = text.index("#else // __APPLE__")
    start = text.index("    char *pslash;", text.index("  // sysclasspath, java_home, dll_dir", apple))
    end = text.index("    if (!set_boot_path", start)
    text = text[:start] + """#if defined(__IOS__)
    const char* bundle_home = getenv("JAVA_HOME");
    if (bundle_home == nullptr || *bundle_home == 0) {
      vm_exit_during_initialization("The embedding app must supply JAVA_HOME");
    }
    Arguments::set_java_home(bundle_home);
    int written = jio_snprintf(buf, bufsize, "%s/lib", bundle_home);
    if (written < 0 || written >= bufsize) {
      vm_exit_during_initialization("Bundled Java home path is too long");
    }
    Arguments::set_dll_dir(buf);
#else
""" + text[start:end] + "#endif\n" + text[end:]
    text = replace(text, "#ifdef __IOS__\n      ;\n#else\n      MACOS_ONLY(| (exec ? MAP_JIT : 0));",
                   "#if defined(__IOS__) && TARGET_OS_SIMULATOR\n      | (exec ? MAP_JIT : 0);\n#elif defined(__IOS__)\n      ;\n#else\n      MACOS_ONLY(| (exec ? MAP_JIT : 0));")
    bsd.write_text(text)

    runtime_os = mobile / "src/hotspot/share/runtime/os.cpp"
    runtime_os.write_text(replace(runtime_os.read_text(), '"%/Documents/lib/" MODULES_IMAGE_NAME',
                                 '"%/lib/" MODULES_IMAGE_NAME'))

    zero = mobile / "src/hotspot/os_cpu/bsd_zero/os_bsd_zero.cpp"
    text = zero.read_text()
    text = replace(text, '#include "asm/assembler.inline.hpp"',
                   '#include <TargetConditionals.h>\n#include <dlfcn.h>\n#include "asm/assembler.inline.hpp"')
    text = replace(text, "address os::Posix::ucontext_get_pc(const ucontext_t* uc) {\n  ShouldNotCallThis();\n  return nullptr;\n}", """address os::Posix::ucontext_get_pc(const ucontext_t* uc) {
#if defined(__IOS__) && defined(__aarch64__)
  uintptr_t program_counter = uc->uc_mcontext->__ss.__pc;
  return (address)program_counter;
#else
  ShouldNotCallThis();
  return nullptr;
#endif
}""")
    text = replace(text, "void os::Posix::ucontext_set_pc(ucontext_t * uc, address pc) {\n  ShouldNotCallThis();\n}", """void os::Posix::ucontext_set_pc(ucontext_t * uc, address pc) {
#if defined(__IOS__) && defined(__aarch64__)
  uintptr_t program_counter = (uintptr_t)pc;
  uc->uc_mcontext->__ss.__pc = program_counter;
#else
  ShouldNotCallThis();
#endif
}""")
    text = replace(text, "void os::current_thread_enable_wx(WXMode mode) {\n#ifndef __IOS__", """void os::current_thread_enable_wx(WXMode mode) {
#if defined(__IOS__) && TARGET_OS_SIMULATOR
  // Simulator processes use macOS's MAP_JIT contract, although the iOS SDK hides the pthread declaration.
  using ProtectionToggle = void (*)(int);
  static ProtectionToggle toggle = reinterpret_cast<ProtectionToggle>(dlsym(RTLD_DEFAULT, "pthread_jit_write_protect_np"));
  if (toggle != nullptr) toggle(mode == WXWrite ? 0 : 1);
#elif !defined(__IOS__)""")
    zero.write_text(text)

    posix = mobile / "src/hotspot/os/posix/os_posix.cpp"
    text = posix.read_text()
    text = replace(text, "#define ROOT_UID 0", """#ifdef __IOS__
extern "C" void* kototoro_jdk_symbol(const char*);
#endif
#define ROOT_UID 0""")
    for signature in ["void* os::dll_lookup(void* handle, const char* name) {", "void* os::lookup_function(const char* name) {"]:
        text = replace(text, signature, signature + """
#ifdef __IOS__
  if (void* retained = kototoro_jdk_symbol(name)) return retained;
#endif""")
    posix.write_text(text)
    # The first build provides libraries from which we generate the table. This empty definition is replaced
    # before the final libjvm build; no guessed/stale list of JNI exports is ever linked into the app.
    (mobile / "src/hotspot/os/bsd/kototoro_symbols.cpp").write_text(
        'extern "C" void* kototoro_jdk_symbol(const char*) { return nullptr; }\n')


def patch_platform_properties(mobile):
    # Core Java has no IOS enum. Its Darwin filesystem/JAR paths must use the MACOS branch.
    target = Path(mobile) / "src/java.base/share/classes/jdk/internal/util/PlatformProps.java.template"
    text = target.read_text()
    replacements = {
        'static final String CURRENT_OS_STRING = "@@OPENJDK_TARGET_OS@@";':
            'static final String CURRENT_OS_STRING = "@@OPENJDK_TARGET_OS@@" == "ios"'
            ' ? "macos" : "@@OPENJDK_TARGET_OS@@";',
        'static final boolean TARGET_OS_IS_MACOS   = "@@OPENJDK_TARGET_OS@@" == "macos";':
            'static final boolean TARGET_OS_IS_MACOS   = "@@OPENJDK_TARGET_OS@@" == "macos"'
            ' || "@@OPENJDK_TARGET_OS@@" == "ios";',
    }
    for old, new in replacements.items():
        if text.count(new) != 1:
            text = replace(text, old, new)
    if target.read_text() != text:
        target.write_text(text)


def generate_symbols(mobile, libraries):
    """Retain callable native entry points from this exact static build, then let HotSpot find them by name."""
    symbols = set()
    for library in libraries:
        listing = subprocess.check_output(["nm", "-gUj", str(library)], text=True)
        for name in listing.splitlines():
            if re.fullmatch(r"_(?:Java_|JNI_|JNU_|JVM_|JIMAGE_|JDK_|ZIP_|VerifyClass)[A-Za-z0-9_]*", name):
                symbols.add(name[1:])
    if len(symbols) < 300:
        raise RuntimeError("Static build contains too few JNI exports: " + str(len(symbols)))
    ordered = sorted(symbols)
    lines = ["// Generated from nm of this build's OpenJDK libraries; never copied from an external export list.",
             "#include <cstring>", '#include "utilities/globalDefinitions.hpp"', "#ifdef __IOS__", 'extern "C" {']
    # Independent assembler labels avoid conflicting declarations with HotSpot's typed JVM_* prototypes.
    lines += ['void ktr_jdk_entry_' + str(index) + '() __asm__("_' + name + '");' for index, name in enumerate(ordered)]
    lines += ["}", "struct Entry { const char* name; void* address; };", "static const Entry entries[] = {"]
    lines += ['  {"' + name + '", reinterpret_cast<void*>(&ktr_jdk_entry_' + str(index) + ')},' for index, name in enumerate(ordered)]
    lines += ["};", 'extern "C" void* kototoro_jdk_symbol(const char* name) {',
              "  if (name == nullptr) return nullptr;", "  size_t first = 0, last = sizeof(entries) / sizeof(entries[0]);",
              "  while (first < last) {", "    size_t mid = first + (last - first) / 2;",
              "    int order = strcmp(name, entries[mid].name);", "    if (order == 0) return entries[mid].address;",
              "    if (order < 0) last = mid; else first = mid + 1;", "  }", "  return nullptr;", "}",
              "#else", 'extern "C" void* kototoro_jdk_symbol(const char*) { return nullptr; }', "#endif"]
    target = Path(mobile) / "src/hotspot/os/bsd/kototoro_symbols.cpp"
    output = "\n".join(lines) + "\n"
    if target.read_text() != output:
        target.write_text(output)
    return len(ordered)
