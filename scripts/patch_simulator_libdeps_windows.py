#!/usr/bin/env python3
"""Patch the downloaded simulator libdeps for native Windows (MinGW) builds.

PlatformIO re-downloads libdeps into .pio/libdeps/<env>/ whenever they are
missing, which drops the local Windows portability fixes. Run this script
after `pio pkg install` / the first failed build, then build the simulator
with the MSYS2 UCRT64 toolchain on PATH:

    python scripts/patch_simulator_libdeps_windows.py

All patches are guarded by _WIN32 and are no-ops on other hosts.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIM = ROOT / ".pio" / "libdeps" / "x4-pro-simulator"


def patch(rel: str, old: str, new: str) -> bool:
    path = SIM / rel
    if not path.exists():
        print(f"skip (missing): {rel}")
        return False
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if new.splitlines()[0] in text and old not in text:
        print(f"already patched: {rel}")
        return True
    if old not in text:
        print(f"WARN anchor not found: {rel}")
        return False
    path.write_text(text.replace(old, new, 1), encoding="utf-8", errors="surrogateescape", newline="\n")
    print(f"patched: {rel}")
    return True


def main() -> int:
    ok = True

    # 1. esp_heap_caps.h: MinGW has no posix_memalign.
    ok &= patch(
        "simulator/src/esp_heap_caps.h",
        "inline std::mutex mutex;",
        """inline std::mutex mutex;

#ifdef _WIN32
// MinGW has no posix_memalign. Track _aligned_malloc blocks so release() can
// free them with _aligned_free; both paths already hold the pool mutex.
#define WIN_HEAP_SHIM 1
#endif
""".replace("inline std::mutex mutex;\n\n#ifdef _WIN32", "inline std::mutex mutex;\n\n#ifdef _WIN32"),
    )

    text_path = SIM / "simulator/src/esp_heap_caps.h"
    if text_path.exists():
        s = text_path.read_text(encoding="utf-8", errors="surrogateescape")
        if "winPosixMemalign" not in s and "posix_memalign(&pointer, alignment, bytes)" in s:
            s = s.replace(
                "else if (posix_memalign(&pointer, alignment, bytes) != 0)",
                "else if (winPosixMemalign(&pointer, alignment, bytes) != 0)",
                1,
            )
            s = s.replace(
                "inline std::mutex mutex;",
                """inline std::mutex mutex;

