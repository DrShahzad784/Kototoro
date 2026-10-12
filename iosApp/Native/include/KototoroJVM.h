#ifndef KOTOTORO_JVM_H
#define KOTOTORO_JVM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KTRSession KTRSession;
int ktr_vm_available(void);

/* The VM remains alive until process exit. A second configuration is rejected. */
int ktr_vm_start(const char *java_home, const char *classpath,
                 const char *const *additional_options, size_t option_count, char **error);
KTRSession *ktr_session_open(const uint16_t *config, size_t config_length,
                            const uint16_t *directory, size_t directory_length, char **error);
int ktr_session_exchange(KTRSession *session, const uint16_t *request, size_t request_length,
                         uint16_t **response, size_t *response_length, char **error);
/* Close releases Java/global references without destroying the VM. The owner must finish exchanges before close. */
int ktr_session_close(KTRSession *session, char **error);
void ktr_free(void *memory);

/*
 * Android WebView for extensions (org.skepsun.kototoro.ios.compat.IosWebKit): its native
 * nativeCommand(operation, view, payload JSON) is answered by [handler], which returns a ktr_malloc'd UTF-16 result
 * (or NULL for none) and its length. Install after ktr_vm_start and before the session opens; page events go back
 * through ktr_webkit_dispatch, from any thread that Java is not waiting on.
 */
typedef uint16_t *(*KTRWebKitHandler)(const uint16_t *operation, size_t operation_length, int64_t view,
                                      const uint16_t *payload, size_t payload_length, size_t *result_length);
int ktr_webkit_install(KTRWebKitHandler handler, char **error);
int ktr_webkit_dispatch(int64_t view, const uint16_t *event, size_t event_length,
                        const uint16_t *payload, size_t payload_length,
                        uint16_t **result, size_t *result_length, char **error);
void *ktr_malloc(size_t size);

#ifdef __cplusplus
}
#endif
#endif
