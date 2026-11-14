# termlib (ConnectBot) – patched copy

Source: https://github.com/connectbot/termlib, tag 0.3.14, Apache License 2.0 (see LICENSE).

Change for ServerControl: when text is selected, there is no separate "Copy" button beside the
selection any more; "Copy" is the first entry of the ⋮ menu (`Terminal.kt`).

The native library (libvterm + JNI) is not built here: `libjni_cb_term.so` is taken unchanged
from the published `org.connectbot:termlib:0.3.14` AAR at build time (same version as the sources).
