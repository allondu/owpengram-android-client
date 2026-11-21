/*
 * Per-account switch for the in-tgnet Cloudflared carrier.
 *
 * When enabled, ConnectionSocket::openConnection() does not dial TCP. It
 * creates an AF_UNIX socketpair, keeps one end for epoll/read/write and hands
 * the other end to NativeCarrier.java, which pumps it over wss://<host>/.
 */
#ifndef NATIVE_CARRIER_H
#define NATIVE_CARRIER_H

#include <atomic>
#include <cstdint>

namespace NativeCarrier {
    constexpr int MAX_ACCOUNTS = 64;

    // Function-local static in an inline function: one shared array across all
    // translation units, no extra .cpp and no CMakeLists change. The flag is
    // written from the UI thread and read on the tgnet network thread.
    inline std::atomic<bool> *flags() {
        static std::atomic<bool> enabled[MAX_ACCOUNTS] = {};
        return enabled;
    }

    inline void setEnabled(int32_t instanceNum, bool enabled) {
        if (instanceNum >= 0 && instanceNum < MAX_ACCOUNTS) {
            flags()[instanceNum].store(enabled);
        }
    }

    inline bool isEnabled(int32_t instanceNum) {
        return instanceNum >= 0 && instanceNum < MAX_ACCOUNTS && flags()[instanceNum].load();
    }
}

#endif