#ifdef _WIN32
#include <malloc.h>
#include <unordered_set>
inline std::unordered_set<void *> &winAlignedAllocs() {
  static std::unordered_set<void *> set;
  return set;
}
inline int winPosixMemalign(void **pointer, size_t alignment, size_t bytes) {
  if (!pointer) return 22;
  void *mem = _aligned_malloc(bytes ? bytes : 1, alignment);
  if (!mem) return 12;
  *pointer = mem;
  winAlignedAllocs().insert(mem);
  return 0;
}
inline void winFree(void *pointer) {
  if (winAlignedAllocs().erase(pointer) != 0)
    _aligned_free(pointer);
  else
    std::free(pointer);
}
#else
inline int winPosixMemalign(void **pointer, size_t alignment, size_t bytes) {
  return posix_memalign(pointer, alignment, bytes);
}
inline void winFree(void *pointer) { std::free(pointer); }
#endif""",
                1,
            )
            s = s.replace("    std::free(pointer);", "    winFree(pointer);")
            text_path.write_text(s, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("patched: esp_heap_caps.h posix_memalign shim")
        else:
            print("esp_heap_caps.h: shim already present or anchor changed")

    # 2. SimHttpFetch.h: replace the POSIX implementation with a failing stub.
    stub_path = SIM / "simulator/src/SimHttpFetch.h"
    if stub_path.exists():
        s = stub_path.read_text(encoding="utf-8", errors="surrogateescape")
        if "Windows host stub" not in s:
            s = (
                "#pragma once\n\n#ifdef _WIN32\n"
                "// Windows host stub: the real implementation is POSIX-only (dirent, fork/wait,\n"
                "// curl subprocess). Simulator smoke tests run offline, so fetch always fails.\n"
                "#include <map>\n#include <string>\n\nnamespace sim_http_fetch {\n\n"
                "struct Response {\n  int statusCode = 0;\n  int curlExitCode = 0;\n  std::string body;\n};\n\n"
                "inline bool fetch(const std::string &, const char *, const std::map<std::string, std::string> &,\n"
                "                  const std::string &, const char *, Response &out) {\n"
                "  out.statusCode = 0;\n  out.curlExitCode = -1;\n  return false;\n}\n\n"
                "}  // namespace sim_http_fetch\n#else\n" + s + "\n#endif  // _WIN32\n"
            )
            stub_path.write_text(s, encoding="utf-8", newline="\n")
            print("patched: SimHttpFetch.h stub")
        else:
            print("SimHttpFetch.h: already patched")

    # 3. SimulatorStackCheck.cpp: dlfcn/unistd are POSIX-only.
    ok &= patch(
        "simulator/src/SimulatorStackCheck.cpp",
        "#include <cstring>\n#include <dlfcn.h>\n#include <unistd.h>\n",
        "#include <cstring>\n#ifndef _WIN32\n#include <dlfcn.h>\n#include <unistd.h>\n#endif\n",
    )

    # 4. SimulatorLifecycle.cpp: setenv/unsetenv compat.
    lifecycle = SIM / "simulator/src/SimulatorLifecycle.cpp"
    if lifecycle.exists():
        s = lifecycle.read_text(encoding="utf-8", errors="surrogateescape")
        if "#define unsetenv" not in s:
            import re

            s = re.sub(r"::mkdir\((.*),\s*\n?\s*0777\)", r"SIM_MKDIR(\1)", s)  # (no-op here, safety)
            s = s.replace(
                '#include "SimulatorLifecycle.h"\n',
                """#include "SimulatorLifecycle.h"

#ifdef _WIN32
#include <cstdlib>
#ifndef setenv
#define setenv(name, value, overwrite) _putenv_s(name, value)
#endif
#ifndef unsetenv
#define unsetenv(name) _putenv_s(name, "")
#endif
#endif
""",
                1,
            )
            lifecycle.write_text(s, encoding="utf-8", newline="\n")
            print("patched: SimulatorLifecycle.cpp env macros")
        else:
            print("SimulatorLifecycle.cpp: already patched")

    # 5. HalStorage.cpp: binary-mode open, mkdir arity, fsync, localtime_r.
    hal = SIM / "simulator/src/HalStorage.cpp"
    if hal.exists():
        import re

        s = hal.read_text(encoding="utf-8", errors="surrogateescape")
        if "SIM_OPEN_FLAGS" not in s:
            s = s.replace(
                '#include "HalStorage.h"\n',
                """#include "HalStorage.h"

#ifdef _WIN32
#include <fcntl.h>
// MSVCRT defaults open() to text mode, where 0x1A means EOF and CRLF is
// translated -- fatal for binary SD-card data. Force binary for every open.
#define SIM_OPEN_FLAGS(f) ((f) | O_BINARY)
#define SIM_MKDIR(path) ::mkdir(path)
#define SIM_FSYNC(fd) _commit(fd)
#define localtime_r(timep, result) localtime_s((result), (timep))
#else
#define SIM_OPEN_FLAGS(f) (f)
#define SIM_MKDIR(path) ::mkdir(path, 0777)
#define SIM_FSYNC(fd) fsync(fd)
#endif
""",
                1,
            )
            s = re.sub(r"::mkdir\((.*?),\s*0777\)", r"SIM_MKDIR(\1)", s)
            s = re.sub(r"::mkdir\((.*?),\s*\n\s*0777\)", r"SIM_MKDIR(\1)", s)
            s = re.sub(r"(?<![_A-Za-z])fsync\(([^)]+)\)", r"SIM_FSYNC(\1)", s)
            s = s.replace("::open(path.c_str(), flags, 0666);", "::open(path.c_str(), SIM_OPEN_FLAGS(flags), 0666);")
            s = re.sub(r"::open\(([^,]+, )((?:O_[A-Z_]+(?:\s*\|\s*O_[A-Z_]+)*))\)", r"::open(\1SIM_OPEN_FLAGS(\2))", s)
            hal.write_text(s, encoding="utf-8", newline="\n")
            print("patched: HalStorage.cpp POSIX compat")
        else:
            print("HalStorage.cpp: already patched")

    # 6. NetworkClient.cpp: winsock.
    nc = SIM / "simulator/src/NetworkClient.cpp"
    if nc.exists():
        s = nc.read_text(encoding="utf-8", errors="surrogateescape")
        if "winsock2.h" not in s:
            import re

            s = re.sub(
                r"#include <(?:arpa/inet\.h|netinet/in\.h|sys/socket\.h|sys/time\.h|unistd\.h)>\n(?:#include <(?:arpa/inet\.h|netinet/in\.h|sys/socket\.h|sys/time\.h|unistd\.h)>\n)*",
                """#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#define SIM_CLOSE ::closesocket
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#define SIM_CLOSE ::close
#endif
""",
                s,
                count=1,
            )
            anchor = 'NetworkClient::NetworkClient(int fd) : impl_(std::make_shared<Impl>(fd)) {}\n\n'
            if anchor in s:
                s = s.replace(
                    anchor,
                    anchor
                    + """int NetworkClient::connect_winsock_guard();
#ifdef _WIN32
namespace {
bool wsaStartupOnce() {
  WSADATA data{};
  return WSAStartup(MAKEWORD(2, 2), &data) == 0;
}
}  // namespace
#endif
""",
                    1,
                )
            s = s.replace("::close(fd);", "SIM_CLOSE(fd);")
            s = s.replace("::close(impl_->fd);", "SIM_CLOSE(impl_->fd);")
            s = s.replace(
                "int NetworkClient::connect(const char *host, uint16_t port) {\n",
                """int NetworkClient::connect(const char *host, uint16_t port) {
#ifdef _WIN32
  static bool wsaReady = [] {
    WSADATA data{};
    return WSAStartup(MAKEWORD(2, 2), &data) == 0;
  }();
  if (!wsaReady)
    return 0;
#endif
""",
                1,
            )
            s = s.replace(
                "::send(impl_->fd, buf + writtenTotal,",
                "::send(impl_->fd, reinterpret_cast<const char *>(buf + writtenTotal),",
                1,
            )
            nc.write_text(s, encoding="utf-8", newline="\n")
            print("patched: NetworkClient.cpp winsock")
        else:
            print("NetworkClient.cpp: already patched")

    # 7. WebServer.cpp / WebSocketsServer.cpp: winsock includes, closes, setsockopt casts.
    for rel in ["simulator/src/WebServer.cpp", "simulator/src/WebSocketsServer.cpp"]:
        p = SIM / rel
        if not p.exists():
            continue
        s = p.read_text(encoding="utf-8", errors="surrogateescape")
        if "winsock2.h" in s:
            print(f"{rel}: already patched")
            continue
        import re

        s = re.sub(
            r"#include <(?:arpa/inet\.h|fcntl\.h|netinet/in\.h|sys/socket\.h|sys/time\.h|unistd\.h)>\n(?:#include <(?:arpa/inet\.h|fcntl\.h|netinet/in\.h|sys/socket\.h|sys/time\.h|unistd\.h)>\n)*",
            """#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#define SIM_CLOSE ::closesocket
#define SIM_DUP(fd) (fd)
#define SHUT_RDWR SD_BOTH
#else
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#define SIM_CLOSE ::close
#define SIM_DUP(fd) ::dup(fd)
#endif
""",
            s,
            count=1,
        )
        cls = "WebSocketsServer" if "WebSocketsServer" in rel else "WebServer"
        s = s.replace(f"void {cls}SIM_CLOSE() {{", f"void {cls}::close() {{")
        s = s.replace("::close(", "SIM_CLOSE(")
        s = s.replace(f"void {cls}SIM_CLOSE()", f"void {cls}::close()")
        s = re.sub(
            r"setsockopt\((\w+(?:->\w+)?), (SOL_SOCKET, \w+), &(\w+), sizeof\(\3\)\)",
            r"setsockopt(\1, \2, reinterpret_cast<const char *>(&\3), sizeof(\3))",
            s,
        )
        s = s.replace("::dup(impl_->currentClient)", "SIM_DUP(impl_->currentClient)")
        p.write_text(s, encoding="utf-8", newline="\n")
        print(f"patched: {rel}")

    # 8. PNGdec inflate.c: 'uint' is not a type under C23.
    ok &= patch(
        "PNGdec/src/inflate.c",
        "const Bytef *dictionary, uint dictLength",
        "const Bytef *dictionary, uInt dictLength",
    )

    # 9. QRCode qrcode.h: bool typedef clashes with C23 keywords.
    qrh = SIM / "QRCode/src/qrcode.h"
    if qrh.exists():
        s = qrh.read_text(encoding="utf-8", errors="surrogateescape")
        a = "typedef unsigned char bool;\nstatic const bool false = 0;\nstatic const bool true = 1;"
        if a in s:
            s = s.replace(
                a,
                "#if !defined(__cplusplus) && (!defined(__STDC_VERSION__) || __STDC_VERSION__ < 199901L)\n"
                + a
                + "\n#endif",
                1,
            )
            qrh.write_text(s, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("patched: QRCode qrcode.h bool guard")
        else:
            print("QRCode qrcode.h: already patched or anchor changed")

    # 10. MD5Builder Windows implementation.
    md5dir = SIM / "simulator/src"
    md5w = md5dir / "MD5Builder_windows.h"
    if not md5w.exists():
        print("NOTE: MD5Builder_windows.h missing; copy it from the CrossDiTo repo docs or re-add.")
    md5b = md5dir / "MD5Builder.h"
    if md5b.exists():
        s = md5b.read_text(encoding="utf-8", errors="surrogateescape")
        if "_WIN32" not in s:
            s = s.replace(
                '#elif defined(__linux__)\n#include "MD5Builder_linux.h"\n#else',
                '#elif defined(__linux__)\n#include "MD5Builder_linux.h"\n#elif defined(_WIN32)\n#include "MD5Builder_windows.h"\n#else',
                1,
            )
            md5b.write_text(s, encoding="utf-8", newline="\n")
            print("patched: MD5Builder.h dispatch")
        else:
            print("MD5Builder.h: already patched")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
