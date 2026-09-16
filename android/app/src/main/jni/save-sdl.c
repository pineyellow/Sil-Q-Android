/* Android save catalogue and crash-safe snapshot publication.
 * A .sil.meta manifest selects one of two ordinary engine savefiles.
 * Never overwrite the selected slot: fsync the other slot before publishing
 * its manifest with rename. The previous manifest is retained for recovery.
 */
#include "angband.h"
#include "android-save.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct {
    uint32_t magic, version, slot, bytes, hash, dead;
    int32_t depth;
    uint32_t race, house;
    int64_t saved;
    char name[64];
} save_summary;

#define SUMMARY_MAGIC 0x53494c41u
static s32b last_turn = -1;
static int last_depth = -1;
static bool saving, disabled;

static void save_path(char* out, size_t n, cptr base, cptr extension)
{
    strnfmt(out, n, "%s%s", base, extension);
}

static bool exists(cptr path) { return access(path, F_OK) == 0; }

static bool sync_directory(void)
{
    int fd = open(ANGBAND_DIR_SAVE, O_RDONLY | O_DIRECTORY);
    if (fd < 0) return FALSE;
    bool ok = fsync(fd) == 0;
    close(fd);
    return ok;
}

static bool fingerprint(cptr path, uint32_t* bytes, uint32_t* hash)
{
    unsigned char buf[8192];
    FILE* f = fopen(path, "rb");
    if (!f) return FALSE;
    size_t n;
    uint32_t count = 0, h = 2166136261u;
    while ((n = fread(buf, 1, sizeof(buf), f)) != 0) {
        if (count + n > 64u * 1024u * 1024u) { fclose(f); return FALSE; }
        count += n;
        for (size_t i = 0; i < n; i++) h = (h ^ buf[i]) * 16777619u;
    }
    bool ok = !ferror(f) && count >= 4;
    fclose(f);
    *bytes = count; *hash = h;
    return ok;
}

static bool read_summary(cptr path, save_summary* s)
{
    FILE* f = fopen(path, "rb");
    if (!f) return FALSE;
    bool ok = fread(s, sizeof(*s), 1, f) == 1 && fgetc(f) == EOF;
    fclose(f);
    if (!ok || s->magic != SUMMARY_MAGIC || s->version != 1 || s->slot > 1
        || s->dead > 1 || s->depth < 0 || s->depth > 100
        || s->race >= z_info->p_max || s->house >= z_info->c_max
        || !memchr(s->name, 0, sizeof(s->name))) return FALSE;
    for (const unsigned char* p = (const unsigned char*)s->name; *p; p++)
        if (*p < 32 || *p == 127) return FALSE;
    return TRUE;
}

static bool write_summary(cptr path, const save_summary* s)
{
    FILE* f = fopen(path, "wb");
    if (!f) return FALSE;
    bool ok = fwrite(s, sizeof(*s), 1, f) == 1 && fflush(f) == 0
        && fsync(fileno(f)) == 0;
    if (fclose(f) != 0) ok = FALSE;
    return ok;
}

static void slot_path(char* out, size_t n, cptr base, const save_summary* s)
{
    save_path(out, n, base, s->slot ? ".1" : ".0");
}

static bool valid_snapshot(cptr base, const save_summary* s)
{
    char path[1024];
    uint32_t bytes, hash;
    slot_path(path, sizeof(path), base, s);
    return fingerprint(path, &bytes, &hash) && bytes == s->bytes && hash == s->hash;
}

static bool retired(cptr base)
{
    char path[1024];
    save_path(path, sizeof(path), base, ".retired");
    return exists(path);
}

