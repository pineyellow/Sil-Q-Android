/* Game-thread inventory bridge. Java receives snapshots, never engine pointers.
 * Inventory actions use Sil-Q gameplay commands. */
static SDL_atomic_t inventory_request, inventory_answer;
static SDL_atomic_t inventory_answer_action;
static bool inventory_action, inventory_cancelled;
static bool inventory_committed;
static bool inventory_failed;
static int inventory_selected = -1;
static bool inventory_selection_pending;
static char* inventory_json;
static size_t inventory_length, inventory_capacity;
static bool inventory_first_run;
static char inventory_text[1024];
static int inventory_choices[INVEN_TOTAL + MAX_FLOOR_STACK];
static int inventory_choice_count;

static void inv_append(cptr str)
{
    size_t n = strlen(str);
    if (inventory_length + n + 1 > inventory_capacity) {
        inventory_capacity = (inventory_length + n + 1) * 2;
        char* resized = SDL_realloc(inventory_json, inventory_capacity);
        if (!resized) quit("Cannot allocate inventory snapshot");
        inventory_json = resized;
    }
    memcpy(inventory_json + inventory_length, str, n + 1);
    inventory_length += n;
}

static void inv_number(int n)
{
    char text[32]; SDL_snprintf(text, sizeof(text), "%d", n); inv_append(text);
}

static void inv_string(cptr text)
{
    inv_append("\"");
    for (const unsigned char* p = (const unsigned char*)(text ? text : ""); *p; p++) {
        char escaped[8];
        if (*p == '"' || *p == '\\') {
            escaped[0] = '\\'; escaped[1] = *p; escaped[2] = 0;
        } else if (*p < 32 || *p >= 128) {
            /* Core strings are single-byte, including accented item names. */
            SDL_snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
        } else { escaped[0] = *p; escaped[1] = 0; }
        inv_append(escaped);
    }
    inv_append("\"");
}

static void inv_description(byte color, cptr text)
{
    if (!inventory_first_run) inv_append(",");
    inventory_first_run = FALSE;
    inv_append("{\"text\":"); inv_string(text);
    byte* rgb = angband_color_table[color % MAX_COLORS];
    inv_append(",\"color\":");
    inv_number((int)(0xff000000u | rgb[1] << 16 | rgb[2] << 8 | rgb[3]));
    inv_append("}");
}

static bool inv_can_refuel(int index)
{
    object_type* o = index < 0 ? &o_list[-index] : &inventory[index];
    object_type* light = &inventory[INVEN_LITE];
    if (index >= INVEN_WIELD || light->tval != TV_LIGHT) return FALSE;
    if (light->sval == SV_LIGHT_LANTERN)
        return o->tval == TV_FLASK || (o->tval == TV_LIGHT
            && o->sval == SV_LIGHT_LANTERN && o->timeout > 0);
    return (light->sval == SV_LIGHT_TORCH || light->sval == SV_LIGHT_MALLORN)
        && o->tval == TV_LIGHT && o->sval == light->sval;
}

/* Bit positions are shared with InventoryOverlay's action labels. */
static int inv_actions(int index)
{
    object_type* o = index < 0 ? &o_list[-index] : &inventory[index];
    int actions = (1 << 6) | (index < 0 ? (1 << 10) : (1 << 5));
    if (index >= 0 && p_ptr->active_ability[S_ARC][ARC_FLETCHERY]
        && item_tester_hook_ordinary_ammo(o)) actions |= 1 << 11;
    if (index >= INVEN_WIELD) return actions | (1 << 1);
    actions |= 1 << 7; /* Destroy carried items with the engine's confirmation. */
    if (wield_slot(o) >= INVEN_WIELD
        && !(o->name1 >= ART_MORGOTH_0 && o->name1 <= ART_MORGOTH_3)) actions |= 1;
    if (o->tval == TV_FOOD) actions |= 1 << 2;
    if (o->tval == TV_POTION) actions |= 1 << 3;
    if (inv_can_refuel(index)) actions |= 1 << 4;
    if (o->tval == TV_STAFF) actions |= 1 << 8;
    if (o->tval == TV_HORN) actions |= 1 << 9;
    return actions;
}

