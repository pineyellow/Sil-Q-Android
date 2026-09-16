/* Song selection only. The core command retains oath checks and change_song. */
static SDL_atomic_t song_request, song_answer;

JNIEXPORT void JNICALL Java_com_pineyellow_silq_SilActivity_nativeSongReply(
    JNIEnv* env, jobject self, jint request, jint choice)
{
    (void)env; (void)self;
    if (request > 0 && SDL_AtomicCAS(&song_request, request, 0))
        SDL_AtomicSet(&song_answer, choice);
    wake_renderer();
}

static void song_entry(int id, cptr name, cptr description, bool current, bool* first)
{
    if (!*first) inv_append(",");
    *first = FALSE;
    inv_append("{\"id\":"); inv_number(id);
    inv_append(",\"name\":"); inv_string(name);
    inv_append(",\"description\":"); inv_string(description);
    inv_append(",\"current\":"); inv_append(current ? "true" : "false");
    inv_append("}");
}

int android_choose_song(void)
{
    static int serial;
    if (!dungeon_input()) return -1;
    int id = serial = serial == 2147483647 ? 1 : serial + 1;
    int count = 0;
    bool first = TRUE;
    inventory_length = 0;
    inv_append("{\"songs\":[");
    for (int i = 0; i < SNG_WOVEN_THEMES; i++) {
        if (!p_ptr->active_ability[S_SNG][i]) continue;
        ability_type* ability = &b_info[ability_index(S_SNG, i)];
        song_entry(i, b_name + ability->name, b_text + ability->text,
            p_ptr->song1 == i || p_ptr->song2 == i, &first);
        count++;
    }
    if (count) {
        if (p_ptr->song2 != SNG_NOTHING)
            song_entry(SNG_EXCHANGE_THEMES, "Exchange Themes", "", FALSE, &first);
    }
    inv_append("]");
    if (p_ptr->song1 != SNG_NOTHING) {
        inv_append(",\"stopChoice\":");
        inv_number(SNG_NOTHING);
    }
    inv_append("}");
    SDL_AtomicSet(&song_answer, -2);
    SDL_AtomicSet(&song_request, id);
    note_active = TRUE;
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    pointer_down = FALSE;
    cancel_camera_touch();
    Term_fresh();
    JNIEnv* env = SDL_AndroidGetJNIEnv();
    jobject activity = SDL_AndroidGetActivity();
    bool shown = FALSE;
    if (env && activity) {
        jclass cls = (*env)->GetObjectClass(env, activity);
        jmethodID method = (*env)->GetMethodID(env, cls, "showSongDialog", "(Ljava/lang/String;I)V");
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
    if (shown) while (SDL_AtomicGet(&song_answer) == -2) {
        if (android_save_interrupt()) {
            SDL_AtomicSet(&song_answer, -1);
            break;
        }
        pump_events(TRUE, TRUE);
    }
    int choice = shown ? SDL_AtomicGet(&song_answer) : -1;
    SDL_AtomicSet(&song_request, 0);
    Term_flush();
    note_active = FALSE;
    camera_time = SDL_GetTicks();
    SDL_AtomicAdd(&dpad_context, 1);
    SDL_AtomicAdd(&dpad_press_token, 1);
    if (!count) return -1;
    if (choice == SNG_NOTHING) return choice;
    if (choice == SNG_EXCHANGE_THEMES && p_ptr->song2 != SNG_NOTHING) return choice;
    if (choice >= 0 && choice < SNG_WOVEN_THEMES
        && p_ptr->active_ability[S_SNG][choice]) return choice;
    return -1;
}
