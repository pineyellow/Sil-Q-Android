/* Minimal Android SDL terminal frontend. The game owns all engine state. */
#include <SDL.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <math.h>
#include <unistd.h>
#include "angband.h"
#include "android-save.h"

#define COLS 80
#define ROWS 24
#define CELL_W 16
#define CELL_H 32
#define SOURCE_TILE_SIZE 16

static SDL_Window* window;
static SDL_Renderer* renderer;
static SDL_Texture* font;
static SDL_Texture* tiles;
static SDL_Texture* rage_tiles;
static term screen_term;
static byte cells[ROWS][COLS];
static byte colors[ROWS][COLS];
static byte tile_attrs[ROWS][COLS];
static byte tile_chars[ROWS][COLS];
static byte terrain_attrs[ROWS][COLS];
static byte terrain_chars[ROWS][COLS];
static bool graphic_cells[ROWS][COLS];
static int cursor_x, cursor_y;
static bool cursor_visible;
static bool cursor_big;
static bool background;
static bool frame_dirty = TRUE;
static bool resume_frame_pending;
static void wake_renderer(void);
static SDL_atomic_t save_lifecycle_mask, save_request_serial;
static int save_seen_serial;
static bool save_pending, close_after_save;

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSaveLifecycle(JNIEnv* env, jobject self,
    jint reason, jboolean inactive)
{
    (void)env; (void)self;
    int before, after;
    do {
        before = SDL_AtomicGet(&save_lifecycle_mask);
        after = inactive ? before | reason : before & ~reason;
    } while (!SDL_AtomicCAS(&save_lifecycle_mask, before, after));
    if (inactive && before != after) SDL_AtomicAdd(&save_request_serial, 1);
    wake_renderer();
}

