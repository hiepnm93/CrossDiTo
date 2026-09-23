"""
PlatformIO pre-build script: native-host compatibility for simulator envs.

- Pin the C standard to gnu17. GCC 15+ defaults to C23, where bool/true/false
  are keywords, and some C libdeps (ricmoo/QRCode 0.0.1) predate C23 and
  typedef their own `bool`. C++ files keep -std=gnu++2a from build_flags.
- Link OpenSSL (libcrypto) on Linux: the simulator's MD5Builder_linux.h uses
  <openssl/md5.h>. macOS uses CommonCrypto and MinGW ships its own shim, so
  the extra lib is Linux-only.
"""

Import("env")

env.Append(CFLAGS=["-std=gnu17"])

import sys

if sys.platform.startswith("linux"):
    env.Append(LIBS=["crypto"])
