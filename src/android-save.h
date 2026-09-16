#ifndef ANDROID_SAVE_H
#define ANDROID_SAVE_H
/* Called on the engine thread, except the JNI lifecycle request producer. */
extern bool android_save_managed(void);
extern void android_save_new_name(void);
extern bool android_save_commit(cptr temporary);
extern bool android_save_resolve(char* path, size_t size);
extern bool android_save_menu(void);
extern bool android_save_delete(void);
extern bool android_save_mark_dead(void);
extern void android_save_reset(void);
extern void android_save_checkpoint(void);
extern bool android_save_now(void);
extern bool android_save_interrupt(void);
extern void android_save_request(void);
extern void android_save_poll(void);
extern bool android_save_suspended(void);
extern void android_save_serviced(bool success);
extern void android_save_cancel_requests(void);
#endif