static bool unused_identity(cptr base)
{
    /* Earlier builds reserved empty identities during startup/name entry.
     * Ignore only bare, empty reservations; keep damaged real saves visible. */
    struct stat st;
    if (stat(base, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size != 0)
        return FALSE;
    static const char* extensions[] = { ".0", ".1", ".meta", ".backup",
        ".new", ".meta.new", ".backup.new" };
    for (size_t i = 0; i < sizeof(extensions) / sizeof(*extensions); i++) {
        char path[1024];
        save_path(path, sizeof(path), base, extensions[i]);
        if (exists(path)) return FALSE;
    }
    return TRUE;
}

static bool resolve_summary(cptr base, save_summary* s)
{
    char path[1024];
    if (retired(base)) return FALSE;
    save_path(path, sizeof(path), base, ".meta");
    if (read_summary(path, s)) {
        if (s->dead) return FALSE;
        if (valid_snapshot(base, s)) return TRUE;
    }
    save_path(path, sizeof(path), base, ".backup");
    return read_summary(path, s) && !s->dead && valid_snapshot(base, s);
}

bool android_save_managed(void)
{
    size_t n = strlen(savefile);
    return n > 4 && strcmp(savefile + n - 4, ".sil") == 0;
}

void android_save_new_name(void)
{
    /* Character names are labels, not file identities. Equal names coexist. */
    char path[1024];
    strnfmt(path, sizeof(path), "%s/character-XXXXXX.sil", ANGBAND_DIR_SAVE);
    int fd = mkstemps(path, 4);
    if (fd < 0) { savefile[0] = 0; return; }
    close(fd);
    my_strcpy(savefile, path, sizeof(savefile));
}

bool android_save_commit(cptr temporary)
{
    char path[1024], manifest[1024], staged[1024], backup[1024];
    save_summary previous, next;
    if (disabled || retired(savefile)) return FALSE;
    bool have_previous = resolve_summary(savefile, &previous);
    memset(&next, 0, sizeof(next));
    next.magic = SUMMARY_MAGIC; next.version = 1;
    next.slot = have_previous ? 1 - previous.slot : 0;
    next.dead = p_ptr->is_dead;
    next.depth = p_ptr->depth;
    next.race = p_ptr->prace; next.house = p_ptr->phouse;
    next.saved = (int64_t)time(NULL);
    my_strcpy(next.name, op_ptr->full_name, sizeof(next.name));
    int fd = open(temporary, O_RDONLY);
    if (fd < 0) return FALSE;
    bool ok = fsync(fd) == 0;
    close(fd);
    if (!ok || !fingerprint(temporary, &next.bytes, &next.hash)) return FALSE;
    slot_path(path, sizeof(path), savefile, &next);
    if (rename(temporary, path) != 0 || !sync_directory()) return FALSE;
    save_path(manifest, sizeof(manifest), savefile, ".meta");
    save_path(staged, sizeof(staged), savefile, ".meta.new");
    save_path(backup, sizeof(backup), savefile, ".backup");
    if (have_previous) {
        char temp_backup[1024];
        save_path(temp_backup, sizeof(temp_backup), savefile, ".backup.new");
        if (!write_summary(temp_backup, &previous)
            || rename(temp_backup, backup) != 0) return FALSE;
    }
    if (!write_summary(staged, &next) || rename(staged, manifest) != 0
        || !sync_directory()) return FALSE;
    last_turn = playerturn; last_depth = p_ptr->depth;
    return TRUE;
}

bool android_save_resolve(char* path, size_t size)
{
    save_summary s;
    if (!resolve_summary(savefile, &s)) return FALSE;
    slot_path(path, size, savefile, &s);
    return TRUE;
}

static bool retire(void)
{
    char path[1024];
    if (!android_save_managed()) return TRUE;
    save_path(path, sizeof(path), savefile, ".retired");
    int fd = open(path, O_CREAT | O_WRONLY, 0600);
    if (fd < 0) return FALSE;
    bool ok = fsync(fd) == 0;
    close(fd);
    return ok && sync_directory();
}

bool android_save_delete(void)
{
    /* Publish a tombstone first so interrupted deletion cannot resurrect a run. */
    if (!retire()) return FALSE;
    disabled = TRUE;
    android_save_cancel_requests();
    if (!android_save_managed()) return TRUE; /* Never delete bundled tutorial. */
    static const char* extensions[] = { "", ".0", ".1", ".meta", ".backup",
        ".new", ".meta.new", ".backup.new" };
    bool ok = TRUE;
    for (size_t i = 0; i < sizeof(extensions) / sizeof(*extensions); i++) {
        char path[1024];
        save_path(path, sizeof(path), savefile, extensions[i]);
        if (unlink(path) != 0 && errno != ENOENT) ok = FALSE;
    }
    /* Keep the tiny tombstone; even leftover recovery files are never loaded. */
    return sync_directory() && ok;
}

bool android_save_mark_dead(void)
{
    if (android_save_managed() && !disabled) {
        if (retire()) disabled = TRUE;
        else { plog("Could not retire the dead character's save."); return FALSE; }
    }
    return TRUE;
}

void android_save_reset(void)
{
    last_turn = -1; last_depth = -1; saving = disabled = FALSE;
    android_save_cancel_requests();
}

bool android_save_now(void)
{
    if (saving || disabled || !character_generated || !character_dungeon
        || p_ptr->is_dead || p_ptr->game_type != 0) return FALSE;
    saving = TRUE;
    handle_stuff();
    bool ok = save_player();
    saving = FALSE;
    return ok;
}

void android_save_checkpoint(void)
{
    android_save_poll();
    /* A leap has committed its landing; finish that before suspending. */
    if (p_ptr->leaping) return;
    bool suspension = android_save_interrupt();
    if (suspension) {
        /* Normal interruption preserves partial crafting and cancellation rules. */
        disturb(0, 0);
        p_ptr->command_new = 0;
        Term_flush();
    }
    bool due = last_turn < 0 || playerturn - last_turn >= 30
        || p_ptr->depth != last_depth;
    /* These timers are not persisted as active operations in the PC format.
     * Backgrounding interrupts them normally; periodic saves can wait. */
    if (!suspension && (p_ptr->smithing || p_ptr->fletching)) due = FALSE;
    bool failed = FALSE;
    if (!disabled && (suspension || due) && p_ptr->game_type == 0) {
        failed = !android_save_now();
        /* Retry at the next interval/lifecycle request, not every free command. */
        if (failed) { last_turn = playerturn; last_depth = p_ptr->depth; }
    }
    if (suspension) android_save_serviced(!failed);
    while (android_save_suspended()) {
        Term_xtra(TERM_XTRA_EVENT, TRUE);
        /* Focus loss and background callbacks may arrive separately. No game
         * state changes while parked here, so the completed save covers both. */
        if (android_save_interrupt()) android_save_serviced(!failed);
    }
    if (failed) msg_print("Saving failed. Your previous save has been kept.");
}

typedef struct { char base[1024]; save_summary summary; bool valid; } save_entry;
static int newest_first(const void* a, const void* b)
{
    const save_entry* x = a; const save_entry* y = b;
    if (x->summary.saved != y->summary.saved)
        return x->summary.saved > y->summary.saved ? -1 : 1;
    return strcmp(x->base, y->base);
}

bool android_save_menu(void)
{
    DIR* directory = opendir(ANGBAND_DIR_SAVE);
    save_entry* entries = NULL;
    size_t count = 0;
    struct dirent* ent;
    if (directory) {
        while ((ent = readdir(directory)) != NULL) {
            size_t len = strlen(ent->d_name);
            /* Reserved identity file also exposes incomplete/corrupt saves. */
            if (len <= 4 || strcmp(ent->d_name + len - 4, ".sil")) continue;
            char base[1024];
            path_build(base, sizeof(base), ANGBAND_DIR_SAVE, ent->d_name);
            if (retired(base) || unused_identity(base)) continue;
            save_entry* grown = realloc(entries, (count + 1) * sizeof(*entries));
            if (!grown) break;
            entries = grown;
            save_entry* e = &entries[count++];
            memset(e, 0, sizeof(*e));
            my_strcpy(e->base, base, sizeof(e->base));
            char meta[1024];
            save_path(meta, sizeof(meta), base, ".meta");
            /* Cheap preview; validate the snapshot only when selected. */
            e->valid = read_summary(meta, &e->summary) && !e->summary.dead;
            if (!e->valid) {
                save_path(meta, sizeof(meta), base, ".backup");
                e->valid = read_summary(meta, &e->summary) && !e->summary.dead;
            }
        }
        closedir(directory);
    }
    if (count > 1) qsort(entries, count, sizeof(*entries), newest_first);
    int selected = 0;
    bool chosen = FALSE;
    bool previous_hide_cursor = hide_cursor;
    bool previous_cursor;
    Term_get_cursor(&previous_cursor);
    hide_cursor = TRUE;
    Term_set_cursor(FALSE);
    screen_save();
    for (;;) {
        int rows = MIN(16, Term->hgt - 7);
        int start = selected / rows * rows;
        Term_clear();
        c_put_str(TERM_L_BLUE, "Load character", 2, 4);
        if (!count) put_str("No saved characters.", 4, 4);
        for (int i = start; i <= (int)count && i < start + rows; i++) {
            char line[256];
            if (i == (int)count) strnfmt(line, sizeof(line), "%c) Back", 'a' + i - start);
            else if (!entries[i].valid)
                strnfmt(line, sizeof(line), "%c) Unavailable or corrupted save", 'a' + i - start);
            else {
                save_summary* s = &entries[i].summary;
                if (s->house == 0)
                    strnfmt(line, sizeof(line), "%c) %s, Houseless %s (%d ft)",
                        'a' + i - start, s->name, p_name + p_info[s->race].name,
                        s->depth * 50);
                else
                    strnfmt(line, sizeof(line), "%c) %s, %s of %s (%d ft)",
                        'a' + i - start, s->name, p_name + p_info[s->race].name,
                        c_name + c_info[s->house].alt_name, s->depth * 50);
            }
            line[MIN((int)sizeof(line) - 1, Term->wid - 8)] = 0;
            c_put_str(i == selected ? TERM_L_BLUE : TERM_WHITE, line,
                (count ? 5 : 6) + i - start, 4);
        }
        put_str("Swipe to select; tap to open. Escape: Back", Term->hgt - 2, 4);
        Term_fresh();
        char key = inkey();
        if (key == ESCAPE) break;
        if (key == '8') { if (selected > 0) selected--; continue; }
        if (key == '2') { if (selected < (int)count) selected++; continue; }
        if (key >= 'a' && key < 'a' + rows && start + key - 'a' <= (int)count)
            selected = start + key - 'a';
        else if (key != '\r' && key != '\n' && key != ' ') continue;
        if (selected == (int)count) break;
        my_strcpy(savefile, entries[selected].base, sizeof(savefile));
        char path[1024];
        if (android_save_resolve(path, sizeof(path))) { chosen = TRUE; break; }
        msg_print("This save is unavailable or corrupted.");
        message_flush();
    }
    screen_load();
    hide_cursor = previous_hide_cursor;
    Term_set_cursor(previous_cursor);
    free(entries);
    if (!chosen) savefile[0] = 0;
    Term_flush();
    return chosen;
}