void android_save_request(void) { SDL_AtomicAdd(&save_request_serial, 1); }
bool android_save_suspended(void)
{
    return background || SDL_AtomicGet(&save_lifecycle_mask) != 0;
}
bool android_save_interrupt(void) { return save_pending; }
void android_save_poll(void)
{
    if (character_generated && p_ptr->is_dead) {
        android_save_mark_dead();
        save_pending = FALSE;
        close_after_save = FALSE;
    }
    int serial = SDL_AtomicGet(&save_request_serial);
    if (serial == save_seen_serial) return;
    save_seen_serial = serial;
    if (character_generated && character_dungeon && p_ptr->playing && !p_ptr->is_dead) {
        save_pending = TRUE;
        screen_term.key_head = screen_term.key_tail;
    }
}
void android_save_serviced(bool success)
{
    save_pending = FALSE;
    if (close_after_save && success) quit(NULL);
    close_after_save = FALSE;
}
void android_save_cancel_requests(void)
{
    save_pending = close_after_save = FALSE;
    save_seen_serial = SDL_AtomicGet(&save_request_serial);
}
static bool note_active;
#if defined(SILQ_DEBUG_TUTORIAL_SKILLS)
bool android_debug_tutorial_enabled(void)
{
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return FALSE;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = cls ? (*env)->GetMethodID(env, cls,
        "isDebugTutorialEnabled", "()Z") : NULL;
    bool enabled = method && (*env)->CallBooleanMethod(env, activity, method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        enabled = FALSE;
    }
    if (cls) (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return enabled;
}
#endif
static bool more_active;
static bool pointer_down;
static bool pointer_menu;
static int pointer_x, pointer_y, pointer_travel;
static char pointer_swipe_key;
static int pointer_menu_depth;
static Uint32 pointer_repeat_at;
static bool allocation_input_active;

void android_allocation_input(bool active)
{
    allocation_input_active = active;
    pointer_down = FALSE;
}

extern int sil_main(int argc, char** argv);
extern bool game_in_progress;

/* UI thread reads only atomics and posts SDL events; engine state stays here. */
static SDL_atomic_t dpad_event_type;
/* A wake event carries no game command and is safe from the Java thread. */
static void wake_renderer(void)
{
    int type = SDL_AtomicGet(&dpad_event_type);
    if (!type) return;
    SDL_Event event;
    SDL_zero(event);
    event.type = (Uint32)type;
    SDL_PushEvent(&event);
}

static SDL_atomic_t dpad_available;
static SDL_atomic_t overlay_input_blocked;
static SDL_atomic_t dpad_press_token;
static SDL_atomic_t dpad_context;
static SDL_atomic_t dpad_repeat_pending;

void android_interrupt_dpad(void)
{
    SDL_AtomicAdd(&dpad_press_token, 1);
}
static SDL_atomic_t requested_tiles = { 1 };
/* Negative request ID while waiting; 0/1 once answered. */
static SDL_atomic_t confirmation_answer;
static bool inventory_active;
static bool firing_active;
static bool throwing_active;
static bool horn_active;
static bool horn_vertical;
static bool interacting_active;
static int firing_range;
static SDL_atomic_t fire_epoch, fire_request;
static int last_fire_state = -1;
static int last_stealth_state = -1;
static int last_singing_state = -1;
static int last_has_songs = -1;
static int last_has_bow = -1;
static int selected_quiver = 1;
static int last_quiver = -1, last_quiver_switch = -1;
static int last_horn_vertical = -1;
static void update_fire_controls(void);
static SDL_atomic_t inventory_open_requested;
static SDL_atomic_t ground_open_requested, ground_epoch;
static bool ground_browse_requested;
static void update_ground_controls(void);
static SDL_atomic_t character_sheet_epoch;
static bool character_sheet_active;
static bool character_controls_active;
static bool character_creation_active;
static int last_dpad_available = -1;
static int last_save_available = -1;
static SDL_atomic_t camera_speed = { 3 }, camera_zoom_setting = { 1500 };
static float camera_x, camera_y, camera_zoom = 1.5f;
static bool camera_ready, camera_detached;
static int camera_depth = -1;
static Uint32 camera_time;
static void cancel_camera_touch(void);
static void android_show_monster(int monster);

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSetGraphicsTiles(
    JNIEnv* env, jobject self, jboolean enabled)
{
    (void)env;
    (void)self;
    SDL_AtomicSet(&requested_tiles, enabled != JNI_FALSE);
    /* Wake the game thread even while its keyboard input is blocked by Settings. */
    int type = SDL_AtomicGet(&dpad_event_type);
    if (type) {
        SDL_Event event;
        SDL_zero(event);
        event.type = (Uint32)type;
        event.user.code = 0;
        SDL_PushEvent(&event);
    }
}

static void set_graphics_mode(bool enabled)
{
    arg_graphics = use_graphics = enabled ? GRAPHICS_MICROCHASM : GRAPHICS_NONE;
    use_bigtile = enabled;
    use_transparency = enabled;
    ANGBAND_GRAF = enabled ? "tiles" : "none";
}

static bool character_creation_close_active;

static bool dungeon_input(void)
{
    return character_dungeon && character_icky == 0 && game_in_progress
        && p_ptr->playing && !p_ptr->is_dead;
}

/* Track footer input separately from controls shared with nested screens. */
void android_character_sheet_input(bool active)
{
    character_sheet_active = active;
    int epoch = SDL_AtomicAdd(&character_sheet_epoch, 1) + 1;
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = (*env)->GetMethodID(env, cls, "onNativeCharacterSheet", "(ZI)V");
    if (method) (*env)->CallVoidMethod(env, activity, method,
        (character_controls_active || character_creation_close_active) ? JNI_TRUE : JNI_FALSE, epoch);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void android_character_sheet_controls(bool active)
{
    character_controls_active = active;
    pointer_down = FALSE;
    if (!active && dungeon_input()) {
        /* Flush the restored dungeon after leaving the modal screen. Keep the
         * camera position and discard time spent in the modal screen. */
        camera_time = SDL_GetTicks();
        cancel_camera_touch();
        Term_fresh();
    }
    android_character_sheet_input(FALSE);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeCharacterInput(
    JNIEnv* env, jobject self, jboolean close, jint epoch)
{
    (void)env;
    (void)self;
    int type = SDL_AtomicGet(&dpad_event_type);
    if (!type) return;
    SDL_Event event;
    SDL_zero(event);
    event.type = (Uint32)type;
    event.user.code = close ? ESCAPE : '@';
    event.user.windowID = close ? (Uint32)epoch : (Uint32)SDL_AtomicGet(&dpad_context);
    SDL_PushEvent(&event);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeOpenAbilities(JNIEnv* env, jobject self)
{
    (void)env; (void)self;
    int type = SDL_AtomicGet(&dpad_event_type);
    if (!type) return;
    SDL_Event event;
    SDL_zero(event);
    event.type = (Uint32)type;
    event.user.code = '\t';
    event.user.windowID = (Uint32)SDL_AtomicGet(&dpad_context);
    SDL_PushEvent(&event);
}

static void update_dpad_availability(void)
{
    static int last_more = -1;
    /* A settings dialog can pause SDL while its live D-pad preview stays visible.
     * Focus/background input is cancelled separately from control visibility. */
    int available = dungeon_input();
    int can_save = available && p_ptr->game_type == 0;
    if (available == last_dpad_available && can_save == last_save_available
        && more_active == last_more) return;
    last_more = more_active;
    last_dpad_available = available;
    last_save_available = can_save;
    SDL_AtomicSet(&dpad_available, available);
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);

    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = (*env)->GetMethodID(env, cls,
        "onNativeDpadAvailability", "(ZZZ)V");
    if (method) (*env)->CallVoidMethod(env, activity, method,
        available ? JNI_TRUE : JNI_FALSE, can_save ? JNI_TRUE : JNI_FALSE,
        more_active ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

JNIEXPORT jint JNICALL
Java_com_pineyellow_silq_SilActivity_nativeBeginDpadPress(JNIEnv* env, jobject self)
{
    (void)env;
    (void)self;
    return SDL_AtomicAdd(&dpad_press_token, 1) + 1;
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeCancelDpadInput(JNIEnv* env, jobject self)
{
    (void)env;
    (void)self;
    SDL_AtomicAdd(&dpad_press_token, 1);
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSetOverlayInputBlocked(
    JNIEnv* env, jobject self, jboolean blocked)
{
    (void)env;
    (void)self;
    SDL_AtomicSet(&overlay_input_blocked, blocked != JNI_FALSE);
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    wake_renderer();
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSendDpadKey(
    JNIEnv* env, jobject self, jint command, jint token, jboolean repeat)
{
    SDL_Event event;
    int type = SDL_AtomicGet(&dpad_event_type);
    (void)env;
    (void)self;
    if (!type || command < '1' || command > '9'
        || SDL_AtomicGet(&confirmation_answer) < 0
        || !SDL_AtomicGet(&dpad_available)
        || SDL_AtomicGet(&overlay_input_blocked)
        || (repeat && token != SDL_AtomicGet(&dpad_press_token))) return JNI_FALSE;
    /* A slow game turn must not accumulate a backlog of held movement. */
    if (repeat && !SDL_AtomicCAS(&dpad_repeat_pending, 0, 1)) return JNI_TRUE;
    SDL_zero(event);
    event.type = (Uint32)type;
    event.user.windowID = (Uint32)SDL_AtomicGet(&dpad_context);
    event.user.code = command;
    event.user.data1 = (void*)(intptr_t)token;
    event.user.data2 = (void*)(intptr_t)(repeat != JNI_FALSE);
    if (SDL_PushEvent(&event) != 1 && repeat)
        SDL_AtomicSet(&dpad_repeat_pending, 0);
    return JNI_TRUE;
}

void android_character_creation_close(bool active)
{
    character_creation_close_active = active;
    pointer_down = FALSE;
    android_character_sheet_input(character_sheet_active);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeAbandonGame(JNIEnv* env, jobject self)
{
    (void)env;
    (void)self;
    int type = SDL_AtomicGet(&dpad_event_type);
    if (!type) return;
    SDL_Event event;
    SDL_zero(event);
    event.type = (Uint32)type;
    event.user.code = KTRL(']');
    event.user.windowID = (Uint32)SDL_AtomicGet(&dpad_context);
    SDL_PushEvent(&event);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSaveAndQuit(JNIEnv* env, jobject self)
{
    (void)env; (void)self;
    int type = SDL_AtomicGet(&dpad_event_type);
    if (!type) return;
    SDL_Event event;
    SDL_zero(event);
    event.type = (Uint32)type;
    event.user.code = KTRL('X');
    event.user.windowID = (Uint32)SDL_AtomicGet(&dpad_context);
    SDL_PushEvent(&event);
}

/* Only menu/modal input: never turn a swipe into a dungeon command. */
void android_character_creation(bool active)
{
    character_creation_active = active;
    pointer_down = FALSE;
}

static bool menu_input(void)
{
    return !inkey_flag && (!character_dungeon || character_icky > 0
        || !game_in_progress);
}

static void log_message(cptr message)
{
    __android_log_write(ANDROID_LOG_ERROR, "SilQ", message ? message : "Quit");
}

static void load_font(void)
{
    SDL_Surface* bitmap = SDL_LoadBMP("font.bmp");
    if (!bitmap) quit_fmt("Cannot load font: %s", SDL_GetError());
    SDL_SetColorKey(bitmap, SDL_TRUE, SDL_MapRGB(bitmap->format, 0, 0, 0));
    if (font) SDL_DestroyTexture(font);
    font = SDL_CreateTextureFromSurface(renderer, bitmap);
    SDL_FreeSurface(bitmap);
    if (!font) quit_fmt("Cannot create font texture: %s", SDL_GetError());
    SDL_SetTextureBlendMode(font, SDL_BLENDMODE_BLEND);
}

static SDL_Surface* rgba_surface(SDL_Surface* source)
{
    SDL_Surface* result = SDL_ConvertSurfaceFormat(source, SDL_PIXELFORMAT_RGBA32, 0);
    if (!result) quit_fmt("Cannot convert tileset: %s", SDL_GetError());
    return result;
}

static void tint_surface_for_rage(SDL_Surface* surface, Uint8 blank_r,
    Uint8 blank_g, Uint8 blank_b)
{
    if (SDL_MUSTLOCK(surface) && SDL_LockSurface(surface) < 0)
        quit_fmt("Cannot lock rage tileset: %s", SDL_GetError());

    for (int y = 0; y < surface->h; y++) {
        Uint32* row = (Uint32*)((Uint8*)surface->pixels + y * surface->pitch);
        for (int x = 0; x < surface->w; x++) {
            Uint8 r, g, b, a;
            SDL_GetRGBA(row[x], surface->format, &r, &g, &b, &a);
            if (r == blank_r && g == blank_g && b == blank_b) continue;
            int luma = (299 * r + 587 * g + 114 * b) / 1000;
            int red = (int)(luma * RAGE_TINT_RED_COEFF);
            int green = (int)(luma * RAGE_TINT_GREEN_COEFF);
            int blue = (int)(luma * RAGE_TINT_BLUE_COEFF);
            if (red > 255) red = 255;
            if (green > 255) green = 255;
            if (blue > 255) blue = 255;
            row[x] = SDL_MapRGBA(surface->format,
                (Uint8)red, (Uint8)green, (Uint8)blue, a);
        }
    }

    if (SDL_MUSTLOCK(surface)) SDL_UnlockSurface(surface);
}

static void load_tiles(void)
{
    char path[1024];
    path_build(path, sizeof(path), ANGBAND_DIR_XTRA, "graf/16x16.bmp");
    SDL_Surface* bitmap = SDL_LoadBMP(path);
    if (!bitmap) quit_fmt("Cannot load tileset: %s", SDL_GetError());

    SDL_Surface* normal = rgba_surface(bitmap);
    SDL_Surface* rage = rgba_surface(bitmap);
    SDL_FreeSurface(bitmap);

    Uint8 blank_r, blank_g, blank_b;
    SDL_GetRGB(*(Uint32*)normal->pixels, normal->format,
        &blank_r, &blank_g, &blank_b);
    tint_surface_for_rage(rage, blank_r, blank_g, blank_b);

    SDL_SetColorKey(normal, SDL_TRUE,
        SDL_MapRGB(normal->format, blank_r, blank_g, blank_b));
    SDL_SetColorKey(rage, SDL_TRUE,
        SDL_MapRGB(rage->format, blank_r, blank_g, blank_b));

    SDL_Texture* new_tiles = SDL_CreateTextureFromSurface(renderer, normal);
    SDL_Texture* new_rage_tiles = SDL_CreateTextureFromSurface(renderer, rage);
    SDL_FreeSurface(normal);
    SDL_FreeSurface(rage);
    if (!new_tiles || !new_rage_tiles)
        quit_fmt("Cannot create tileset textures: %s", SDL_GetError());

    if (tiles) SDL_DestroyTexture(tiles);
    if (rage_tiles) SDL_DestroyTexture(rage_tiles);
    tiles = new_tiles;
    rage_tiles = new_rage_tiles;
    SDL_SetTextureBlendMode(tiles, SDL_BLENDMODE_BLEND);
    SDL_SetTextureBlendMode(rage_tiles, SDL_BLENDMODE_BLEND);
}

static void draw_tile(SDL_Texture* texture, byte a, byte c,
    const SDL_Rect* destination)
{
    SDL_Rect source = {
        (c & 0x3F) * SOURCE_TILE_SIZE,
        (a & 0x3F) * SOURCE_TILE_SIZE,
        SOURCE_TILE_SIZE,
        SOURCE_TILE_SIZE
    };
    SDL_RenderCopy(renderer, texture, &source, destination);
}

static void draw_graphic_cell(int x, int y)
{
    SDL_Texture* texture = (p_ptr && p_ptr->rage && rage_tiles) ? rage_tiles : tiles;
    int width = use_bigtile ? 2 * CELL_W : CELL_W;
    SDL_Rect destination = { x * CELL_W, y * CELL_H, width, CELL_H };
    byte a = tile_attrs[y][x];
    byte c = tile_chars[y][x];
    byte ta = terrain_attrs[y][x];
    byte tc = terrain_chars[y][x];
    bool terrain_is_graphic = (ta & 0x80) && (tc & 0x80);

    if (terrain_is_graphic) draw_tile(texture, ta, tc, &destination);

    if (a & GRAPHICS_GLOW_MASK)
        draw_tile(texture, misc_to_attr[ICON_GLOW], misc_to_char[ICON_GLOW],
            &destination);

    if (!terrain_is_graphic || (a & 0x3F) != (ta & 0x3F)
        || (c & 0x3F) != (tc & 0x3F))
        draw_tile(texture, a, c, &destination);

    if (c & GRAPHICS_ALERT_MASK)
        draw_tile(texture, misc_to_attr[ICON_ALERT], misc_to_char[ICON_ALERT],
            &destination);
}

#include "camera-sdl.h"

/* Publish only immutable control state; all targeting stays on this thread. */
static void update_fire_controls(void)
{
    static bool was_fletching;
    bool fletching = character_dungeon && p_ptr->fletching != 0;
    if (fletching != was_fletching) {
        was_fletching = fletching;
        SDL_AtomicAdd(&dpad_context, 1);
        SDL_AtomicAdd(&dpad_press_token, 1);
        pointer_down = FALSE;
        cancel_camera_touch();
    }
    int state = 0;
    if (dungeon_input()) {
        state = 1;
        if (!background && !SDL_AtomicGet(&overlay_input_blocked)
            && (!inventory_active || firing_active) && !more_active) {
            if (firing_active) state = interacting_active ? 5 : horn_active ? 7 : throwing_active ? 4 : 3;
            else if (fletching) state = 8;
            else if (inkey_flag && !inkey_scan)
                state = inventory[INVEN_BOW].k_idx ? 2 : 6;
        }
    }
    int stealth = dungeon_input() && p_ptr->stealth_mode;
    int singing_now = dungeon_input() && p_ptr->song1 != SNG_NOTHING;
    int has_songs = 0;
    if (dungeon_input()) {
        for (int i = 0; i < SNG_WOVEN_THEMES; i++)
            if (p_ptr->active_ability[S_SNG][i]) { has_songs = 1; break; }
    }
    int can_switch = state == 3
        && inventory[selected_quiver == 1 ? INVEN_QUIVER2 : INVEN_QUIVER1].k_idx;
    int can_aim_vertical = state == 7 && horn_vertical;
    int has_bow = dungeon_input() && inventory[INVEN_BOW].k_idx != 0;
    if (state == last_fire_state && stealth == last_stealth_state
        && singing_now == last_singing_state && has_songs == last_has_songs && selected_quiver == last_quiver
        && can_switch == last_quiver_switch && can_aim_vertical == last_horn_vertical
        && has_bow == last_has_bow) return;
    last_has_bow = has_bow;
    last_fire_state = state;
    last_stealth_state = stealth;
    last_singing_state = singing_now;
    last_has_songs = has_songs;
    last_quiver = selected_quiver;
    last_quiver_switch = can_switch;
    last_horn_vertical = can_aim_vertical;
    int epoch = SDL_AtomicAdd(&fire_epoch, 1) + 1;
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = (*env)->GetMethodID(env, cls, "onNativeFireState", "(IIZZIZZZZ)V");
    if (method) (*env)->CallVoidMethod(env, activity, method, state, epoch,
        stealth ? JNI_TRUE : JNI_FALSE, singing_now ? JNI_TRUE : JNI_FALSE,
        selected_quiver, can_switch ? JNI_TRUE : JNI_FALSE,
        can_aim_vertical ? JNI_TRUE : JNI_FALSE, has_songs ? JNI_TRUE : JNI_FALSE,
        has_bow ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeFireInput(
    JNIEnv* env, jobject self, jint epoch, jboolean cancel)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + (cancel ? 1 : 0));
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeInteractInput(
    JNIEnv* env, jobject self, jint epoch)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + 2);
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeStealthInput(
    JNIEnv* env, jobject self, jint epoch)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + 3);
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSingInput(
    JNIEnv* env, jobject self, jint epoch)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + 4);
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeQuiverInput(
    JNIEnv* env, jobject self, jint epoch)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + 5);
    wake_renderer();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeHornDirectionInput(
    JNIEnv* env, jobject self, jint epoch, jboolean down)
{
    (void)env; (void)self;
    if (epoch == SDL_AtomicGet(&fire_epoch))
        SDL_AtomicCAS(&fire_request, 0, epoch * 8 + (down ? 7 : 6));
    wake_renderer();
}

/* Only the game thread selects the ammunition for the pending shot. */
int android_fire_quiver(int quiver)
{
    if (quiver == 1 || quiver == 2) selected_quiver = quiver;
    return selected_quiver;
}

void android_fire_aim(bool active, int range)
{
    firing_active = active;
    firing_range = range;
    SDL_AtomicSet(&fire_request, 0);
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    camera_time = SDL_GetTicks();
    Term_flush();
    update_fire_controls();
}

bool android_interact_direction(int* direction)
{
    if (map_tap_direction) {
        *direction = map_tap_direction;
        return TRUE;
    }
    interacting_active = TRUE;
    android_fire_aim(TRUE, 1);
    bool selected = get_rep_dir(direction);
    android_fire_aim(FALSE, 1);
    interacting_active = FALSE;
    return selected;
}

/* Finish following a completed move before a note or message freezes the
 * camera. Preserve detached views and targeting/modal camera positions. */
static void settle_camera_before_pause(void)
{
    if (camera_active() && !camera_detached && !inventory_active
        && !more_active && !firing_active
        && SDL_AtomicGet(&confirmation_answer) >= 0
        && (!camera_follow_pending || p_ptr->px != camera_follow_px
            || p_ptr->py != camera_follow_py)) {
        camera_follow_pending = FALSE;
        camera_x = p_ptr->px + 0.5f;
        camera_y = p_ptr->py + 0.5f;
    }
}

void android_message_pause(bool active)
{
    if (active) {
        if (!note_active) settle_camera_before_pause();
    }
    more_active = active;
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    camera_time = SDL_GetTicks();
    if (!active) {
        Term_flush();
    }
    update_dpad_availability();
}

/* Called after a new or loaded dungeon is ready, before its initial redraw.
 * Menu input must not determine the first dungeon frame, and a new character
 * may start at the same depth as the previous character. */
void android_camera_enter_dungeon(void)
{
    camera_ready = FALSE;
    camera_detached = FALSE;
    camera_time = SDL_GetTicks();
    cancel_camera_touch();
    android_camera_clear_effects();
}

typedef struct {
    int active, zoom, px, py, depth;
    s32b turn;
    float x, y;
} FrameView;
static FrameView presented_view;

static FrameView frame_view(void)
{
    FrameView view = {0};
    view.active = camera_active();
    if (view.active) {
        view.zoom = SDL_AtomicGet(&camera_zoom_setting);
        view.px = p_ptr->px;
        view.py = p_ptr->py;
        view.depth = p_ptr->depth;
        view.turn = turn;
        view.x = camera_x;
        view.y = camera_y;
    }
    return view;
}

static bool view_changed(void)
{
    FrameView view = frame_view();
    return view.active != presented_view.active || view.zoom != presented_view.zoom
        || view.px != presented_view.px || view.py != presented_view.py
        || view.depth != presented_view.depth || view.turn != presented_view.turn
        || view.x != presented_view.x || view.y != presented_view.y;
}

/* Retain smooth follow support, but stop scheduling frames when it settles. */
static bool camera_follow_moving(void)
{
    return camera_active() && camera_ready && !camera_detached
        && !camera_follow_pending && !inventory_active && !more_active
        && !firing_active && !note_active
        && SDL_AtomicGet(&confirmation_answer) >= 0
        && (fabsf(camera_x - p_ptr->px - 0.5f) > 0.001f
            || fabsf(camera_y - p_ptr->py - 0.5f) > 0.001f);
}

static bool presentation_ready(void)
{
    if (android_save_suspended() || save_pending
        || SDL_AtomicGet(&save_request_serial) != save_seen_serial
        || !renderer || !font || !tiles) return FALSE;
    if (resume_frame_pending && character_dungeon && p_ptr->playing && !p_ptr->is_dead
        && (!dungeon_input() || !inkey_flag || inkey_scan
            || inventory_active || firing_active || note_active || more_active))
        return FALSE;
    return TRUE;
}

static void present(void)
{
    /* Keep restoration requests pending until a frame can actually be drawn. */
    frame_dirty = TRUE;
    /* Java reports pause/focus loss before SDL's background event. Keep the
     * last presented frame throughout cancellation and saving, including a
     * quick return to foreground before the save has finished. */
    if (android_save_suspended() || save_pending
        || SDL_AtomicGet(&save_request_serial) != save_seen_serial) {
        resume_frame_pending = TRUE;
        return;
    }
    if (!presentation_ready()) return;
    if (resume_frame_pending) {
        /* presentation_ready() waits for modal cancellation to settle. */
        camera_time = SDL_GetTicks();
        resume_frame_pending = FALSE;
    }
    update_camera_canvas();
    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
    SDL_RenderClear(renderer);
    bool free_camera = camera_active();
    if (free_camera) draw_camera();
    for (int y = 0; y < ROWS; y++) {
        for (int x = 0; x < COLS; x++) {
            if (free_camera && y >= ROW_MAP && y < ROWS - 1
                && x >= COL_MAP) continue;
            if (graphic_cells[y][x]) {
                draw_graphic_cell(x, y);
                continue;
            }
            unsigned int c = cells[y][x];
            if (c == 0 || c == ' ') continue;
            byte* rgb = angband_color_table[colors[y][x]];
            SDL_SetTextureColorMod(font, rgb[1], rgb[2], rgb[3]);
            SDL_Rect source = { (c % 16) * CELL_W, (c / 16) * CELL_H, CELL_W, CELL_H };
            SDL_Rect dest = { x * CELL_W, y * CELL_H, CELL_W, CELL_H };
            SDL_RenderCopy(renderer, font, &source, &dest);
        }
    }
    if (cursor_visible && !free_camera && !character_sheet_active) {
        SDL_Rect rect = { cursor_x * CELL_W, cursor_y * CELL_H,
            cursor_big ? 2 * CELL_W : CELL_W, CELL_H };
        SDL_SetRenderDrawColor(renderer, 255, 255, 0, 255);
        SDL_RenderDrawRect(renderer, &rect);
    }
    SDL_RenderPresent(renderer);
    presented_view = frame_view();
    frame_dirty = FALSE;
}

static errr text_sdl(int x, int y, int n, byte a, cptr s)
{
    frame_dirty = TRUE;
    if (y < 0 || y >= ROWS) return 1;
    for (int i = 0; i < n && x + i < COLS; i++) {
        if (x + i < 0) continue;
        cells[y][x + i] = (byte)s[i];
        colors[y][x + i] = a;
        graphic_cells[y][x + i] = FALSE;
    }
    return 0;
}

static errr wipe_sdl(int x, int y, int n)
{
    frame_dirty = TRUE;
    if (y < 0 || y >= ROWS) return 1;
    for (int i = 0; i < n && x + i < COLS; i++) {
        if (x + i >= 0) {
            cells[y][x + i] = ' ';
            graphic_cells[y][x + i] = FALSE;
        }
    }
    return 0;
}

static errr pict_sdl(int x, int y, int n, const byte* ap, const char* cp,
    const byte* tap, const char* tcp)
{
    frame_dirty = TRUE;
    if (y < 0 || y >= ROWS) return 1;
    for (int i = 0; i < n && x + i < COLS; i++) {
        int column = x + i;
        if (column < 0) continue;
        graphic_cells[y][column] = TRUE;
        tile_attrs[y][column] = ap[i];
        tile_chars[y][column] = (byte)cp[i];
        terrain_attrs[y][column] = tap[i];
        terrain_chars[y][column] = (byte)tcp[i];
        if (use_bigtile && column + 1 < COLS) {
            graphic_cells[y][column + 1] = FALSE;
            cells[y][column + 1] = ' ';
        }
    }
    return 0;
}

static errr cursor_sdl(int x, int y)
{
    frame_dirty = TRUE;
    cursor_x = x;
    cursor_y = y;
    cursor_big = FALSE;
    return 0;
}

static errr big_cursor_sdl(int x, int y)
{
    frame_dirty = TRUE;
    cursor_x = x;
    cursor_y = y;
    cursor_big = TRUE;
    return 0;
}

static void key_sdl(const SDL_KeyboardEvent* event)
{
    SDL_Keycode sym = event->keysym.sym;
    SDL_Keymod mod = event->keysym.mod;
    int key = 0;
    switch (sym) {
    case SDLK_UP: key = '8'; break;
    case SDLK_DOWN: key = '2'; break;
    case SDLK_LEFT: key = '4'; break;
    case SDLK_RIGHT: key = '6'; break;
    case SDLK_RETURN: case SDLK_KP_ENTER: key = '\r'; break;
    case SDLK_ESCAPE: case SDLK_AC_BACK: key = 27; break;
    case SDLK_BACKSPACE: key = 8; break;
    case SDLK_TAB: key = '\t'; break;
    default:
        if (sym >= SDLK_SPACE && sym <= SDLK_z) {
            key = sym;
            if (sym >= SDLK_a && sym <= SDLK_z) {
                if (mod & KMOD_CTRL) key = sym & 31;
                else if ((!!(mod & KMOD_SHIFT)) != (!!(mod & KMOD_CAPS))) key -= 32;
            }
        }
        break;
    }
    if (key) Term_keypress(key);
}

static void handle_event(const SDL_Event* event, bool discard_input)
{
    /* Input alone does not change the frame. Terminal hooks invalidate changed
     * content, and pump_events tracks camera changes. Dirtying every motion
     * event here submits duplicate menu frames throughout a swipe. Surface
     * restoration events below still present explicitly. */
    android_save_poll();
    discard_input = discard_input || android_save_suspended() || android_save_interrupt();
    bool blocked = SDL_AtomicGet(&overlay_input_blocked) != 0;
    if (blocked || discard_input) {
        pointer_down = FALSE;
        cancel_camera_touch();
    }
    if (event->type == (Uint32)SDL_AtomicGet(&dpad_event_type)) {
        if (event->user.code == 0) return;
        if (event->user.code == '@' || event->user.code == '\t' || event->user.code == ESCAPE
            || event->user.code == KTRL(']') || event->user.code == KTRL('X')) {
            if (discard_input || blocked || background || more_active
                || screen_term.key_head != screen_term.key_tail) return;
            if ((event->user.code == '@' || event->user.code == '\t' || event->user.code == KTRL(']') || event->user.code == KTRL('X'))
                && dungeon_input() && inkey_flag
                && !inkey_scan && !inventory_active && !firing_active
                && event->user.windowID == (Uint32)SDL_AtomicGet(&dpad_context)) {
                Term_keypress((char)event->user.code);
            } else if (event->user.code == ESCAPE
                && (character_controls_active || character_creation_close_active)
                && event->user.windowID == (Uint32)SDL_AtomicGet(&character_sheet_epoch)) {
                if (character_creation_close_active) {
                    extern bool android_birth_cancelled;
                    android_birth_cancelled = TRUE;
                }
                Term_keypress(ESCAPE);
            }
            return;
        }
        bool repeat = (intptr_t)event->user.data2 != 0;
        if (repeat) SDL_AtomicSet(&dpad_repeat_pending, 0);
        if (more_active) {
            /* Only a fresh press in this message pause may dismiss it. Never
             * let movement repeats clear messages or move after dismissal. */
            if (!repeat && !discard_input && !blocked && !background
                && event->user.windowID == (Uint32)SDL_AtomicGet(&dpad_context)
                && screen_term.key_head == screen_term.key_tail) {
                SDL_AtomicAdd(&dpad_press_token, 1);
                Term_keypress(' ');
            }
            return;
        }
        if (!discard_input && !blocked && !background && !more_active
            && dungeon_input()
            /* A direction completes aiming; never queue held repeats behind it. */
            && (!(firing_active || p_ptr->fletching)
                || (!repeat && screen_term.key_head == screen_term.key_tail))
            && event->user.windowID == (Uint32)SDL_AtomicGet(&dpad_context)
            && (!repeat || ((int)(intptr_t)event->user.data1
                    == SDL_AtomicGet(&dpad_press_token)
                && inkey_flag && !inkey_scan
                && screen_term.key_head == screen_term.key_tail)))
        {
            if (!firing_active) camera_resume_follow();
            Term_keypress((char)event->user.code);
        }
        return;
    }
    discard_input = discard_input || blocked;
    if (!discard_input && camera_touch(event)) return;
    switch (event->type) {
    case SDL_QUIT:
        if (character_generated && character_dungeon && p_ptr->playing && !p_ptr->is_dead) {
            close_after_save = TRUE;
            android_save_request();
        } else quit(NULL);
        break;
    case SDL_APP_WILLENTERBACKGROUND:
        android_save_request();
        cancel_camera_touch();
        pointer_down = FALSE;
        background = TRUE;
        update_dpad_availability();
        break;
    case SDL_APP_DIDENTERFOREGROUND:
        background = FALSE;
        update_dpad_availability();
        present();
        break;
    case SDL_RENDER_DEVICE_RESET:
        load_font();
        load_tiles();
        present();
        break;
    case SDL_WINDOWEVENT:
        if (event->window.event == SDL_WINDOWEVENT_FOCUS_LOST) {
            android_save_request();
            cancel_camera_touch();
            pointer_down = FALSE;
        }
        if (event->window.event == SDL_WINDOWEVENT_EXPOSED
            || event->window.event == SDL_WINDOWEVENT_SIZE_CHANGED) present();
        break;
    case SDL_KEYDOWN:
        pointer_down = FALSE;
        if (!discard_input) {
            if (!more_active && !firing_active && !character_controls_active)
                camera_resume_follow();
            if (firing_active && !interacting_active && !horn_active && event->key.keysym.sym != SDLK_f
                && event->key.keysym.sym != SDLK_ESCAPE) break;
            key_sdl(&event->key);
        }
        break;
    case SDL_MOUSEBUTTONDOWN:
        if (event->button.which == SDL_TOUCH_MOUSEID && camera_active() && !more_active) break;
        if (event->button.button == SDL_BUTTON_LEFT) {
            pointer_down = !discard_input;
            pointer_menu = menu_input();
            pointer_x = event->button.x;
            pointer_y = event->button.y;
            pointer_travel = 0;
            pointer_swipe_key = 0;
            pointer_menu_depth = character_icky;
        }
        break;
    case SDL_MOUSEMOTION:
        if (event->motion.which == SDL_TOUCH_MOUSEID && camera_active() && !more_active) break;
        if (pointer_down) {
            int distance = SDL_max(abs(event->motion.x - pointer_x),
                abs(event->motion.y - pointer_y));
            pointer_travel = SDL_max(pointer_travel, distance);
            int dx = event->motion.x - pointer_x;
            int dy = event->motion.y - pointer_y;
            if (!pointer_swipe_key && pointer_menu && menu_input()
                && !more_active && !background
                && ((abs(dy) >= 32 && abs(dy) > abs(dx) * 2)
                    || (allocation_input_active
                        && abs(dx) >= 32 && abs(dx) > abs(dy) * 2))
                && screen_term.key_head == screen_term.key_tail) {
                pointer_swipe_key = abs(dx) > abs(dy)
                    ? (dx < 0 ? '4' : '6') : (dy < 0 ? '8' : '2');
                Term_keypress(pointer_swipe_key);
                pointer_repeat_at = SDL_GetTicks() + 300;
            }
        }
        break;
    case SDL_MOUSEBUTTONUP:
        if (event->button.which == SDL_TOUCH_MOUSEID && camera_active() && !more_active) break;
        if (event->button.button == SDL_BUTTON_LEFT && pointer_down) {
            int dx = event->button.x - pointer_x;
            int dy = event->button.y - pointer_y;
            pointer_down = FALSE;
            pointer_travel = SDL_max(pointer_travel, SDL_max(abs(dx), abs(dy)));
            if (discard_input || pointer_swipe_key) break;
            /* Logical coordinates keep thresholds independent of display size.
             * A repeating swipe already sent its key on motion.
             * A drag never also confirms; other horizontal swipes are release-only. */
            if (pointer_travel <= 12) {
                if (more_active || !tap_dungeon(event->button.x, event->button.y))
                    Term_keypress('\r');
            }
            else if (!more_active && pointer_menu && menu_input()
                && abs(dy) >= 32 && abs(dy) > abs(dx) * 2)
                Term_keypress(dy < 0 ? '8' : '2');
            else if ((character_controls_active || character_creation_active) && !more_active
                && pointer_menu && menu_input()
                && abs(dx) >= 32 && abs(dx) > abs(dy) * 2)
                Term_keypress(dx < 0 ? '4' : '6');
        }
        break;
    }
}

static void apply_graphics_setting(void)
{
    bool enabled = SDL_AtomicGet(&requested_tiles) != 0;
    if (enabled == (use_graphics != GRAPHICS_NONE)) return;
    if (!z_info || !f_info || !k_info || !r_info || !flavor_info) return;
    /* Saved modal screens contain the old glyphs and map geometry. Apply on
     * returning to normal command input instead of overwriting those screens. */
    if (character_icky || (character_dungeon
        && (!dungeon_input() || !inkey_flag || inkey_scan))) return;
    set_graphics_mode(enabled);
    reset_visuals(TRUE);
    if (character_dungeon) {
        if (a_info[ART_MORGOTH_3].cur_num == 1) {
            r_info[R_IDX_MORGOTH].x_attr = r_info[R_IDX_MORGOTH_NO_CROWN].x_attr;
            r_info[R_IDX_MORGOTH].x_char = r_info[R_IDX_MORGOTH_NO_CROWN].x_char;
        }
        cursor_big = FALSE;
        verify_panel();
        do_cmd_redraw();
    }
}

static void pump_events(bool wait, bool discard_input)
{
    SDL_Event event;

    if (!character_dungeon) camera_ready = FALSE;
    if (!camera_active()) cancel_camera_touch();
    if (!discard_input) apply_graphics_setting();
    update_dpad_availability();
    update_fire_controls();
    /* Message pauses cancel an existing swipe on entry. Fresh taps must keep
     * their down state until release so they can dismiss the -more- prompt. */
    if (more_active) pointer_swipe_key = 0;
    if (pointer_down && (discard_input || background
        || SDL_AtomicGet(&overlay_input_blocked)
        || (pointer_menu && (!menu_input() || pointer_menu_depth != character_icky))))
        pointer_down = FALSE;
    /* Events wake this wait immediately. The bounded idle timeout is only a
     * fallback for state changes without an event, not a redraw timer. */
    bool can_draw = !discard_input && presentation_ready();
    int timeout = can_draw && camera_follow_moving() ? 16 : 250;
    if (can_draw && (frame_dirty || view_changed())) timeout = 0;
    if (pointer_down && pointer_swipe_key) {
        Sint32 remaining = (Sint32)(pointer_repeat_at - SDL_GetTicks());
        timeout = SDL_min(timeout, SDL_max(0, remaining));
    }
    if (wait && SDL_WaitEventTimeout(&event, timeout))
        handle_event(&event, discard_input);
    while (SDL_PollEvent(&event)) handle_event(&event, discard_input);
    android_save_poll();
    /* Process release/cancellation first. Never accumulate repeats while the
     * engine is busy, and never send a catch-up burst after a slow frame. */
    if (pointer_down && pointer_swipe_key && !discard_input && !background
        && !more_active && !SDL_AtomicGet(&overlay_input_blocked)
        && menu_input() && pointer_menu_depth == character_icky
        && screen_term.key_head == screen_term.key_tail
        && (Sint32)(SDL_GetTicks() - pointer_repeat_at) >= 0) {
        Term_keypress(pointer_swipe_key);
        pointer_repeat_at = SDL_GetTicks() + 100;
    }
    int fire = SDL_AtomicSet(&fire_request, 0);
    if (fire && fire / 8 == SDL_AtomicGet(&fire_epoch)
        && !discard_input && !background && !SDL_AtomicGet(&overlay_input_blocked)
        && !more_active && dungeon_input()
        && screen_term.key_head == screen_term.key_tail) {
        int action = fire % 8;
        if (firing_active) {
            if (action == 1) Term_keypress(ESCAPE);
            else if (action == 0 && !interacting_active && !throwing_active
                && !horn_active && inventory[INVEN_BOW].k_idx) Term_keypress('f');
            else if (horn_active && horn_vertical && (action == 6 || action == 7))
                Term_keypress(action == 6 ? '<' : '>');
            else if (action == 5 && !interacting_active && !throwing_active && !horn_active
                && inventory[selected_quiver == 1 ? INVEN_QUIVER2 : INVEN_QUIVER1].k_idx) {
                selected_quiver = 3 - selected_quiver;
                update_fire_controls();
            }
        } else if (p_ptr->fletching) {
            /* Automatic work has no floating action controls. */
        } else if (inkey_flag && !inkey_scan && !inventory_active) {
            if (action == 2) Term_keypress('/');
            else if (action == 3) Term_keypress('S');
            else if (action == 4) Term_keypress('s');
            else if (action == 0 && inventory[INVEN_BOW].k_idx) {
                if (!inventory[selected_quiver == 1 ? INVEN_QUIVER1 : INVEN_QUIVER2].k_idx
                    && inventory[selected_quiver == 1 ? INVEN_QUIVER2 : INVEN_QUIVER1].k_idx)
                    selected_quiver = 3 - selected_quiver;
                Term_keypress(selected_quiver == 2 ? 'F' : 'f');
            }
        }
    }
    if (SDL_AtomicSet(&inventory_open_requested, 0) && !discard_input
        && !SDL_AtomicGet(&overlay_input_blocked) && !background
        && dungeon_input() && !firing_active && inkey_flag && !inkey_scan
        && screen_term.key_head == screen_term.key_tail) {
        SDL_AtomicAdd(&dpad_press_token, 1);
        Term_keypress('i');
    }
    update_ground_controls();
    int ground_request = SDL_AtomicSet(&ground_open_requested, 0);
    if (ground_request && ground_request == SDL_AtomicGet(&ground_epoch)
        && !discard_input && !background && !SDL_AtomicGet(&overlay_input_blocked)
        && dungeon_input() && !inventory_active && !firing_active && !more_active
        && inkey_flag && !inkey_scan && !p_ptr->fletching
        && screen_term.key_head == screen_term.key_tail) {
        int index = cave_o_idx[p_ptr->py][p_ptr->px];
        if (index && o_list[index].tval != TV_NOTE) {
            ground_browse_requested = TRUE;
            Term_keypress('i');
        } else if (cave_up_stairs_bold(p_ptr->py, p_ptr->px)
            || cave_down_stairs_bold(p_ptr->py, p_ptr->px)
            || cave_forge_bold(p_ptr->py, p_ptr->px)) {
            /* Use the same deferred command as tapping the player's tile. */
            map_tap_x = map_tap_px = p_ptr->px;
            map_tap_y = map_tap_py = p_ptr->py;
            map_tap_depth = p_ptr->depth;
            map_tap_monster = 0; map_tap_inspect = FALSE;
            map_tap_pending = TRUE;
            SDL_AtomicAdd(&dpad_press_token, 1);
            Term_keypress(KTRL('\\'));
        }
    }
    if (!discard_input) apply_graphics_setting();
    if (!discard_input) {
        if (frame_dirty || view_changed() || camera_follow_moving()) present();
    }
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeConfirmationResult(
    JNIEnv* env, jobject self, jint id, jboolean confirmed)
{
    (void)env; (void)self;
    if (id > 0) SDL_AtomicCAS(&confirmation_answer, -id, confirmed ? 1 : 0);
    wake_renderer();
}

/* kind: 0 confirmation, 1 note, 2 monster description. */
static bool android_text_dialog(cptr prompt, int kind)
{
    if (android_save_interrupt()) return FALSE;
    static int next_id;
    int id = next_id = next_id == 2147483647 ? 1 : next_id + 1;
    SDL_AtomicSet(&confirmation_answer, -id);
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    /* Show current engine drawing without entering the terminal prompt path.
     * Existing inventory/modal screens remain visible; dungeon cameras freeze. */
    Term_fresh();
    /* A camera-state change does not dirty terminal cells. Term_fresh() can
     * therefore skip presenting, leaving the opening note over a menu-era
     * frame. Render explicitly before Java freezes the background. */
    present();
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    bool shown = FALSE;
    if (env && activity) {
        jclass cls = (*env)->GetObjectClass(env, activity);
        jmethodID method = cls ? (*env)->GetMethodID(env, cls,
            kind == 2 ? "showMonsterDialog" : kind == 1 ? "showNoteDialog"
                : "showConfirmationDialog", "(Ljava/lang/String;I)V") : NULL;
        const unsigned char* bytes = (const unsigned char*)(prompt ? prompt : "");
        size_t length = strlen((const char*)bytes);
        jchar* chars = SDL_malloc((length + 1) * sizeof(jchar));
        if (chars) for (size_t i = 0; i < length; i++) chars[i] = bytes[i];
        jstring text = method && chars ? (*env)->NewString(env, chars, (jsize)length) : NULL;
        SDL_free(chars);
        if (text) {
            (*env)->CallVoidMethod(env, activity, method, text, id);
            shown = !(*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, text);
        }
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        if (cls) (*env)->DeleteLocalRef(env, cls);
        (*env)->DeleteLocalRef(env, activity);
    }
    if (!shown) SDL_AtomicCAS(&confirmation_answer, -id, 0);
    /* Keep lifecycle and renderer events serviced while Java owns input.
     * In particular, do not queue movement to execute after dismissal. */
    while (SDL_AtomicGet(&confirmation_answer) < 0) {
        if (android_save_interrupt()) {
            SDL_AtomicCAS(&confirmation_answer, -id, 0);
            break;
        }
        pump_events(TRUE, TRUE);
    }
    bool accepted = SDL_AtomicGet(&confirmation_answer) == 1;
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    Term_flush();
    camera_time = SDL_GetTicks();
    if (kind) return accepted;
    inkey_base = inkey_xtra = inkey_flag = inkey_scan = FALSE;
    msg_flag = FALSE;
    prt("", 0, 0);
    return accepted;
}

bool android_get_confirmation(cptr prompt)
{
    return android_text_dialog(prompt, FALSE);
}

void android_show_note(cptr text)
{
    note_active = TRUE;
    settle_camera_before_pause();
    android_text_dialog(text, TRUE);
    note_active = FALSE;
}

#include "inventory-sdl.h"
#include "songs-sdl.h"

/* Replace only the input presentation at the core command's aim step.
 * Item selection, curse handling, unequipping and turn costs remain in
 * do_cmd_throw(), in their original order. Escape returns to that command. */
bool android_throw_aim(int* direction, int range)
{
    if (inventory_action) inv_hide();
    throwing_active = TRUE;
    android_fire_aim(TRUE, range);
    bool aimed = get_aim_dir(direction, range);
    android_fire_aim(FALSE, range);
    throwing_active = FALSE;
    return aimed;
}

/* Horns use the same target selection, but have no range limit or quiver. */
bool android_horn_aim(int* direction, bool vertical)
{
    if (inventory_action) inv_hide();
    horn_active = TRUE;
    horn_vertical = vertical;
    android_fire_aim(TRUE, 0);
    bool aimed = get_aim_dir(direction, 0);
    android_fire_aim(FALSE, 0);
    horn_active = FALSE;
    horn_vertical = FALSE;
    return aimed;
}

static bool android_can_exchange(int monster)
{
    if (monster <= 0 || monster >= mon_max || !mon_list[monster].r_idx
        || !mon_list[monster].ml
        || !p_ptr->innate_ability[S_STL][STL_EXCHANGE_PLACES]
        || !p_ptr->active_ability[S_STL][STL_EXCHANGE_PLACES]) return FALSE;
    int dx = abs(mon_list[monster].fx - p_ptr->px);
    int dy = abs(mon_list[monster].fy - p_ptr->py);
    return dx <= 1 && dy <= 1 && (dx || dy);
}

static void android_show_monster(int monster)
{
    if (monster <= 0 || !mon_list[monster].ml) return;
    /* Share the description-run serializer. No inventory session is active
     * during a normal map command, and Java receives its own immutable string. */
    inventory_length = 0;
    inv_append("{\"name\":");
    bool obscured = p_ptr->image || p_ptr->rage;
    inv_string(obscured ? "ENEMY" : r_name + r_info[mon_list[monster].r_idx].name);
    int x = mon_list[monster].fx, y = mon_list[monster].fy;
    int px = p_ptr->px, py = p_ptr->py, depth = p_ptr->depth;
    bool can_exchange = android_can_exchange(monster);
    inv_append(can_exchange ? ",\"canExchange\":true,\"stats\":["
                            : ",\"canExchange\":false,\"stats\":[");
    inventory_first_run = TRUE;
    if (!obscured) {
        monster_type* m_ptr = &mon_list[monster];
        if (m_ptr->hp > 0 && m_ptr->maxhp > 0) {
            char status[32];
            int color;
            cptr health = "Near death";
            switch (health_level(m_ptr->hp, m_ptr->maxhp)) {
            case HEALTH_UNHURT: health = "Unhurt"; break;
            case HEALTH_SOMEWHAT_WOUNDED: health = "Slightly wounded"; break;
            case HEALTH_WOUNDED: health = "Wounded"; break;
            case HEALTH_BADLY_WOUNDED: health = "Badly wounded"; break;
            }
            inv_description(TERM_WHITE, "Health: ");
            inv_description(health_attr(m_ptr->hp, m_ptr->maxhp), health);
            if (get_alertness_text(m_ptr, sizeof(status), status, &color)) {
                bool stance = m_ptr->alertness >= ALERTNESS_ALERT
                    && !(r_info[m_ptr->r_idx].flags2 & RF2_MINDLESS);
                inv_description(TERM_WHITE, stance ? "\nStance: " : "\nAwareness: ");
                if (stance) {
                    inv_description((byte)color, m_ptr->stance == STANCE_FLEEING ? "Fleeing"
                        : m_ptr->stance == STANCE_AGGRESSIVE ? "Aggressive" : "Confident");
                    inv_description(TERM_WHITE, "\nMorale: ");
                    inv_description((byte)color, format("%d", m_ptr->morale >= 0
                        ? (m_ptr->morale + 9) / 10 : m_ptr->morale / 10));
                } else inv_description((byte)color, status);
            }
            if (m_ptr->confused || m_ptr->stunned) {
                inv_description(TERM_WHITE, "\nEffects: ");
                inv_description(TERM_YELLOW, m_ptr->confused && m_ptr->stunned
                    ? "Confused, stunned" : m_ptr->confused ? "Confused" : "Stunned");
            }
        }
    }
    inv_append("],\"runs\":[");
    inventory_first_run = TRUE;
    if (p_ptr->image)
        inv_description(TERM_WHITE, "What you see is not to be believed.");
    else if (p_ptr->rage)
        inv_description(TERM_WHITE, "You cannot recall details about this enemy while raging.");
    else {
        void (*saved_hook)(byte, cptr) = text_out_hook;
        text_out_hook = inv_description;
        describe_monster(mon_list[monster].r_idx, FALSE);
        text_out_hook = saved_hook;
    }
    inv_append("]}");
    note_active = TRUE;
    bool exchange = android_text_dialog(inventory_json, 2);
    note_active = FALSE;
    /* Run only after Java releases its input block, through the map command
     * loop. The core retains all terrain, confusion, combat and turn rules. */
    if (exchange && can_exchange && !background && dungeon_input()
        && p_ptr->depth == depth && p_ptr->px == px && p_ptr->py == py
        && cave_m_idx[y][x] == monster && android_can_exchange(monster)) {
        map_tap_direction = 5 + (x - px) - 3 * (y - py);
        do_cmd_exchange();
        map_tap_direction = 0;
    }
}

static errr extra_sdl(int action, int value)
{
    switch (action) {
    case TERM_XTRA_EVENT: pump_events(value != 0, FALSE); return 0;
    case TERM_XTRA_BORED: pump_events(FALSE, FALSE); return 0;
    case TERM_XTRA_FLUSH:
        map_tap_pending = FALSE;
        pointer_down = FALSE;
        pump_events(FALSE, TRUE);
        return 0;
    case TERM_XTRA_CLEAR:
        frame_dirty = TRUE;
        memset(cells, ' ', sizeof(cells));
        memset(graphic_cells, 0, sizeof(graphic_cells));
        return 0;
    case TERM_XTRA_SHAPE: frame_dirty = TRUE; cursor_visible = value != 0; return 0;
    case TERM_XTRA_FRESH: case TERM_XTRA_REACT: present(); return 0;
    case TERM_XTRA_DELAY: if (value > 0) SDL_Delay(value); return 0;
    case TERM_XTRA_FROSH: case TERM_XTRA_ALIVE: case TERM_XTRA_LEVEL: return 0;
    default: return 1;
    }
}

errr init_sdl(int argc, char** argv)
{
    (void)argc;
    (void)argv;
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "0");
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "1");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) < 0)
        quit_fmt("SDL initialization failed: %s", SDL_GetError());
    Uint32 event_type = SDL_RegisterEvents(1);
    if (event_type == (Uint32)-1) quit("Cannot register Android control events");
    SDL_AtomicSet(&dpad_event_type, (int)event_type);
    window = SDL_CreateWindow("Sil-Q", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
        COLS * CELL_W, ROWS * CELL_H, SDL_WINDOW_FULLSCREEN_DESKTOP);
    if (!window) quit_fmt("Cannot create SDL window: %s", SDL_GetError());
    renderer = SDL_CreateRenderer(window, -1,
        SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!renderer) renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED);
    if (!renderer) renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    if (!renderer) quit_fmt("Cannot create renderer: %s", SDL_GetError());
    SDL_RendererInfo renderer_info;
    if (SDL_GetRendererInfo(renderer, &renderer_info) == 0)
        __android_log_print(ANDROID_LOG_INFO, "SilQ", "Renderer: %s; VSync: %s",
            renderer_info.name,
            (renderer_info.flags & SDL_RENDERER_PRESENTVSYNC) ? "on" : "off");
    SDL_RenderSetLogicalSize(renderer, COLS * CELL_W, ROWS * CELL_H);
    load_font();
    set_graphics_mode(SDL_AtomicGet(&requested_tiles) != 0);
    load_tiles();
    memset(cells, ' ', sizeof(cells));
    memset(graphic_cells, 0, sizeof(graphic_cells));
    term_init(&screen_term, COLS, ROWS, 256);
    screen_term.attr_blank = TERM_WHITE;
    screen_term.char_blank = ' ';
    screen_term.never_frosh = TRUE;
    screen_term.higher_pict = TRUE;
    screen_term.text_hook = text_sdl;
    screen_term.pict_hook = pict_sdl;
    screen_term.wipe_hook = wipe_sdl;
    screen_term.curs_hook = cursor_sdl;
    screen_term.bigcurs_hook = big_cursor_sdl;
    screen_term.xtra_hook = extra_sdl;
    Term_activate(&screen_term);
    angband_term[0] = &screen_term;
    __android_log_write(ANDROID_LOG_INFO, "SilQ",
        use_graphics ? "SDL terminal ready: 80x24 with MicroChasm tiles"
                     : "SDL terminal ready: 80x24 with ASCII graphics");
    return 0;
}

int SDL_main(int argc, char** argv)
{
    background = FALSE;
    plog_aux = log_message;
    const char* path = SDL_AndroidGetInternalStoragePath();
    if (!path || chdir(path) != 0) quit("Cannot enter app storage directory");
    // Keep the standalone engine's process lifetime: quit() exits this app.
    // Its cleanup leaves dangling globals and cannot reenter main in the same VM.
    int result_code = sil_main(argc, argv);
    if (font) SDL_DestroyTexture(font);
    if (tiles) SDL_DestroyTexture(tiles);
    if (rage_tiles) SDL_DestroyTexture(rage_tiles);
    if (renderer) SDL_DestroyRenderer(renderer);
    if (window) SDL_DestroyWindow(window);
    font = NULL;
    tiles = NULL;
    rage_tiles = NULL;
    renderer = NULL;
    window = NULL;
    SDL_Quit();
    return result_code;
}