/* Contextually unavailable actions. Never accepted by the dispatcher. */
static int inv_disabled_actions(int index)
{
    return index >= INVEN_WIELD ? (1 << 7) : 0;
}

static bool inv_matches(int index, int mode)
{
    if (!inventory[index].k_idx) return FALSE;
    if (index < INVEN_WIELD ? !(mode & USE_INVEN) : !(mode & USE_EQUIP)) return FALSE;
    return item_tester_okay(&inventory[index]);
}

static void inv_snapshot(cptr mode, cptr prompt, int filter, int maximum)
{
    extern void android_object_description(const object_type*, void (*)(byte, cptr));
    extern void android_object_stat_description(const object_type*, void (*)(byte, cptr));
    inventory_length = 0;
    inv_append("{\"mode\":"); inv_string(mode);
    inv_append(",\"prompt\":"); inv_string(prompt);
    inv_append(",\"max\":"); inv_number(maximum);
    inv_append(",\"capacity\":"); inv_number(INVEN_PACK);
    int count = 0;
    for (int i = 0; i < INVEN_WIELD; i++) if (inventory[i].k_idx) count++;
    inv_append(",\"count\":"); inv_number(count);
    inv_append(",\"totalWeight\":"); inv_number(p_ptr->total_weight);
    inv_append(",\"encumbered\":");
    inv_append(p_ptr->total_weight > weight_limit() ? "true" : "false");
    inv_append(",\"items\":[");
    char path[1024];
    path_build(path, sizeof(path), ANGBAND_DIR_XTRA, "graf/16x16.bmp");
    SDL_Surface* bmp = SDL_LoadBMP(path);
    SDL_Surface* atlas = bmp ? rgba_surface(bmp) : NULL;
    if (bmp) SDL_FreeSurface(bmp);
    Uint8 br = 0, bg = 0, bb = 0, ba;
    if (atlas) SDL_GetRGBA(*(Uint32*)atlas->pixels, atlas->format, &br, &bg, &bb, &ba);
    bool first = TRUE;
    inventory_choice_count = 0;
    for (int i = 0; i < INVEN_TOTAL && maximum == 0; i++)
        if (inventory[i].k_idx && (!filter || inv_matches(i, filter)))
            inventory_choices[inventory_choice_count++] = i;
    if (filter & USE_FLOOR) {
        int floor_items[MAX_FLOOR_STACK];
        int n = scan_floor(floor_items, MAX_FLOOR_STACK, p_ptr->py, p_ptr->px, 0x01);
        for (int i = 0; i < n; i++)
            inventory_choices[inventory_choice_count++] = -floor_items[i];
    }
    for (int row = 0; row < inventory_choice_count; row++) {
        int i = inventory_choices[row];
        object_type* o = i < 0 ? &o_list[-i] : &inventory[i];
        if (!first) inv_append(","); first = FALSE;
        inv_append("{\"id\":"); inv_number(filter ? row : i);
        char name[512]; object_desc(name, sizeof(name), o, TRUE, 3);
        if (i < 0) my_strcat(name, " (on floor)", sizeof(name));
        inv_append(",\"name\":"); inv_string(name);
        inv_append(",\"quantity\":"); inv_number(o->number);
        inv_append(",\"slot\":"); inv_string(i >= INVEN_WIELD ? describe_use(i) : "");
        inv_append(",\"actions\":"); inv_number(inv_actions(i));
        inv_append(",\"disabledActions\":"); inv_number(i < 0 ? 0 : inv_disabled_actions(i));
        inv_append(",\"stats\":["); inventory_first_run = TRUE;
        android_object_stat_description(o, inv_description);
        inv_append("],\"runs\":["); inventory_first_run = TRUE;
        android_object_description(o, inv_description);
        inv_append("],\"pixels\":[");
        int tx = ((byte)object_char(o) & 0x3f) * SOURCE_TILE_SIZE;
        int ty = (object_attr(o) & 0x3f) * SOURCE_TILE_SIZE;
        for (int p = 0; p < 256; p++) {
            if (p) inv_append(",");
            Uint8 r = 0, g = 0, b = 0, a = 0;
            if (atlas && tx + 16 <= atlas->w && ty + 16 <= atlas->h) {
                Uint32* row = (Uint32*)((Uint8*)atlas->pixels + (ty + p / 16) * atlas->pitch);
                SDL_GetRGBA(row[tx + p % 16], atlas->format, &r, &g, &b, &a);
                if (r == br && g == bg && b == bb) a = 0;
            }
            inv_number((int)((Uint32)a << 24 | r << 16 | g << 8 | b));
        }
        inv_append("]}");
    }
    if (atlas) SDL_FreeSurface(atlas);
    inv_append("]}");
}

