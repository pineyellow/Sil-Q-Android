/* Android dungeon camera. Included by main-sdl.c; all mutable view state is
 * owned by the SDL/game thread. Java only changes atomic preferences.
 * Detached drag, pinch and selectable follow speeds. */
typedef struct { SDL_FingerID id; float x, y; bool down; } CameraFinger;
static CameraFinger camera_fingers[2];
static float drag_x, drag_y;
static bool camera_dragged, camera_pinched;
static bool map_tap_pending;
static int map_tap_x, map_tap_y, map_tap_depth, map_tap_px, map_tap_py;
static int map_tap_direction;
static int map_tap_monster;
static bool map_tap_inspect;
static int camera_canvas_width = COLS * CELL_W;
static int camera_canvas_height = ROWS * CELL_H;
static bool camera_active(void);
static void cancel_camera_touch(void);
static bool camera_follow_pending;
static int camera_follow_px, camera_follow_py;

static void camera_resume_follow(void)
{
    if (camera_detached && SDL_AtomicGet(&camera_speed) == 3 && dungeon_input()) {
        camera_follow_pending = TRUE;
        camera_follow_px = p_ptr->px;
        camera_follow_py = p_ptr->py;
    }
    camera_detached = FALSE;
}

void android_camera_command_finished(void)
{
    /* Failed movement must also release the deferred recenter. */
    camera_follow_pending = FALSE;
}

/* Keep the terminal unchanged, but give the free camera a canvas matching
 * the output aspect ratio. Uniform scaling preserves square dungeon tiles. */
static void update_camera_canvas(void)
{
    int width = COLS * CELL_W, height = ROWS * CELL_H;
    int output_width, output_height;
    if (camera_active() && SDL_GetRendererOutputSize(renderer,
            &output_width, &output_height) == 0 && output_width > 0 && output_height > 0) {
        float scale = SDL_min((float)output_width / width, (float)output_height / height);
        width = (int)roundf(output_width / scale);
        height = (int)roundf(output_height / scale);
    }
    if (width != camera_canvas_width || height != camera_canvas_height) {
        camera_canvas_width = width;
        camera_canvas_height = height;
        SDL_RenderSetLogicalSize(renderer, width, height);
        cancel_camera_touch();
        pointer_down = FALSE;
    }
}

int android_tap_direction(void)
{
    return map_tap_direction;
}

/* Match look's knowledge rules without entering its cursor/input loop. */
static bool map_inspection_description(int y, int x, char* text, size_t size)
{
    int object = cave_o_idx[y][x];
    bool item = object > 0 && o_list[object].marked && cave_floorlike_bold(y, x);
    if (p_ptr->image) {
        my_strcpy(text, "What you see is not to be believed.", size);
    } else if (item) {
        char name[80];
        object_desc(name, sizeof(name), &o_list[object], TRUE, 3);
        strnfmt(text, size, "You see %s", name);
    } else {
        int feat = f_info[cave_feat[y][x]].mimic;
        /* Hidden traps look like floor; mimic also conceals secret doors
         * and undiscovered door locks. Never describe unexplored terrain. */
        if (cave_trap_bold(y, x) && (cave_info[y][x] & CAVE_HIDDEN))
            feat = FEAT_FLOOR;
        if (!(cave_info[y][x] & CAVE_MARK) && !player_can_see_bold(y, x))
            feat = FEAT_NONE;
        if (feat == FEAT_NONE) return FALSE;
        cptr name = f_name + f_info[feat].name;
        cptr article = (feat >= FEAT_FORGE_UNIQUE_HEAD && feat <= FEAT_FORGE_UNIQUE_TAIL)
            ? "the" : (is_a_vowel(name[0]) ? "an" : "a");
        strnfmt(text, size, "You see %s %s", article, name);
    }
    return TRUE;
}

