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

#ifdef __cplusplus
}
#endif
#endif