static void update_ground_controls(void)
{
    static object_type previous;
    static int previous_index = -1, previous_context = -1, previous_depth = -1;
    static int previous_feat = -1, previous_x = -1, previous_y = -1;
    bool available = dungeon_input() && !inventory_active && !firing_active && !p_ptr->fletching && !more_active;
    int index = available ? cave_o_idx[p_ptr->py][p_ptr->px] : 0;
    if (index && o_list[index].tval == TV_NOTE) index = 0;
    int feat = available && !index
        && (cave_up_stairs_bold(p_ptr->py, p_ptr->px)
            || cave_down_stairs_bold(p_ptr->py, p_ptr->px)
            || cave_forge_bold(p_ptr->py, p_ptr->px))
        ? cave_feat[p_ptr->py][p_ptr->px] : 0;
    int context = SDL_AtomicGet(&dpad_context);
    if (index == previous_index && context == previous_context && previous_depth == p_ptr->depth
        && feat == previous_feat && previous_x == p_ptr->px && previous_y == p_ptr->py
        && (!index || !memcmp(&previous, &o_list[index], sizeof(previous)))) return;
    previous_index = index;
    previous_context = context;
    previous_depth = p_ptr->depth;
    previous_feat = feat; previous_x = p_ptr->px; previous_y = p_ptr->py;
    if (index) previous = o_list[index];
    int epoch = SDL_AtomicAdd(&ground_epoch, 1) + 1;
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (!env || !activity) return;
    if (index) {
        byte saved_tval = item_tester_tval;
        bool saved_full = item_tester_full;
        bool (*saved_hook)(const object_type*) = item_tester_hook;
        item_tester_tval = 0; item_tester_full = FALSE; item_tester_hook = NULL;
        inv_snapshot("floor", "", USE_FLOOR, 0);
        item_tester_tval = saved_tval; item_tester_full = saved_full; item_tester_hook = saved_hook;
    } else if (feat) {
        char path[1024];
        path_build(path, sizeof(path), ANGBAND_DIR_XTRA, "graf/16x16.bmp");
        SDL_Surface* bmp = SDL_LoadBMP(path);
        SDL_Surface* atlas = bmp ? rgba_surface(bmp) : NULL;
        if (bmp) SDL_FreeSurface(bmp);
        inventory_length = 0;
        inv_append("{\"terrain\":true,\"items\":[{\"name\":");
        inv_string(cave_forge_bold(p_ptr->py, p_ptr->px) ? "Use forge"
            : cave_up_stairs_bold(p_ptr->py, p_ptr->px) ? "Ascend" : "Descend");
        inv_append(",\"pixels\":[");
        int tx = ((byte)f_info[feat].x_char & 0x3f) * SOURCE_TILE_SIZE;
        int ty = (f_info[feat].x_attr & 0x3f) * SOURCE_TILE_SIZE;
        for (int p = 0; p < 256; p++) {
            if (p) inv_append(",");
            Uint8 r = 0, g = 0, b = 0, a = 0;
            if (atlas && tx + 16 <= atlas->w && ty + 16 <= atlas->h) {
                Uint32* row = (Uint32*)((Uint8*)atlas->pixels + (ty + p / 16) * atlas->pitch);
                SDL_GetRGBA(row[tx + p % 16], atlas->format, &r, &g, &b, &a);
            }
            inv_number((int)((Uint32)a << 24 | r << 16 | g << 8 | b));
        }
        inv_append("]}]}");
        if (atlas) SDL_FreeSurface(atlas);
    }
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID method = (*env)->GetMethodID(env, cls, "onNativeGroundItem", "(Ljava/lang/String;I)V");
    jstring json = (*env)->NewStringUTF(env, index || feat ? inventory_json : "");
    if (method) (*env)->CallVoidMethod(env, activity, method, json, epoch);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, json);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