/* Execute through the normal command loop, never inside SDL event pumping. */
void android_run_map_tap(void)
{
    if (!map_tap_pending) return;
    map_tap_pending = FALSE;
    if (!dungeon_input() || p_ptr->depth != map_tap_depth
        || p_ptr->px != map_tap_px || p_ptr->py != map_tap_py) return;
    int x = map_tap_x, y = map_tap_y;
    if (map_tap_monster > 0) {
        if (cave_m_idx[y][x] == map_tap_monster && mon_list[map_tap_monster].ml)
            android_show_monster(map_tap_monster);
        return;
    }
    if (map_tap_inspect) {
        char text[256];
        /* The command loop has acknowledged the previous message. Never
         * flush, log, or wait for this temporary inspection line. The next
         * command clears it through request_command(), just like look text. */
        if (!msg_flag && map_inspection_description(y, x, text, sizeof(text)))
            prt(text, 0, 0);
        return;
    }
    if (x == p_ptr->px && y == p_ptr->py) {
        /* Same action as comma (/5): interact with the player's square. */
        map_tap_direction = 5;
        do_cmd_alter();
        map_tap_direction = 0;
        return;
    }
    if (abs(x - p_ptr->px) > 1 || abs(y - p_ptr->py) > 1
        || !(cave_info[y][x] & CAVE_MARK)) return;
    map_tap_direction = 5 + (x - p_ptr->px) - 3 * (y - p_ptr->py);
    if (cave_feat[y][x] == FEAT_OPEN) do_cmd_close();
    else if (cave_feat[y][x] >= FEAT_DOOR_HEAD
        && cave_feat[y][x] <= FEAT_DOOR_TAIL) do_cmd_open();
    map_tap_direction = 0;
}
static byte effect_attr[MAX_DUNGEON_HGT][MAX_DUNGEON_WID];
static byte effect_char[MAX_DUNGEON_HGT][MAX_DUNGEON_WID];
static bool effect_visible[MAX_DUNGEON_HGT][MAX_DUNGEON_WID];

/* Keep projectile/combat glyphs in world coordinates as the terminal panel
 * moves. The engine clears these through lite_spot/prt_map as usual. */
void android_camera_effect(int y, int x, byte a, char c)
{
    frame_dirty = TRUE;
    if (y < 0 || y >= MAX_DUNGEON_HGT || x < 0 || x >= MAX_DUNGEON_WID) return;
    effect_visible[y][x] = TRUE;
    effect_attr[y][x] = a;
    effect_char[y][x] = (byte)c;
}

void android_camera_clear_effect(int y, int x)
{
    frame_dirty = TRUE;
    if (y >= 0 && y < MAX_DUNGEON_HGT && x >= 0 && x < MAX_DUNGEON_WID)
        effect_visible[y][x] = FALSE;
}

void android_camera_clear_effects(void)
{
    frame_dirty = TRUE;
    memset(effect_visible, 0, sizeof(effect_visible));
}

