#include "KototoroJVM.h"
#include <codecvt>
#include <fstream>
#include <iostream>
#include <locale>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

static std::u16string utf16(const std::string &text) {
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(text);
}

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

static std::u16string exchange(KTRSession *session, const std::u16string &request) {
    uint16_t *response = nullptr;
    size_t length = 0;
    char *error = nullptr;
    const int status = ktr_session_exchange(session, reinterpret_cast<const uint16_t *>(request.data()),
        request.size(), &response, &length, &error);
    const std::string failure = error == nullptr ? "JNI exchange failed" : error;
    ktr_free(error);
    if (status != 0) throw std::runtime_error(failure);
    std::u16string text(reinterpret_cast<const char16_t *>(response), length);
    ktr_free(response);
    return text;
}

int main(int argc, char **argv) {
    try {
        require(argc == 5, "Usage: smoke <java-home> <classpath> <config.json> <directory>");
        char *error = nullptr;
        int status = ktr_vm_start(argv[1], argv[2], nullptr, 0, &error);
        if (status != 0) throw std::runtime_error(error == nullptr ? "VM startup failed" : error);
        require(ktr_vm_start(argv[1], argv[2], nullptr, 0, &error) == 0, "Repeated VM configuration failed");
        require(ktr_vm_start(argv[1], "different-classpath", nullptr, 0, &error) != 0, "Different VM configuration accepted");
        ktr_free(error);
        error = nullptr;
        const auto directory = utf16(argv[4]);
        const auto invalid = u"{\"platformClass\":\"missing.Platform\"}";
        KTRSession *failed = ktr_session_open(reinterpret_cast<const uint16_t *>(invalid),
            std::char_traits<char16_t>::length(invalid), reinterpret_cast<const uint16_t *>(directory.data()),
            directory.size(), &error);
        require(failed == nullptr && error != nullptr, "Missing Java platform did not report an error");
        ktr_free(error);
        error = nullptr;
        std::ifstream input(argv[3]);
        const auto config = utf16(std::string(std::istreambuf_iterator<char>(input), {}));
        KTRSession *session = ktr_session_open(reinterpret_cast<const uint16_t *>(config.data()), config.size(),
            reinterpret_cast<const uint16_t *>(directory.data()), directory.size(), &error);
        if (session == nullptr) throw std::runtime_error(error == nullptr ? "Session open failed" : error);
        auto sources = exchange(session,
            u"{\"version\":1,\"requestId\":\"中文 🐈\\u0000\",\"call\":{\"operation\":\"sources\"}}");
        require(sources.find(u"中文 🐈\\u0000") != std::u16string::npos, "UTF-16 request ID changed");
        require(sources.find(u"MIHON_9007199254740993") != std::u16string::npos, "64-bit source ID changed");
        require(exchange(session, u"{").find(u"INVALID_REQUEST") != std::u16string::npos,
            "Malformed request response missing");
        std::vector<std::thread> workers;
        std::exception_ptr failure;
        std::mutex failure_mutex;
        for (int i = 0; i < 4; ++i) {
            workers.emplace_back([&] {
                try {
                    for (int call = 0; call < 5; ++call) {
                        auto response = exchange(session,
                            u"{\"version\":1,\"requestId\":\"thread\",\"call\":{\"operation\":\"list\","
                            u"\"sourceName\":\"MIHON_9007199254740993\",\"offset\":0}}");
                        require(response.find(u"popular 1") != std::u16string::npos, "Source list failed");
                    }
                } catch (...) {
                    std::lock_guard<std::mutex> lock(failure_mutex);
                    if (failure == nullptr) failure = std::current_exception();
                }
            });
        }
        for (auto &worker : workers) worker.join();
        status = ktr_session_close(session, &error);
        ktr_free(error);
        require(status == 0, "Session close failed");
        if (failure != nullptr) std::rethrow_exception(failure);
        require(ktr_session_close(nullptr, nullptr) == 0, "Null close failed");
        std::cout << "KOTOTORO_JNI_SMOKE_OK: unicode, source IDs, 20 threaded calls, recovery, close\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