JNIEXPORT void JNICALL Java_com_pineyellow_silq_SilActivity_nativeOpenGround(
    JNIEnv* env, jobject self, jint epoch)
{
    (void)env; (void)self;
    if (epoch > 0) SDL_AtomicSet(&ground_open_requested, epoch);
    wake_renderer();
}

JNIEXPORT void JNICALL Java_com_pineyellow_silq_SilActivity_nativeInventoryReply(
    JNIEnv* env, jobject self, jint request, jint value, jint action)
{
    (void)env; (void)self;
    /* Claim exactly one reply; publish the answer with SDL's memory barriers. */
    if (request > 0 && SDL_AtomicCAS(&inventory_request, request, 0)) {
        SDL_AtomicSet(&inventory_answer_action, action);
        SDL_AtomicSet(&inventory_answer, value);
    }
    wake_renderer();
}

JNIEXPORT void JNICALL Java_com_pineyellow_silq_SilActivity_nativeOpenInventory(
    JNIEnv* env, jobject self)
{
    (void)env; (void)self;
    SDL_AtomicSet(&inventory_open_requested, 1);
    wake_renderer();
}

JNIEXPORT void JNICALL Java_com_pineyellow_silq_SilActivity_nativeInventoryTextReply(
    JNIEnv* env, jobject self, jint request, jbyteArray text)
{
    (void)self;
    if (request <= 0 || !text || !SDL_AtomicCAS(&inventory_request, request, 0)) return;
    int n = SDL_min((*env)->GetArrayLength(env, text), (int)sizeof(inventory_text) - 1);
    (*env)->GetByteArrayRegion(env, text, 0, n, (jbyte*)inventory_text);
    inventory_text[n] = 0;
    SDL_AtomicSet(&inventory_answer, 1);
    wake_renderer();
}

static void inv_hide(void);
static int inv_wait(void)
{
    if (android_save_interrupt()) return -1;
    /* A follow-up selector can reopen Java after an earlier effect hid it. */
    inventory_committed = FALSE;
    static int serial;
    int id = serial = serial == 2147483647 ? 1 : serial + 1;
    SDL_AtomicSet(&inventory_answer, -2);
    SDL_AtomicSet(&inventory_answer_action, -1);
    SDL_AtomicSet(&inventory_request, id);
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    bool shown = FALSE;
    if (env && activity) {
        jclass cls = (*env)->GetObjectClass(env, activity);
        jmethodID method = (*env)->GetMethodID(env, cls, "showInventory", "(Ljava/lang/String;I)V");
        jstring json = method ? (*env)->NewStringUTF(env, inventory_json) : NULL;
        if (json) {
            (*env)->CallVoidMethod(env, activity, method, json, id);
            shown = !(*env)->ExceptionCheck(env);
            (*env)->DeleteLocalRef(env, json);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, cls);
        (*env)->DeleteLocalRef(env, activity);
    }
    if (!shown) { SDL_AtomicSet(&inventory_request, 0); return -1; }
    while (SDL_AtomicGet(&inventory_answer) == -2) {
        if (android_save_interrupt()) {
            SDL_AtomicSet(&inventory_request, 0);
            inv_hide();
            return -1;
        }
        pump_events(TRUE, TRUE);
    }
    return SDL_AtomicGet(&inventory_answer);
}

bool android_inventory_has_selection(void)
{
    return inventory_action && inventory_selection_pending;
}