static bool camera_active(void)
{
    /* Screen ownership, not input readiness, chooses the renderer. The engine's
     * screen_save/screen_load nesting keeps full-screen menus on the terminal;
     * automatic turns and dungeon prompts remain on the camera. Death disables
     * input immediately, but the dungeon still owns the screen while the engine
     * finishes its final updates. close_game() transfers ownership to the death
     * screen by increasing character_icky. */
    return !background && character_dungeon && character_icky == 0
        && game_in_progress && (p_ptr->playing || p_ptr->is_dead);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_silq_SilActivity_nativeSetCamera(
    JNIEnv* env, jobject self, jint mode, jint speed, jfloat zoom)
{
    (void)env; (void)self;
    (void)mode; /* The Android dungeon always uses the modern camera. */
    SDL_AtomicSet(&camera_speed, SDL_clamp(speed, 0, 3));
    if (isfinite(zoom))
        SDL_AtomicSet(&camera_zoom_setting, (int)(SDL_clamp(zoom, 0.5f, 3.0f) * 1000));
    wake_renderer();
}

static void cancel_camera_touch(void)
{
    memset(camera_fingers, 0, sizeof(camera_fingers));
    camera_dragged = camera_pinched = FALSE;
}

static SDL_Rect camera_rect(void)
{
    SDL_Rect r = { COL_MAP * CELL_W, ROW_MAP * CELL_H,
        (COLS - COL_MAP - 1) * CELL_W, (ROWS - ROW_MAP - 1) * CELL_H };
    if (camera_active()) {
        r.w = camera_canvas_width - r.x;
        r.h = camera_canvas_height - r.y - CELL_H;
    }
    return r;
}

static bool tap_dungeon(float sx, float sy)
{
    if (!dungeon_input() || (!firing_active
        && (!inkey_flag || inkey_scan)))
        return FALSE;
    SDL_Rect r = camera_rect();
    if (sx < r.x || sy < r.y || sx >= r.x + r.w || sy >= r.y + r.h)
        return TRUE;
    float cw = use_bigtile ? 2 * CELL_W : CELL_W;
    int x, y;
    if (camera_active()) {
        x = (int)floorf(camera_x + (sx - r.x - r.w / 2.0f) / (cw * camera_zoom));
        y = (int)floorf(camera_y + (sy - r.y - r.h / 2.0f) / (CELL_H * camera_zoom));
    } else {
        x = p_ptr->wx + (int)((sx - r.x) / cw);
        y = p_ptr->wy + (int)((sy - r.y) / CELL_H);
    }
    if (x < 0 || y < 0 || x >= p_ptr->cur_map_wid || y >= p_ptr->cur_map_hgt)
        return TRUE;
    if (firing_active) {
        if (interacting_active) {
            if (abs(x - p_ptr->px) <= 1 && abs(y - p_ptr->py) <= 1
                && screen_term.key_head == screen_term.key_tail)
                Term_keypress('0' + 5 + (x - p_ptr->px) - 3 * (y - p_ptr->py));
            return TRUE;
        }
        if ((x == p_ptr->px && y == p_ptr->py)
            || screen_term.key_head != screen_term.key_tail) return TRUE;
        if (firing_range > 0 && distance(p_ptr->py, p_ptr->px, y, x) > firing_range) return TRUE;
        int monster = cave_m_idx[y][x];
        if (monster > 0 && target_able(monster)) target_set_monster(monster);
        else target_set_location(y, x);
        if (target_okay(firing_range)) Term_keypress('f');
        return TRUE;
    }
    bool player = x == p_ptr->px && y == p_ptr->py;
    int monster = cave_m_idx[y][x];
    bool enemy = monster > 0 && mon_list[monster].ml;
    bool door = abs(x - p_ptr->px) <= 1 && abs(y - p_ptr->py) <= 1
        && (cave_info[y][x] & CAVE_MARK)
        && (cave_feat[y][x] == FEAT_OPEN || (cave_feat[y][x] >= FEAT_DOOR_HEAD
            && cave_feat[y][x] <= FEAT_DOOR_TAIL));
    char description[256];
    bool inspect = !player && !door && !enemy
        && map_inspection_description(y, x, description, sizeof(description));
    if ((!player && !door && !enemy && !inspect) || screen_term.key_head != screen_term.key_tail)
        return TRUE;
    map_tap_inspect = inspect;
    map_tap_monster = enemy ? monster : 0;
    map_tap_x = x; map_tap_y = y; map_tap_depth = p_ptr->depth;
    map_tap_px = p_ptr->px; map_tap_py = p_ptr->py;
    map_tap_pending = TRUE;
    SDL_AtomicAdd(&dpad_press_token, 1);
    /* Ctrl-] (29) is swallowed by inkey(); Ctrl-\ (28) passes through. */
    Term_keypress(KTRL('\\'));
    return TRUE;
}

static void clamp_camera(void)
{
    /* Allow edge tiles to reach the center without panning off the level. */
    camera_x = SDL_clamp(camera_x, 0.5f, p_ptr->cur_map_wid - 0.5f);
    camera_y = SDL_clamp(camera_y, 0.5f, p_ptr->cur_map_hgt - 0.5f);
}

static void camera_glyph(byte a, byte c, const SDL_Rect* dst)
{
    if (!c || c == ' ') return;
    byte* rgb = angband_color_table[a % MAX_COLORS];
    SDL_SetTextureColorMod(font, rgb[1], rgb[2], rgb[3]);
    SDL_Rect src = { (c % 16) * CELL_W, (c / 16) * CELL_H, CELL_W, CELL_H };
    SDL_RenderCopy(renderer, font, &src, dst);
}

static void draw_camera(void)
{
    SDL_Rect view = camera_rect();
    Uint32 now = SDL_GetTicks();
    float dt = SDL_min(now - camera_time, 100) / 1000.0f;
    camera_time = now;
    camera_zoom = SDL_AtomicGet(&camera_zoom_setting) / 1000.0f;
    if (!camera_ready || camera_depth != p_ptr->depth) {
        camera_follow_pending = FALSE;
        camera_ready = TRUE;
        camera_depth = p_ptr->depth;
        camera_detached = FALSE;
        camera_x = p_ptr->px + 0.5f;
        camera_y = p_ptr->py + 0.5f;
        android_camera_clear_effects();
    }
    if (camera_follow_pending
        && (p_ptr->px != camera_follow_px || p_ptr->py != camera_follow_py))
        camera_follow_pending = FALSE;
    if (!camera_detached && !camera_follow_pending && !inventory_active && !more_active && !firing_active && !note_active
        && SDL_AtomicGet(&confirmation_answer) >= 0) {
        const float speeds[] = { 8, 16, 28, 0 };
        int speed = SDL_AtomicGet(&camera_speed);
        float t = speed == 3 ? 1 : 1 - expf(-speeds[speed] * dt);
        camera_x += (p_ptr->px + 0.5f - camera_x) * t;
        camera_y += (p_ptr->py + 0.5f - camera_y) * t;
    }
    clamp_camera();
    float cw = (use_bigtile ? 2 * CELL_W : CELL_W) * camera_zoom;
    float ch = CELL_H * camera_zoom;
    float left = camera_x - view.w / (2 * cw);
    float top = camera_y - view.h / (2 * ch);
    SDL_RenderSetClipRect(renderer, &view);
    /* map_info respects explored terrain and monster visibility. Its ASCII
     * shimmer may use randomness: use a local stable seed, never game RNG. */
    bool saved_quick = Rand_quick;
    u32b saved_value = Rand_value;
    Rand_quick = TRUE;
    for (int y = SDL_max(0, (int)floorf(top));
         y < p_ptr->cur_map_hgt && y < top + view.h / ch; y++) {
        for (int x = SDL_max(0, (int)floorf(left));
             x < p_ptr->cur_map_wid && x < left + view.w / cw; x++) {
            byte a, ta; char c, tc;
            Rand_value = (u32b)turn * 131u + y * 65537u + x * 313u;
            map_info(y, x, &a, &c, &ta, &tc);
            if (effect_visible[y][x]) {
                a = effect_attr[y][x];
                c = (char)effect_char[y][x];
            }
            int dx = (int)roundf(view.x + (x - left) * cw);
            int dy = (int)roundf(view.y + (y - top) * ch);
            SDL_Rect dst = { dx, dy,
                (int)roundf(view.x + (x + 1 - left) * cw) - dx,
                (int)roundf(view.y + (y + 1 - top) * ch) - dy };
            if ((a & 0x80) && ((byte)c & 0x80)) {
                SDL_Texture* texture = p_ptr->rage ? rage_tiles : tiles;
                bool terrain = (ta & 0x80) && ((byte)tc & 0x80);
                if (terrain) draw_tile(texture, ta, (byte)tc, &dst);
                if (a & GRAPHICS_GLOW_MASK)
                    draw_tile(texture, misc_to_attr[ICON_GLOW], misc_to_char[ICON_GLOW], &dst);
                if (!terrain || (a & 0x3f) != (ta & 0x3f)
                    || (c & 0x3f) != (tc & 0x3f)) draw_tile(texture, a, (byte)c, &dst);
                if ((byte)c & GRAPHICS_ALERT_MASK)
                    draw_tile(texture, misc_to_attr[ICON_ALERT], misc_to_char[ICON_ALERT], &dst);
            } else camera_glyph(a, (byte)c, &dst);
        }
    }
    Rand_quick = saved_quick;
    Rand_value = saved_value;
    SDL_RenderSetClipRect(renderer, NULL);
}

static void save_camera_zoom(void)
{
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = (*env)->GetMethodID(env, cls, "onNativeCameraZoom", "(F)V");
    if (method) (*env)->CallVoidMethod(env, activity, method, camera_zoom);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

static bool camera_touch(const SDL_Event* event)
{
    if (event->type != SDL_FINGERDOWN && event->type != SDL_FINGERMOTION
        && event->type != SDL_FINGERUP) return FALSE;
    // Message pauses use the ordinary mouse/tap path, never dungeon gestures.
    if (more_active) return FALSE;
    if (!camera_active()) { cancel_camera_touch(); return FALSE; }
    float x = event->tfinger.x * camera_canvas_width;
    float y = event->tfinger.y * camera_canvas_height;
    int slot = -1;
    for (int i = 0; i < 2; i++)
        if (camera_fingers[i].down && camera_fingers[i].id == event->tfinger.fingerId) slot = i;
    if (event->type == SDL_FINGERDOWN) {
        SDL_Rect r = camera_rect();
        if (x < r.x || y < r.y || x >= r.x + r.w || y >= r.y + r.h) return TRUE;
        if (slot >= 0) return TRUE;
        for (int i = 0; i < 2; i++) if (!camera_fingers[i].down) { slot = i; break; }
        if (slot < 0) return TRUE;
        bool first = !camera_fingers[0].down && !camera_fingers[1].down;
        camera_fingers[slot] = (CameraFinger){event->tfinger.fingerId, x, y, TRUE};
        if (first) { drag_x = x; drag_y = y; camera_dragged = camera_pinched = FALSE; }
        else camera_pinched = TRUE;
        pointer_down = FALSE;
        return TRUE;
    }
    if (slot < 0) return TRUE;
    CameraFinger* f = &camera_fingers[slot];
    CameraFinger* other = &camera_fingers[1 - slot];
    if (event->type == SDL_FINGERMOTION) {
        if (other->down) {
            float old = hypotf(f->x - other->x, f->y - other->y);
            float distance = hypotf(x - other->x, y - other->y);
            if (old > 12 && distance > 12) {
                camera_zoom = SDL_clamp(camera_zoom * distance / old, 0.5f, 3.0f);
                SDL_AtomicSet(&camera_zoom_setting, (int)(camera_zoom * 1000));
            }
        } else if (!camera_pinched) {
            if (!camera_dragged && hypotf(x - drag_x, y - drag_y) > 12) {
                camera_dragged = camera_detached = TRUE;
                camera_follow_pending = FALSE;
                f->x = drag_x; f->y = drag_y;
            }
            if (camera_dragged) {
                camera_x -= (x - f->x) / ((use_bigtile ? 2 * CELL_W : CELL_W) * camera_zoom);
                camera_y -= (y - f->y) / (CELL_H * camera_zoom);
                clamp_camera();
            }
        }
        f->x = x; f->y = y;
    } else {
        f->down = FALSE;
        if (!other->down) {
            if (camera_pinched) save_camera_zoom();
            else if (!camera_dragged && hypotf(x - drag_x, y - drag_y) <= 12) {
                if (!tap_dungeon(x, y)) Term_keypress('\r');
                if (!firing_active && !(map_tap_pending && (map_tap_monster > 0 || map_tap_inspect)))
                    camera_resume_follow();
            }
        }
    }
    return TRUE;
}