/* Status is separate from the signed engine item index (negative means floor). */
static int inv_select(cptr prompt, cptr empty, int mode, int* item)
{
    int selected = inventory_selected;
    bool pending = inventory_selection_pending;
    inventory_selection_pending = FALSE;
    inventory_selected = -1;
    if (!pending) {
        inv_snapshot("select", prompt, mode, 0);
        if (!inventory_choice_count) {
            if (empty) msg_print(empty);
            inventory_cancelled = TRUE;
            return -1;
        }
        int row = inv_wait();
        if (row < 0 || row >= inventory_choice_count) {
            inventory_cancelled = TRUE;
            return -1;
        }
        selected = inventory_choices[row];
    }
    bool valid = FALSE;
    if (selected >= 0) valid = selected < INVEN_TOTAL && inv_matches(selected, mode);
    else if (mode & USE_FLOOR) {
        int floor_items[MAX_FLOOR_STACK];
        int n = scan_floor(floor_items, MAX_FLOOR_STACK, p_ptr->py, p_ptr->px, 0x01);
        for (int i = 0; i < n; i++) if (floor_items[i] == -selected) valid = TRUE;
    }
    if (!valid) {
        inventory_cancelled = TRUE;
        return -1;
    }
    *item = selected;
    return 0;
}

int android_inventory_select(cptr prompt, cptr empty, int mode, int* item)
{
    if (inventory_action) return inv_select(prompt, empty, mode, item);

    /* Dungeon commands can request items without starting in inventory (for
     * example, thralls and choosing a digging tool). Route all such requests
     * through the same selector, preserving the caller's filter and locations. */
    if (!dungeon_input() || inventory_active) return -2;

    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    Term_fresh();
    present();
    inventory_active = inventory_action = TRUE;
    inventory_selection_pending = FALSE;
    inventory_selected = -1;
    inventory_cancelled = inventory_committed = inventory_failed = FALSE;

    int status = inv_select(prompt, empty, mode, item);

    /* Release the selector before inscription checks, rewards, or -more-. */
    inventory_active = inventory_action = FALSE;
    inventory_selection_pending = FALSE;
    inventory_selected = -1;
    SDL_AtomicSet(&inventory_request, 0);
    inv_hide();
    Term_flush();
    camera_time = SDL_GetTicks();
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    return status;
}

int android_inventory_quantity(cptr prompt, int maximum)
{
    if (!inventory_action) return -1;
    if (maximum <= 1) return maximum;
    inv_snapshot("quantity", prompt ? prompt : "How many?", 0, maximum);
    int n = inv_wait();
    if (n < 1 || n > maximum) { inventory_cancelled = TRUE; return 0; }
    return n;
}

int android_inventory_text(cptr prompt, char* buffer, size_t length)
{
    if (!inventory_action) return -1;
    inventory_length = 0;
    inv_append("{\"mode\":\"text\",\"prompt\":"); inv_string(prompt);
    inv_append(",\"value\":"); inv_string(buffer);
    inv_append(",\"max\":"); inv_number(SDL_min(length - 1, sizeof(inventory_text) - 1));
    inv_append("}");
    if (inv_wait() != 1) return 0;
    my_strcpy(buffer, inventory_text, length);
    return 1;
}

static void inv_hide(void)
{
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    if (env && activity) {
        jclass cls = (*env)->GetObjectClass(env, activity);
        jmethodID method = (*env)->GetMethodID(env, cls, "hideInventory", "()V");
        if (method) (*env)->CallVoidMethod(env, activity, method);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, cls);
        (*env)->DeleteLocalRef(env, activity);
    }
}

/* Release Java input before the engine emits results or waits at -more-. */
void android_inventory_commit(void)
{
    if (!inventory_action || inventory_committed) return;
    inventory_committed = TRUE;
    /* Selection dialogs clear command view. Restore it before result messages
     * capture their -more- camera, including the fletching start message. */
    inv_hide();
}

/* A failed activation must leave its explanation visible, even without a turn. */
void android_inventory_failure(void)
{
    if (!inventory_action) return;
    inventory_failed = TRUE;
    android_inventory_commit();
}

static bool inv_command_allowed(void)
{
    /* The normal request_command checks equipped '^' inscriptions. Inventory
     * actions bypass that keyboard request, so preserve the same checks here. */
    for (int i = INVEN_WIELD; i < INVEN_TOTAL; i++) {
        if (!inventory[i].k_idx || !inventory[i].obj_note) continue;
        cptr s = strchr(quark_str(inventory[i].obj_note), '^');
        while (s) {
            if ((s[1] == p_ptr->command_cmd || s[1] == '*')
                && !get_check("Are you sure? ")) return FALSE;
            s = strchr(s + 1, '^');
        }
    }
    return TRUE;
}

void android_inventory(void)
{
    if (!dungeon_input() || inventory_active) return;
    bool floor = ground_browse_requested;
    ground_browse_requested = FALSE;
    inventory_active = TRUE;
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    Term_fresh();
    while (TRUE) {
        item_tester_tval = 0; item_tester_hook = NULL; item_tester_full = FALSE;
        inv_snapshot(floor ? "floor" : "browse", "", floor ? USE_FLOOR : 0, 0);
        if (floor && !inventory_choice_count) break;
        int result = inv_wait();
        if (result < 0) break;
        int index = result, action = SDL_AtomicGet(&inventory_answer_action);
        if (floor) {
            if (result >= inventory_choice_count) continue;
            index = inventory_choices[result];
            if (index >= 0 || !o_list[-index].k_idx
                || o_list[-index].iy != p_ptr->py || o_list[-index].ix != p_ptr->px) continue;
        }
        const char commands[] = { 'w', 'r', 'E', 'q', 'u', 'd', 't', 'k', 'a', 'p', 'g', '-' };
        /* Reject unsupported actions before shifting masks or indexing commands.
         * Item and action are separate fields, so neither can spill into the other. */
        if (action < 0 || action >= (int)sizeof(commands)
            || (!floor && (index >= INVEN_TOTAL || !inventory[index].k_idx))
            || !(inv_actions(index) & (1 << action))) continue;
        inventory_selected = index;
        inventory_selection_pending = TRUE;
        inventory_action = TRUE; inventory_cancelled = inventory_committed = FALSE;
        inventory_failed = FALSE;
        p_ptr->command_cmd = commands[action];
        p_ptr->command_arg = 0;
        msg_flag = FALSE; /* Old top-line text must not force a terminal -more-. */
        if (!inv_command_allowed()) {
            inventory_action = FALSE; inventory_selected = -1;
            continue;
        }
        switch (action) {
        case 0: do_cmd_wield(NULL, 0); break;
        case 1: do_cmd_takeoff(NULL, 0); break;
        case 2: do_cmd_eat_food(NULL, 0); break;
        case 3: do_cmd_quaff_potion(NULL, 0); break;
        case 4: do_cmd_use_item(); break; /* Existing fuel compatibility/waste prompts. */
        case 5: do_cmd_drop(); break;
        case 6: do_cmd_throw(FALSE); break;
        case 7: do_cmd_destroy(); break;
        case 8: do_cmd_activate_staff(NULL, 0); break;
        case 9: do_cmd_play_instrument(NULL, 0); break;
        case 11: do_cmd_fletchery(); break;
        case 10:
        {
            object_type* o = &o_list[-index];
            if (!inven_carry_okay(o)) {
                android_inventory_failure();
                msg_print("You have no room for this item.");
            } else if (p_ptr->total_weight + o->weight > weight_limit() * 3 / 2) {
                android_inventory_failure();
                msg_print("You cannot lift this item.");
            } else {
                android_inventory_commit();
                p_ptr->previous_action[0] = ACTION_MISC;
                p_ptr->energy_use = 100;
                py_pickup_aux(-index);
            }
            break;
        }
        }
        inventory_action = FALSE; inventory_selected = -1;
        if (p_ptr->energy_use || inventory_failed || (action < 8 && !inventory_cancelled)) break;
    }
    inventory_action = inventory_active = FALSE;
    inventory_selection_pending = FALSE;
    SDL_AtomicSet(&inventory_request, 0);
    inv_hide();
    Term_flush();
    camera_time = SDL_GetTicks();
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
}
