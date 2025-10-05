#include "../include/startup_validator.h"
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdbool.h>
#include "../include/native_config.h"

// From startup_validator.c: controls whether network validation is required
extern bool g_require_network_validation;

// 添加线程支持
#ifdef _WIN32
#include <windows.h>
#include <process.h>
#include <stdint.h>
#define THREAD_HANDLE HANDLE
#define CREATE_THREAD(thread, func, param) ((thread = (HANDLE)_beginthreadex(NULL, 0, func, param, 0, NULL)) != NULL)
#define THREAD_FUNC unsigned __stdcall
#define THREAD_RETURN(val) _endthreadex((unsigned)(val)); return (unsigned)(val);
#define SLEEP_MS(ms) Sleep(ms)
#else
#include <pthread.h>
#include <unistd.h>
#include <signal.h>
#include <stdint.h>
#define THREAD_HANDLE pthread_t
#define CREATE_THREAD(thread, func, param) (pthread_create(&thread, NULL, func, param) == 0)
#define THREAD_FUNC void*
#define THREAD_RETURN(val) pthread_exit((void*)(intptr_t)(val));
#define SLEEP_MS(ms) usleep((ms) * 1000)
#endif

#define ACTION_ON_ENABLE        1
#define ACTION_ON_DISABLE       2
#define ACTION_COMMAND_EXECUTE  3
#define ACTION_PERMISSION_CHECK 4
#define ACTION_PLAYER_JOIN      5
#define ACTION_GET_STATUS       6
#define ACTION_PLAYER_QUIT      7

typedef struct {
    bool plugin_enabled;
    bool core_modules_enabled;
    bool commands_registered;
    char* validation_token;
    long last_validation_time;
    char* data_folder_path;
    char* server_version;
    int server_max_players;
    int server_port;
    
    // 会话token（仅本次进程有效）
    char* session_token;

    JNIEnv* jni_env;
    jobject plugin_instance;
    jclass plugin_class;

    uint64_t encrypted_security_state;
    uint32_t access_control_hash;
    time_t last_integrity_check;

    char verified_admin_players[16][64];
    int verified_admin_count;
    
    char authenticated_players[32][64];
    int authenticated_player_count;
    
    // 定时验证状态
    time_t last_periodic_validation;     // 上次定时验证时间
    int validation_failure_count;        // 连续验证失败次数
    bool periodic_validation_enabled;    // 是否启用定时验证
    char* stored_cards[16];              // 存储的卡密列表（最多16个）
    int stored_card_count;               // 存储的卡密数量
    char* validation_host;               // 验证服务器地址
    char* validation_app_id;             // 应用ID
    char* validation_rc4_key;            // RC4密钥
    int validation_timeout;              // 验证超时时间
    
    // 🆕 定时验证线程控制
    THREAD_HANDLE validation_thread;     // 验证线程句柄
    volatile bool validation_thread_running;  // 线程运行状态
    volatile bool should_terminate;      // 强制终止标志
} native_plugin_state_t;

static native_plugin_state_t g_plugin_state = {0};

// 安全常量和宏
#define SECURITY_ENCRYPT_KEY    0xDEADBEEFCAFEBABELL
#define ACCESS_CONTROL_MAGIC    0x1337C0DELL
#define INTEGRITY_CHECK_INTERVAL 30
#define ADMIN_PASSWORD          "345710"

// 定时验证常量
#define PERIODIC_VALIDATION_INTERVAL    120     // 2分钟 = 120秒
#define MAX_VALIDATION_FAILURES         3       // 最大允许连续失败次数
#define VALIDATION_HOST_DEFAULT         "wig.nbhao.org"
#define VALIDATION_APP_ID_DEFAULT       "PATHFINDER_PLUGIN"
#define VALIDATION_RC4_KEY_DEFAULT      "PathFinderSecure2024"
#define VALIDATION_TIMEOUT_DEFAULT      30

#define ENCRYPT_SECURITY_STATE(state) ((state) ^ SECURITY_ENCRYPT_KEY)
#define DECRYPT_SECURITY_STATE(enc)   ((enc) ^ SECURITY_ENCRYPT_KEY)

// 🆕 前向声明
static THREAD_FUNC periodic_validation_thread(void* param);
static void force_terminate_program();
static bool start_validation_thread();
static void stop_validation_thread();
static bool native_disable_plugin_completely();

static bool is_player_admin_verified(const char* player_name) {
    if (!player_name) {
        return false;
    }
    
    for (int i = 0; i < g_plugin_state.verified_admin_count; i++) {
        if (strcmp(g_plugin_state.verified_admin_players[i], player_name) == 0) {
            return true;
        }
    }
    return false;
}

static bool is_player_authenticated(const char* player_uuid) {
    if (!player_uuid) {
        return false;
    }
    
    for (int i = 0; i < g_plugin_state.authenticated_player_count; i++) {
        if (strcmp(g_plugin_state.authenticated_players[i], player_uuid) == 0) {
            return true;
        }
    }
    return false;
}

// 添加玩家到已认证列表
static bool add_authenticated_player(const char* player_uuid) {
    if (!player_uuid || g_plugin_state.authenticated_player_count >= 32) return false;
    
    // 检查是否已存在
    if (is_player_authenticated(player_uuid)) return true;
    
    // 添加到列表
    strncpy(g_plugin_state.authenticated_players[g_plugin_state.authenticated_player_count], 
            player_uuid, 63);
    g_plugin_state.authenticated_players[g_plugin_state.authenticated_player_count][63] = '\0';
    g_plugin_state.authenticated_player_count++;
    return true;
}

// 从已认证列表中移除玩家
static bool remove_authenticated_player(const char* player_uuid) {
    if (!player_uuid) return false;
    
    for (int i = 0; i < g_plugin_state.authenticated_player_count; i++) {
        if (strcmp(g_plugin_state.authenticated_players[i], player_uuid) == 0) {
            // 移除这个玩家，将后面的玩家前移
            for (int j = i; j < g_plugin_state.authenticated_player_count - 1; j++) {
                strcpy(g_plugin_state.authenticated_players[j], 
                       g_plugin_state.authenticated_players[j + 1]);
            }
            g_plugin_state.authenticated_player_count--;
            printf("Removed %s from authenticated player list (total: %d)\n", 
                   player_uuid, g_plugin_state.authenticated_player_count);
            return true;
        }
    }
    return false;
}

// =============================================================================
// 工具函数 - JNI参数处理
// =============================================================================

// 从jobjectArray中提取字符串参数
static char* extract_string_param(JNIEnv* env, jobjectArray params, int index) {
    if (!params || !env) return NULL;
    
    jsize length = (*env)->GetArrayLength(env, params);
    if (index >= length) return NULL;
    
    jobject obj = (*env)->GetObjectArrayElement(env, params, index);
    if (!obj) return NULL;
    
    // 安全的JNI调用检查
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) {
        (*env)->DeleteLocalRef(env, obj);
        return NULL;
    }
    
    if ((*env)->IsInstanceOf(env, obj, stringClass)) {
        jstring jstr = (jstring)obj;
        const char* str = (*env)->GetStringUTFChars(env, jstr, NULL);
        if (!str) {
            (*env)->DeleteLocalRef(env, obj);
            (*env)->DeleteLocalRef(env, stringClass);
            return NULL;
        }
        
        char* result = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
        (*env)->DeleteLocalRef(env, obj);
        (*env)->DeleteLocalRef(env, stringClass);
        return result;
    }
    
    (*env)->DeleteLocalRef(env, obj);
    (*env)->DeleteLocalRef(env, stringClass);
    return NULL;
}

// 从jobjectArray中提取整数参数
static int extract_int_param(JNIEnv* env, jobjectArray params, int index) {
    if (!params || !env) return 0;
    
    jsize length = (*env)->GetArrayLength(env, params);
    if (index >= length) return 0;
    
    jobject obj = (*env)->GetObjectArrayElement(env, params, index);
    if (!obj) return 0;
    
    // 安全的JNI调用检查
    jclass integerClass = (*env)->FindClass(env, "java/lang/Integer");
    if (!integerClass) {
        (*env)->DeleteLocalRef(env, obj);
        return 0;
    }
    
    if ((*env)->IsInstanceOf(env, obj, integerClass)) {
        jmethodID intValue = (*env)->GetMethodID(env, integerClass, "intValue", "()I");
        if (!intValue) {
            (*env)->DeleteLocalRef(env, obj);
            (*env)->DeleteLocalRef(env, integerClass);
            return 0;
        }
        
        int result = (*env)->CallIntMethod(env, obj, intValue);
        (*env)->DeleteLocalRef(env, obj);
        (*env)->DeleteLocalRef(env, integerClass);
        return result;
    }
    
    (*env)->DeleteLocalRef(env, obj);
    (*env)->DeleteLocalRef(env, integerClass);
    return 0;
}





// 从Java层获取翻译消息的函数（服务器默认语言）
static char* get_translated_message(JNIEnv* env, const char* key, const char* default_msg) {
    if (!env || !key || !default_msg) return strdup(default_msg ? default_msg : "");
    
    // 获取TOCpluginNative类
    jclass plugin_class = (*env)->FindClass(env, "org/momu/tOCplugin/TOCpluginNative");
    if (!plugin_class) {
        printf("Failed to find TOCpluginNative class\n");
        return strdup(default_msg);
    }
    
    // 获取getTranslatedMessage方法
    jmethodID get_translated_method = (*env)->GetStaticMethodID(env, plugin_class, 
                                                               "getTranslatedMessage", 
                                                               "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!get_translated_method) {
        printf("Failed to find getTranslatedMessage method\n");
        (*env)->DeleteLocalRef(env, plugin_class);
        return strdup(default_msg);
    }
    
    // 创建Java字符串
    jstring j_key = (*env)->NewStringUTF(env, key);
    jstring j_default = (*env)->NewStringUTF(env, default_msg);
    
    if (!j_key || !j_default) {
        printf("Failed to create Java strings\n");
        if (j_key) (*env)->DeleteLocalRef(env, j_key);
        if (j_default) (*env)->DeleteLocalRef(env, j_default);
        (*env)->DeleteLocalRef(env, plugin_class);
        return strdup(default_msg);
    }
    
    // 调用Java方法获取翻译
    jstring j_result = (jstring)(*env)->CallStaticObjectMethod(env, plugin_class, get_translated_method, j_key, j_default);
    
    char* result = strdup(default_msg);
    if (j_result) {
        const char* translated = (*env)->GetStringUTFChars(env, j_result, NULL);
        if (translated) {
            free(result);
            result = strdup(translated);
            (*env)->ReleaseStringUTFChars(env, j_result, translated);
        }
        (*env)->DeleteLocalRef(env, j_result);
    }
    
    // 清理引用
    (*env)->DeleteLocalRef(env, j_key);
    (*env)->DeleteLocalRef(env, j_default);
    (*env)->DeleteLocalRef(env, plugin_class);
    
    return result;
}

// 从Java层获取特定玩家的翻译消息（使用玩家个人语言）
static char* get_translated_message_for_player(JNIEnv* env, const char* player_name, const char* key, const char* default_msg) {
    if (!env || !key || !default_msg) return strdup(default_msg ? default_msg : "");
    
    // 如果没有玩家名，回退到服务器默认语言
    if (!player_name || strlen(player_name) == 0) {
        return get_translated_message(env, key, default_msg);
    }
    
    // 获取TOCpluginNative类
    jclass plugin_class = (*env)->FindClass(env, "org/momu/tOCplugin/TOCpluginNative");
    if (!plugin_class) {
        printf("Failed to find TOCpluginNative class\n");
        return get_translated_message(env, key, default_msg);
    }
    
    // 获取getTranslatedMessageForPlayer方法
    jmethodID get_translated_method = (*env)->GetStaticMethodID(env, plugin_class, 
                                                               "getTranslatedMessageForPlayer", 
                                                               "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!get_translated_method) {
        printf("Failed to find getTranslatedMessageForPlayer method\n");
        (*env)->DeleteLocalRef(env, plugin_class);
        return get_translated_message(env, key, default_msg);
    }
    
    // 创建Java字符串
    jstring j_player = (*env)->NewStringUTF(env, player_name);
    jstring j_key = (*env)->NewStringUTF(env, key);
    jstring j_default = (*env)->NewStringUTF(env, default_msg);
    
    if (!j_player || !j_key || !j_default) {
        printf("Failed to create Java strings\n");
        if (j_player) (*env)->DeleteLocalRef(env, j_player);
        if (j_key) (*env)->DeleteLocalRef(env, j_key);
        if (j_default) (*env)->DeleteLocalRef(env, j_default);
        (*env)->DeleteLocalRef(env, plugin_class);
        return get_translated_message(env, key, default_msg);
    }
    
    // 调用Java方法获取翻译
    jstring j_result = (jstring)(*env)->CallStaticObjectMethod(env, plugin_class, get_translated_method, j_player, j_key, j_default);
    
    char* result = strdup(default_msg);
    if (j_result) {
        const char* translated = (*env)->GetStringUTFChars(env, j_result, NULL);
        if (translated) {
            free(result);
            result = strdup(translated);
            (*env)->ReleaseStringUTFChars(env, j_result, translated);
        }
        (*env)->DeleteLocalRef(env, j_result);
    }
    
    // 清理引用
    (*env)->DeleteLocalRef(env, j_player);
    (*env)->DeleteLocalRef(env, j_key);
    (*env)->DeleteLocalRef(env, j_default);
    (*env)->DeleteLocalRef(env, plugin_class);
    
    return result;
}

// 调用Java层处理子命令
static void call_java_subcommand(JNIEnv* env, const char* sender_name, jarray args_array, const char* sender_type, const char* token) {
    if (!env || !sender_name || !args_array) return;
    
    // 获取TOCpluginNative类
    jclass plugin_class = (*env)->FindClass(env, "org/momu/tOCplugin/TOCpluginNative");
    if (!plugin_class) {
        printf("Failed to find TOCpluginNative class for subcommand handling\n");
        return;
    }
    
    // 查找handleSubcommandFromNative静态方法
    jmethodID handle_method = (*env)->GetStaticMethodID(env, plugin_class, 
                                                       "handleSubcommandFromNative", 
                                                       "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!handle_method) {
        printf("Failed to find handleSubcommandFromNative method\n");
        (*env)->DeleteLocalRef(env, plugin_class);
        return;
    }
    
    // 创建Java字符串
    jstring j_sender = (*env)->NewStringUTF(env, sender_name);
    jstring j_sender_type = (*env)->NewStringUTF(env, sender_type);
    jstring j_token = (*env)->NewStringUTF(env, token ? token : "");
    
    if (j_sender && j_sender_type && j_token) {
        // 调用Java方法处理子命令，传递完整的args数组
        (*env)->CallStaticVoidMethod(env, plugin_class, handle_method, j_sender, args_array, j_sender_type, j_token);
    }
    
    // 清理引用
    if (j_sender) (*env)->DeleteLocalRef(env, j_sender);
    if (j_sender_type) (*env)->DeleteLocalRef(env, j_sender_type);
    if (j_token) (*env)->DeleteLocalRef(env, j_token);
    (*env)->DeleteLocalRef(env, plugin_class);
}

// 发送消息到Java层处理（支持玩家和控制台）
static void send_java_message(JNIEnv* env, const char* target, const char* message) {
    if (!env || !target || !message) return;
    
    // 获取TOCpluginNative类
    jclass plugin_class = (*env)->FindClass(env, "org/momu/tOCplugin/TOCpluginNative");
    if (!plugin_class) {
        printf("Failed to find TOCpluginNative class for message sending\n");
        printf("Fallback message to %s: %s\n", target, message);
        return;
    }
    
    // 查找sendMessage静态方法
    jmethodID send_method = (*env)->GetStaticMethodID(env, plugin_class, 
                                                     "sendMessageFromNative", 
                                                     "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!send_method) {
        printf("Failed to find sendMessageFromNative method\n");
        printf("Fallback message to %s: %s\n", target, message);
        (*env)->DeleteLocalRef(env, plugin_class);
        return;
    }
    
    // 创建Java字符串
    jstring j_target = (*env)->NewStringUTF(env, target);
    jstring j_message = (*env)->NewStringUTF(env, message);
    
    if (!j_target || !j_message) {
        printf("Failed to create Java strings for message\n");
        if (j_target) (*env)->DeleteLocalRef(env, j_target);
        if (j_message) (*env)->DeleteLocalRef(env, j_message);
        (*env)->DeleteLocalRef(env, plugin_class);
        return;
    }
    
    // 调用Java方法发送消息
    (*env)->CallStaticVoidMethod(env, plugin_class, send_method, j_target, j_message);
    
    // 清理引用
    (*env)->DeleteLocalRef(env, j_target);
    (*env)->DeleteLocalRef(env, j_message);
    (*env)->DeleteLocalRef(env, plugin_class);
}

static bool register_command_executor(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) return false;
    
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) return false;
    
    // 调用getCommand("toc").setExecutor(this)
    jmethodID get_command_method = (*env)->GetMethodID(env, plugin_class, "getCommand", "(Ljava/lang/String;)Lorg/bukkit/command/PluginCommand;");
    if (!get_command_method) {
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    jstring command_name = (*env)->NewStringUTF(env, "toc");
    jobject command_obj = (*env)->CallObjectMethod(env, plugin_instance, get_command_method, command_name);
    
    if (command_obj) {
        jclass command_class = (*env)->GetObjectClass(env, command_obj);
        jmethodID set_executor_method = (*env)->GetMethodID(env, command_class, "setExecutor", "(Lorg/bukkit/command/CommandExecutor;)V");
        
        if (set_executor_method) {
            (*env)->CallVoidMethod(env, command_obj, set_executor_method, plugin_instance);

        }
        
        (*env)->DeleteLocalRef(env, command_class);
        (*env)->DeleteLocalRef(env, command_obj);
    }
    
    (*env)->DeleteLocalRef(env, command_name);
    (*env)->DeleteLocalRef(env, plugin_class);
    return true;
}

// 调用Java方法注册Tab补全器
static bool register_tab_completer(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) return false;
    
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) return false;
    
    // 调用getCommand("toc").setTabCompleter(this)
    jmethodID get_command_method = (*env)->GetMethodID(env, plugin_class, "getCommand", "(Ljava/lang/String;)Lorg/bukkit/command/PluginCommand;");
    if (!get_command_method) {
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    jstring command_name = (*env)->NewStringUTF(env, "toc");
    jobject command_obj = (*env)->CallObjectMethod(env, plugin_instance, get_command_method, command_name);
    
    if (command_obj) {
        jclass command_class = (*env)->GetObjectClass(env, command_obj);
        jmethodID set_tab_completer_method = (*env)->GetMethodID(env, command_class, "setTabCompleter", "(Lorg/bukkit/command/TabCompleter;)V");
        
        if (set_tab_completer_method) {
            (*env)->CallVoidMethod(env, command_obj, set_tab_completer_method, plugin_instance);
        }
        
        (*env)->DeleteLocalRef(env, command_class);
        (*env)->DeleteLocalRef(env, command_obj);
    }
    
    (*env)->DeleteLocalRef(env, command_name);
    (*env)->DeleteLocalRef(env, plugin_class);
    return true;
}

// 调用Java方法注册事件监听器
static bool register_event_listeners(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) return false;
    
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) return false;
    
    // 获取服务器实例
    jmethodID get_server_method = (*env)->GetMethodID(env, plugin_class, "getServer", "()Lorg/bukkit/Server;");
    if (!get_server_method) {
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    jobject server_obj = (*env)->CallObjectMethod(env, plugin_instance, get_server_method);
    if (!server_obj) {
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    // 获取PluginManager
    jclass server_class = (*env)->GetObjectClass(env, server_obj);
    jmethodID get_plugin_manager_method = (*env)->GetMethodID(env, server_class, "getPluginManager", "()Lorg/bukkit/plugin/PluginManager;");
    
    if (!get_plugin_manager_method) {
        (*env)->DeleteLocalRef(env, server_class);
        (*env)->DeleteLocalRef(env, server_obj);
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    jobject plugin_manager_obj = (*env)->CallObjectMethod(env, server_obj, get_plugin_manager_method);
    if (!plugin_manager_obj) {
        (*env)->DeleteLocalRef(env, server_class);
        (*env)->DeleteLocalRef(env, server_obj);
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    // 注册插件本身为事件监听器
    jclass plugin_manager_class = (*env)->GetObjectClass(env, plugin_manager_obj);
    jmethodID register_events_method = (*env)->GetMethodID(env, plugin_manager_class, "registerEvents", "(Lorg/bukkit/event/Listener;Lorg/bukkit/plugin/Plugin;)V");
    
    if (register_events_method) {
        (*env)->CallVoidMethod(env, plugin_manager_obj, register_events_method, plugin_instance, plugin_instance);
    }
    
    // 注册MasterListener
    jclass master_listener_class = (*env)->FindClass(env, "org/momu/tOCplugin/listener/MasterListener");
    if (master_listener_class) {
        jmethodID master_constructor = (*env)->GetMethodID(env, master_listener_class, "<init>", "()V");
        if (master_constructor) {
            jobject master_listener_obj = (*env)->NewObject(env, master_listener_class, master_constructor);
            if (master_listener_obj) {
                (*env)->CallVoidMethod(env, plugin_manager_obj, register_events_method, master_listener_obj, plugin_instance);
                if ((*env)->ExceptionCheck(env)) {
                    printf("[PathFinder] ⚠️ Exception during MasterListener registration, skipping\n");
                    (*env)->ExceptionClear(env);
                }
                (*env)->DeleteLocalRef(env, master_listener_obj);
            } else if ((*env)->ExceptionCheck(env)) {
                printf("[PathFinder] ⚠️ Exception creating MasterListener instance, skipping\n");
                (*env)->ExceptionClear(env);
            }
        } else if ((*env)->ExceptionCheck(env)) {
            printf("[PathFinder] ⚠️ MasterListener constructor not found, skipping\n");
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, master_listener_class);
    } else if ((*env)->ExceptionCheck(env)) {
        printf("[PathFinder] ℹ️ MasterListener class not found, skipping\n");
        (*env)->ExceptionClear(env);
    }
    
    // 注册PlayerMoveListener
    jclass player_move_listener_class = (*env)->FindClass(env, "org/momu/tOCplugin/listener/PlayerMoveListener");
    if (player_move_listener_class) {
        jmethodID move_constructor = (*env)->GetMethodID(env, player_move_listener_class, "<init>", "(Lorg/momu/tOCplugin/TOCpluginNative;)V");
        if (move_constructor) {
            jobject player_move_listener_obj = (*env)->NewObject(env, player_move_listener_class, move_constructor, plugin_instance);
            if (player_move_listener_obj) {
                (*env)->CallVoidMethod(env, plugin_manager_obj, register_events_method, player_move_listener_obj, plugin_instance);
                if ((*env)->ExceptionCheck(env)) {
                    printf("[PathFinder] ⚠️ Exception during PlayerMoveListener registration, skipping\n");
                    (*env)->ExceptionClear(env);
                }
                (*env)->DeleteLocalRef(env, player_move_listener_obj);
            } else if ((*env)->ExceptionCheck(env)) {
                printf("[PathFinder] ⚠️ Exception creating PlayerMoveListener instance, skipping\n");
                (*env)->ExceptionClear(env);
            }
        } else if ((*env)->ExceptionCheck(env)) {
            printf("[PathFinder] ⚠️ PlayerMoveListener constructor not found, skipping\n");
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, player_move_listener_class);
    } else if ((*env)->ExceptionCheck(env)) {
        printf("[PathFinder] ℹ️ PlayerMoveListener class not found, skipping\n");
        (*env)->ExceptionClear(env);
    }
    
    // 清理引用
    (*env)->DeleteLocalRef(env, plugin_manager_class);
    (*env)->DeleteLocalRef(env, plugin_manager_obj);
    (*env)->DeleteLocalRef(env, server_class);
    (*env)->DeleteLocalRef(env, server_obj);
    (*env)->DeleteLocalRef(env, plugin_class);
    
    return true;
}

// 启动定时验证任务
static bool start_periodic_validation_task(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) return false;
    if (!g_require_network_validation) {
        // Skip starting periodic validation when network validation is disabled
        return true;
    }
    
    if (!start_validation_thread()) {
        printf("[PathFinder] Failed to start validation thread\n");
        return false;
    }
    return true;
}

// 🆕 定时验证线程主函数
static THREAD_FUNC periodic_validation_thread(void* param) {
    (void)param; // 避免未使用参数警告
    if (!g_require_network_validation) {
        THREAD_RETURN(0);
    }
    
    while (g_plugin_state.validation_thread_running && !g_plugin_state.should_terminate) {
        // 等待验证间隔时间 (2分钟 = 120秒)
        for (int i = 0; i < PERIODIC_VALIDATION_INTERVAL && g_plugin_state.validation_thread_running; i++) {
            SLEEP_MS(1000); // 每秒检查一次是否需要停止
            
            if (g_plugin_state.should_terminate) {
                printf("[PathFinder] Termination signal received in validation thread\n");
                THREAD_RETURN(0);
            }
        }
        
        if (!g_plugin_state.validation_thread_running) {
            break;
        }
        
        if (!g_plugin_state.periodic_validation_enabled) {
            printf("[PathFinder] Periodic validation disabled, stopping thread\n");
            break;
        }
        
        // 检查验证参数完整性
        if (!g_plugin_state.validation_host || !g_plugin_state.validation_app_id || 
            !g_plugin_state.validation_rc4_key || g_plugin_state.stored_card_count <= 0) {
            printf("[PathFinder] ❌ Validation parameters corrupted - SECURITY BREACH!\n");
            g_plugin_state.validation_failure_count = MAX_VALIDATION_FAILURES;
            force_terminate_program();
            THREAD_RETURN(1);
        }
        
        // 执行卡密验证
        bool validation_success = false;
        for (int i = 0; i < g_plugin_state.stored_card_count; i++) {
            if (!g_plugin_state.stored_cards[i]) continue;
            if (validate_with_wig_c(g_plugin_state.validation_host, 
                                   g_plugin_state.validation_app_id, 
                                   g_plugin_state.stored_cards[i], 
                                   g_plugin_state.validation_rc4_key, 
                                   g_plugin_state.validation_timeout)) {
                validation_success = true;
                break;
            }
        }
        
        if (validation_success) {
            // 验证成功，重置失败计数
            g_plugin_state.validation_failure_count = 0;
            g_plugin_state.last_periodic_validation = time(NULL);
        } else {
            // 验证失败，增加失败计数
            g_plugin_state.validation_failure_count++;
            g_plugin_state.last_periodic_validation = time(NULL);
            
            printf("[PathFinder] Periodic validation FAILED (Count: %d/%d)\n", 
                   g_plugin_state.validation_failure_count, MAX_VALIDATION_FAILURES);
            
            if (g_plugin_state.validation_failure_count >= MAX_VALIDATION_FAILURES) {
                printf("[PathFinder] Maximum validation failures reached - disabling plugin\n");
            printf("[PathFinder] Three consecutive validation failures detected\n");
            
            force_terminate_program();
            THREAD_RETURN(2);
            }
        }
    }
    
    printf("[PathFinder] Periodic validation thread terminated\n");
    g_plugin_state.validation_thread_running = false;
    THREAD_RETURN(0);
}

// 🆕 启动验证线程
static bool start_validation_thread() {
    if (g_plugin_state.validation_thread_running) {
        return true;
    }
    if (!g_require_network_validation) {
        return true;
    }
    
    g_plugin_state.validation_thread_running = true;
    g_plugin_state.should_terminate = false;
    
    if (CREATE_THREAD(g_plugin_state.validation_thread, periodic_validation_thread, NULL)) {
        return true;
    } else {
        printf("[PathFinder] CRITICAL: Failed to create validation thread\n");
        printf("[PathFinder] Security system compromised\n");
        g_plugin_state.validation_thread_running = false;
        force_terminate_program();
        return false;
    }
}

// 🆕 停止验证线程
static void stop_validation_thread() {
    if (!g_plugin_state.validation_thread_running) {
        return;
    }
    
    printf("[PathFinder] Stopping validation thread...\n");
    g_plugin_state.validation_thread_running = false;
    
#ifdef _WIN32
    if (g_plugin_state.validation_thread) {
        WaitForSingleObject(g_plugin_state.validation_thread, 5000); // 5秒超时
        CloseHandle(g_plugin_state.validation_thread);
    }
#else
    if (g_plugin_state.validation_thread) {
        pthread_join(g_plugin_state.validation_thread, NULL);
    }
#endif
    
    printf("[PathFinder] ✅ Validation thread stopped\n");
}

static void force_terminate_program() {
    printf("[PathFinder] SECURITY VIOLATION DETECTED - disabling PathFinder plugin only\n");

    // Mark plugin as disabled at native layer
    g_plugin_state.core_modules_enabled = false;
    g_plugin_state.plugin_enabled = false;
    g_plugin_state.commands_registered = false;
    g_plugin_state.periodic_validation_enabled = false;
    g_plugin_state.should_terminate = true;

    // Best-effort sensitive data cleanup (remaining cleanup happens in onDisable)
    if (g_plugin_state.validation_token) {
        memset(g_plugin_state.validation_token, 0, strlen(g_plugin_state.validation_token));
        free(g_plugin_state.validation_token);
        g_plugin_state.validation_token = NULL;
    }

    // Clear security state
    g_plugin_state.encrypted_security_state = 0;
    g_plugin_state.access_control_hash = 0;

    // Stop background validation thread
    stop_validation_thread();

    // Gracefully disable the plugin via Bukkit API through JNI
    if (!native_disable_plugin_completely()) {
        printf("[PathFinder] ERROR\n");
    }
}

bool perform_native_secure_initialization(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) {
        return false;
    }
    
    printf("[PathFinder] Starting unlock plugin...\n");
    
    // 强制检查卡密验证状态
    if (!g_plugin_state.plugin_enabled || !g_plugin_state.core_modules_enabled) {
        printf("[PathFinder] SECURITY VIOLATION: Card validation not completed!\n");
        printf("[PathFinder] Cannot proceed with initialization - validation required\n");
        return false;
    }
    
    // 检查验证令牌
    if (!g_plugin_state.validation_token || strlen(g_plugin_state.validation_token) == 0) {
        printf("[PathFinder] SECURITY VIOLATION: No valid validation token found!\n");
        return false;
    }
    
    // 检查最后验证时间（防止令牌过期）
    time_t now = time(NULL);
    if (now - g_plugin_state.last_validation_time > 300) { // 5分钟过期
        printf("[PathFinder] SECURITY VIOLATION: Validation token expired!\n");
        return false;
    }
    
    // 🔑 第二步：注册命令执行器（验证通过后才允许）
    if (!register_command_executor(env, plugin_instance)) {
        printf("[PathFinder] Failed to register command executor\n");
        return false;
    }
    
    // 🔑 第三步：注册Tab补全器
    if (!register_tab_completer(env, plugin_instance)) {
        printf("[PathFinder] Failed to register tab completer\n");
        return false;
    }
    
    // 🔑 第四步：注册事件监听器（关键安全步骤）
    if (!register_event_listeners(env, plugin_instance)) {
        printf("[PathFinder] Failed to register event listeners\n");
        return false;
    }
    
    // 🔑 第五步：启动定时验证任务
    if (g_require_network_validation) {
        if (!start_periodic_validation_task(env, plugin_instance)) {
            printf("[PathFinder] Failed to start periodic validation task\n");
            return false;
        }
    }
    
    return true;
}

// 🔒 安全特性：调用Java层的安全初始化方法（已废弃，使用C层实现）
static bool call_java_secure_initialization(JNIEnv* env, jobject plugin_instance) {
    if (!env || !plugin_instance) {
        return false;
    }
    
    // 获取TOCpluginNative类
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) {
        printf("[PathFinder] Failed to get plugin class\n");
        return false;
    }
    
    // 查找performSecureInitialization方法
    jmethodID init_method = (*env)->GetMethodID(env, plugin_class, "performSecureInitialization", "()Z");
    if (!init_method) {
        printf("[PathFinder] Failed to find performSecureInitialization method\n");
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    // 调用performSecureInitialization方法
    jboolean result = (*env)->CallBooleanMethod(env, plugin_instance, init_method);
    
    // 检查是否有异常
    if ((*env)->ExceptionCheck(env)) {
        printf("[PathFinder] Exception occurred during secure initialization\n");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    (*env)->DeleteLocalRef(env, plugin_class);
    return result == JNI_TRUE;
}

// =============================================================================
// 定时验证功能实现
// =============================================================================

// 通过JNI向所有玩家广播消息
static bool broadcast_disable_message(JNIEnv* env, jobject plugin_instance) {
    // 获取插件类
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) {
        printf("[PathFinder] ❌ Failed to get plugin class for broadcast\n");
        return false;
    }
    
    // 获取getServer方法
    jmethodID get_server_method = (*env)->GetMethodID(env, plugin_class, "getServer", "()Lorg/bukkit/Server;");
    if (!get_server_method) {
        printf("[PathFinder] ❌ Failed to find getServer method\n");
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    // 调用getServer获取Server对象
    jobject server = (*env)->CallObjectMethod(env, plugin_instance, get_server_method);
    if (!server || (*env)->ExceptionCheck(env)) {
        printf("[PathFinder] ❌ Failed to get server instance\n");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    // 获取Server类
    jclass server_class = (*env)->GetObjectClass(env, server);
    if (!server_class) {
        printf("[PathFinder] ❌ Failed to get server class\n");
        (*env)->DeleteLocalRef(env, server);
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    jmethodID broadcast_method = (*env)->GetMethodID(env, server_class, "broadcastMessage", "(Ljava/lang/String;)I");
    if (!broadcast_method) {
        printf("[PathFinder] ❌ Failed to find broadcastMessage method\n");
        (*env)->DeleteLocalRef(env, server_class);
        (*env)->DeleteLocalRef(env, server);
        (*env)->DeleteLocalRef(env, plugin_class);
        return false;
    }
    
    const char* messages[] = {
        "§c§l[PathFinder] PLUGIN DISABLED BY NATIVE PROTECTION",
        "§c§l[PathFinder] License validation failed multiple times",
        "§c§l[PathFinder] All PathFinder functionality has been terminated",
        "§c§l[PathFinder] Contact server administrator immediately"
    };
    
    int message_count = sizeof(messages) / sizeof(messages[0]);
    
    for (int i = 0; i < message_count; i++) {
        jstring message_str = (*env)->NewStringUTF(env, messages[i]);
        if (message_str) {
            (*env)->CallIntMethod(env, server, broadcast_method, message_str);
            (*env)->DeleteLocalRef(env, message_str);
            
            if ((*env)->ExceptionCheck(env)) {
                printf("[PathFinder] ⚠️ Exception during message broadcast %d\n", i + 1);
                (*env)->ExceptionClear(env);
            }
        }
    }
    
    // 清理引用
    (*env)->DeleteLocalRef(env, server_class);
    (*env)->DeleteLocalRef(env, server);
    (*env)->DeleteLocalRef(env, plugin_class);
    return true;
}

// 通过JNI完全禁用插件（C层控制）
static bool native_disable_plugin_completely() {
    if (!g_plugin_state.jni_env || !g_plugin_state.plugin_instance) {
        printf("[PathFinder] ❌ Cannot disable plugin - no JNI environment\n");
        return false;
    }
    
    JNIEnv* env = g_plugin_state.jni_env;
    jobject plugin_instance = g_plugin_state.plugin_instance;

    // 1. 向所有玩家广播禁用消息
    if (!broadcast_disable_message(env, plugin_instance)) {
        printf("[PathFinder] ⚠️ Failed to broadcast messages, but continuing disable sequence\n");
    }
    
    // 2. 获取插件类
    jclass plugin_class = (*env)->GetObjectClass(env, plugin_instance);
    if (!plugin_class) {
        printf("[PathFinder] ❌ Failed to get plugin class for disable\n");
        return false;
    }
    
    // 3. 调用onDisable方法清理资源
    jmethodID on_disable_method = (*env)->GetMethodID(env, plugin_class, "onDisable", "()V");
    if (on_disable_method) {
        (*env)->CallVoidMethod(env, plugin_instance, on_disable_method);
        if ((*env)->ExceptionCheck(env)) {
            printf("[PathFinder] ⚠️ Exception during onDisable call\n");
            (*env)->ExceptionClear(env);
        } else {
            printf("[PathFinder] ✅ onDisable completed\n");
        }
    } else {
        printf("[PathFinder] ⚠️ onDisable method not found, skipping\n");
    }

    jmethodID get_server_method = (*env)->GetMethodID(env, plugin_class, "getServer", "()Lorg/bukkit/Server;");
    if (get_server_method) {
        jobject server = (*env)->CallObjectMethod(env, plugin_instance, get_server_method);
        if (server && !(*env)->ExceptionCheck(env)) {
            jclass server_class = (*env)->GetObjectClass(env, server);
            if (server_class) {
                jmethodID get_plugin_manager_method = (*env)->GetMethodID(env, server_class, "getPluginManager", "()Lorg/bukkit/plugin/PluginManager;");
                if (get_plugin_manager_method) {
                    jobject plugin_manager = (*env)->CallObjectMethod(env, server, get_plugin_manager_method);
                    if (plugin_manager && !(*env)->ExceptionCheck(env)) {
                        jclass plugin_manager_class = (*env)->GetObjectClass(env, plugin_manager);
                        if (plugin_manager_class) {
                            jmethodID disable_plugin_method = (*env)->GetMethodID(env, plugin_manager_class, "disablePlugin", "(Lorg/bukkit/plugin/Plugin;)V");
                            if (disable_plugin_method) {
                                (*env)->CallVoidMethod(env, plugin_manager, disable_plugin_method, plugin_instance);
                                if ((*env)->ExceptionCheck(env)) {
                                    printf("⚠️ Exception during plugin disable\n");
                                    (*env)->ExceptionClear(env);
                                }
                            }
                            (*env)->DeleteLocalRef(env, plugin_manager_class);
                        }
                        (*env)->DeleteLocalRef(env, plugin_manager);
                    }
                }
                (*env)->DeleteLocalRef(env, server_class);
            }
            (*env)->DeleteLocalRef(env, server);
        }
    }
    
    (*env)->DeleteLocalRef(env, plugin_class);
    return true;
}

// 禁用插件（由于验证失败）
static void disable_plugin_due_to_validation_failure() {
    printf("🚫 PLUGIN DISABLED: Too many validation failures\n");
    
    // 禁用核心功能
    g_plugin_state.core_modules_enabled = false;
    g_plugin_state.commands_registered = false;
    g_plugin_state.periodic_validation_enabled = false;
    g_plugin_state.plugin_enabled = false;
    
    // 清理验证状态
    g_plugin_state.validation_failure_count = MAX_VALIDATION_FAILURES;
    
    // 记录禁用原因
    printf("📝 Plugin disabled reason: Exceeded maximum validation failures (%d)\n", 
           MAX_VALIDATION_FAILURES);
    
    if (!native_disable_plugin_completely()) {
        printf("⚠️ Failed to completely disable plugin, but plugin is still marked as disabled in Native layer\n");
    }
}

// 初始化定时验证参数
static bool initialize_periodic_validation(const char* host, const char* app_id, const char* rc4_key, 
                                           int timeout, char** cards, int card_count) {
    if (!host || !app_id || !rc4_key || !cards || card_count <= 0) {
        printf("❌ Failed to initialize periodic validation - invalid parameters\n");
        return false;
    }
    
    // 清理旧数据
    if (g_plugin_state.validation_host) {
        free(g_plugin_state.validation_host);
    }
    if (g_plugin_state.validation_app_id) {
        free(g_plugin_state.validation_app_id);
    }
    if (g_plugin_state.validation_rc4_key) {
        free(g_plugin_state.validation_rc4_key);
    }
    for (int i = 0; i < g_plugin_state.stored_card_count; i++) {
        if (g_plugin_state.stored_cards[i]) {
            free(g_plugin_state.stored_cards[i]);
            g_plugin_state.stored_cards[i] = NULL;
        }
    }
    
    // 存储验证参数
    g_plugin_state.validation_host = strdup(host);
    g_plugin_state.validation_app_id = strdup(app_id);
    g_plugin_state.validation_rc4_key = strdup(rc4_key);
    g_plugin_state.validation_timeout = timeout;
    
    // 存储卡密列表（最多16个）
    g_plugin_state.stored_card_count = (card_count > 16) ? 16 : card_count;
    for (int i = 0; i < g_plugin_state.stored_card_count; i++) {
        g_plugin_state.stored_cards[i] = strdup(cards[i]);
    }
    
    // 初始化状态
    g_plugin_state.periodic_validation_enabled = true;
    g_plugin_state.last_periodic_validation = time(NULL);
    g_plugin_state.validation_failure_count = 0;
    return true;
}

// 🚫 旧的定时验证函数已被新的线程系统替代
// 保留 disable_plugin_due_to_validation_failure 函数，因为可能被其他地方使用

// Native插件启用处理
static bool native_handle_plugin_enable(JNIEnv* env, jobjectArray params) {
    printf("[PathFinder] Plugin enable requested\n");
    
    // 提取启动参数
    char* server_version = extract_string_param(env, params, 0);
    char* data_folder = extract_string_param(env, params, 1);
    int max_players = extract_int_param(env, params, 2);
    int server_port = extract_int_param(env, params, 3);
    
    if (!server_version || !data_folder) {
        printf("[PathFinder] Invalid startup parameters\n");
        if (server_version) free(server_version);
        if (data_folder) free(data_folder);
        return false;
    }
    
    // 保存启动参数
    if (g_plugin_state.server_version) free(g_plugin_state.server_version);
    if (g_plugin_state.data_folder_path) free(g_plugin_state.data_folder_path);
    
    g_plugin_state.server_version = strdup(server_version);
    g_plugin_state.data_folder_path = strdup(data_folder);
    g_plugin_state.server_max_players = max_players;
    g_plugin_state.server_port = server_port;
    
    bool validation_success = false;
    
    if (!initialize_anti_crack_protection()) {
        printf("[PathFinder] ❌ Security protection initialization failed\n");
        free(server_version);
        free(data_folder);
        return false;
    }
    
    if (!g_require_network_validation) {
        validation_success = true;
        if (g_plugin_state.validation_token) free(g_plugin_state.validation_token);
        g_plugin_state.validation_token = strdup("OFFLINE_VALIDATION");
    } else {
        char config_path[512];
        snprintf(config_path, sizeof(config_path), "%s/validata.yml", data_folder);
        FILE* config_file = fopen(config_path, "r");
        if (!config_file) {
            printf("[PathFinder] No config file found\n");
            validation_success = false;
        } else {
            char** cards = NULL;
            int card_count = 0;
            char line[256];
            bool in_cards_section = false;
            int line_count = 0;
            
            while (fgets(line, sizeof(line), config_file) && line_count < 100) {
                line_count++;
                line[strcspn(line, "\r\n")] = 0;
                
                if (strlen(line) == 0) continue;
                
                if (strstr(line, "cards:")) {
                    in_cards_section = true;
                    continue;
                }
                
                if (in_cards_section && strstr(line, "- ")) {
                    char* card_start = strstr(line, "- ") + 2;
                    while (*card_start == ' ' || *card_start == '\t') card_start++;
                    if (*card_start == '"') card_start++;
                    
                    // 🔧 修复：正确处理YAML注释，截断#及其后的内容
                    char* comment_pos = strchr(card_start, '#');
                    if (comment_pos) {
                        *comment_pos = '\0';  // 截断注释部分
                    }
                    
                    // 去除行尾的引号和空白字符
                    char* card_end = card_start + strlen(card_start) - 1;
                    while (card_end > card_start && (*card_end == ' ' || *card_end == '\t' || *card_end == '"' || *card_end == '\r' || *card_end == '\n')) {
                        *card_end = '\0';
                        card_end--;
                    }
                    
                    if (strlen(card_start) > 0) {
                        char** new_cards = realloc(cards, (card_count + 1) * sizeof(char*));
                        if (new_cards) {
                            cards = new_cards;
                            cards[card_count] = strdup(card_start);
                            if (cards[card_count]) {
                                card_count++;
                            }
                        }
                    }
                }
            }
            fclose(config_file);
                const char* host = "www.wigwy.xyz";
                const char* appid = "31283";
                const char* rc4_key = "5xzdMDzPt5duTf7";
                int timeout = 30;
                
                for (int i = 0; i < card_count && !validation_success; i++) {
                    if (cards[i] && strlen(cards[i]) > 0) {
                        // 调用Native层的WIG验证函数
                        if (validate_with_wig_c(host, appid, cards[i], rc4_key, timeout)) {
                            printf("[PathFinder] Validation SUCCESS for: %s\n", cards[i]);
                            validation_success = true;
                            if (g_plugin_state.validation_token) free(g_plugin_state.validation_token);
                            g_plugin_state.validation_token = strdup(cards[i]);
                            break;
                        } else {
                            printf("[PathFinder] Validation FAILED for: %s\n", cards[i]);
                        }
                    }
                }
                
                if (!validation_success) {
                    printf("[PathFinder] Validation failed!\n");
            }
            
            if (validation_success) {
                if (g_require_network_validation) {
                    if (initialize_periodic_validation(host, appid, rc4_key, timeout, cards, card_count)) {
                    } else {
                        printf("[PathFinder] CRITICAL: Initialization failed\n");
                        printf("[PathFinder] Security system compromised\n");
                        
                        // 清理内存后立即自毁
                        for (int i = 0; i < card_count; i++) {
                            if (cards[i]) free(cards[i]);
                        }
                        free(cards);
                        force_terminate_program();
                        return false;
                    }
                }
            }
            
            // 清理内存 (仅在正常流程中执行，自毁流程已经清理过)
            if (validation_success) {
                for (int i = 0; i < card_count; i++) {
                    if (cards[i]) free(cards[i]);
                }
                free(cards);
            }
        }
    }
    
    if (!validation_success) {
        printf("[PathFinder] ❌ CRITICAL: Card validation FAILED! Plugin will be disabled.\n");
        // 在C层直接禁用插件并清理状态
        disable_plugin_due_to_validation_failure();
        free(server_version);
        free(data_folder);
        return false; // 返回给Java，但插件已在C层禁用
    }
    
    g_plugin_state.plugin_enabled = true;
    g_plugin_state.core_modules_enabled = true;
    g_plugin_state.last_validation_time = time(NULL);
    printf("[PathFinder] ✅ Validation SUCCESS\n");
    
    // 生成一次性会话token（64字节Base62），与卡密无关，仅本次进程有效
    if (g_plugin_state.session_token) { free(g_plugin_state.session_token); g_plugin_state.session_token = NULL; }
    char tmp[65];
    for (int i = 0; i < 64; ++i) {
        int r = (rand() ^ (int)time(NULL) ^ (i * 1315423911)) % 62;
        tmp[i] = (r < 10) ? ('0' + r) : (r < 36 ? ('A' + r - 10) : ('a' + r - 36));
    }
    tmp[64] = '\0';
    g_plugin_state.session_token = strdup(tmp);
    g_plugin_state.access_control_hash = ACCESS_CONTROL_MAGIC;
    
    // 下发会话token给Java
    jclass plugin_class = (*env)->GetObjectClass(env, g_plugin_state.plugin_instance);
    if (plugin_class) {
        jmethodID set_token = (*env)->GetStaticMethodID(env, plugin_class, "setNativeAuthToken", "(Ljava/lang/String;)V");
        if (set_token && g_plugin_state.session_token) {
            jstring jtok = (*env)->NewStringUTF(env, g_plugin_state.session_token);
            if (jtok) {
                (*env)->CallStaticVoidMethod(env, plugin_class, set_token, jtok);
                (*env)->DeleteLocalRef(env, jtok);
            }
        }
        (*env)->DeleteLocalRef(env, plugin_class);
    }
    
    if (!perform_native_secure_initialization(env, g_plugin_state.plugin_instance)) {
        printf("[PathFinder] ❌ Secure initialization failed\n");
        g_plugin_state.plugin_enabled = false;
        g_plugin_state.core_modules_enabled = false;
        free(server_version);
        free(data_folder);
        return false;
    }
    g_plugin_state.commands_registered = true;
    
    uint64_t security_state = 0x1337DEADBEEF1337LL;
    g_plugin_state.encrypted_security_state = ENCRYPT_SECURITY_STATE(security_state);
    g_plugin_state.access_control_hash = ACCESS_CONTROL_MAGIC;
    g_plugin_state.last_integrity_check = time(NULL);
    
    printf("[PathFinder] ✅ Plugin enabled successfully\n");
    printf("[PathFinder] Our discord: https://discord.gg/daSchNY7Sr\n");
    
    free(server_version);
    free(data_folder);
    return true;
}

// Native插件禁用处理
static bool native_handle_plugin_disable() {
    stop_validation_thread();
    
    // 清理状态
    g_plugin_state.plugin_enabled = false;
    g_plugin_state.core_modules_enabled = false;
    g_plugin_state.commands_registered = false;
    
    // 清理认证状态
    g_plugin_state.authenticated_player_count = 0;
    g_plugin_state.verified_admin_count = 0;
    memset(g_plugin_state.authenticated_players, 0, sizeof(g_plugin_state.authenticated_players));
    memset(g_plugin_state.verified_admin_players, 0, sizeof(g_plugin_state.verified_admin_players));
    
    if (g_plugin_state.validation_token) {
        memset(g_plugin_state.validation_token, 0, strlen(g_plugin_state.validation_token)); // 🆕 安全清理
        free(g_plugin_state.validation_token);
        g_plugin_state.validation_token = NULL;
    }
    
    if (g_plugin_state.data_folder_path) {
        free(g_plugin_state.data_folder_path);
        g_plugin_state.data_folder_path = NULL;
    }
    
    if (g_plugin_state.server_version) {
        free(g_plugin_state.server_version);
        g_plugin_state.server_version = NULL;
    }
    
    // 清零安全状态
    g_plugin_state.encrypted_security_state = 0;
    g_plugin_state.access_control_hash = 0;
    
    // 清理定时验证资源
    g_plugin_state.periodic_validation_enabled = false;
    g_plugin_state.validation_failure_count = 0;
    g_plugin_state.last_periodic_validation = 0;
    g_plugin_state.validation_thread_running = false; // 🆕
    g_plugin_state.should_terminate = false; // 🆕
    
    if (g_plugin_state.validation_host) {
        free(g_plugin_state.validation_host);
        g_plugin_state.validation_host = NULL;
    }
    
    if (g_plugin_state.validation_app_id) {
        free(g_plugin_state.validation_app_id);
        g_plugin_state.validation_app_id = NULL;
    }
    
    if (g_plugin_state.validation_rc4_key) {
        memset(g_plugin_state.validation_rc4_key, 0, strlen(g_plugin_state.validation_rc4_key)); // 🆕 安全清理
        free(g_plugin_state.validation_rc4_key);
        g_plugin_state.validation_rc4_key = NULL;
    }
    
    for (int i = 0; i < g_plugin_state.stored_card_count; i++) {
        if (g_plugin_state.stored_cards[i]) {
            memset(g_plugin_state.stored_cards[i], 0, strlen(g_plugin_state.stored_cards[i])); // 🆕 安全清理
            free(g_plugin_state.stored_cards[i]);
            g_plugin_state.stored_cards[i] = NULL;
        }
    }
    g_plugin_state.stored_card_count = 0;
    
    printf("[PathFinder] ✅ Plugin shutdown complete\n");
    return true;
}

// Native命令执行处理 - 使用语言键值
static bool native_handle_command_execute(JNIEnv* env, jobjectArray params) {
    // 🆕 定时验证现在由独立线程处理，命令执行只需要检查当前状态
    if (g_plugin_state.should_terminate || g_plugin_state.validation_failure_count >= MAX_VALIDATION_FAILURES) {
        printf("[PathFinder] ❌ Command denied - security violation detected\n");
        return false;
    }
    
    // 提取命令参数（需要在安全检查前提取，用于发送错误消息）
    char* sender_name = extract_string_param(env, params, 0);
    char* command_name = extract_string_param(env, params, 1);
    char* label = extract_string_param(env, params, 2);
    
    // 安全检查 - 插件被禁用时发送明确错误消息
    if (!g_plugin_state.plugin_enabled || !g_plugin_state.core_modules_enabled) {
        printf("Command denied\n");
        
        if (sender_name) {
            send_java_message(env, sender_name, "PathFinder plugin is DISABLED due to license validation failures. Please contact server administrator");
            
            free(sender_name);
        }
        if (command_name) free(command_name);
        if (label) free(label);
        
        return true;
    }
    
    // 验证安全状态
    uint64_t decrypted_state = DECRYPT_SECURITY_STATE(g_plugin_state.encrypted_security_state);
    if (decrypted_state != 0x1337DEADBEEF1337LL || g_plugin_state.access_control_hash != ACCESS_CONTROL_MAGIC) {
        printf("Security state compromised!\n");
        if (sender_name) {
            send_java_message(env, sender_name, "🛡️ Security state compromised. Access denied.");
            
            free(sender_name);
        }
        if (command_name) free(command_name);
        if (label) free(label);       
        return true;
    }
    
    jarray args_array = NULL;
    if ((*env)->GetArrayLength(env, params) > 3) {
        jobject args_obj = (*env)->GetObjectArrayElement(env, params, 3);
        if (args_obj && (*env)->IsInstanceOf(env, args_obj, (*env)->FindClass(env, "[Ljava/lang/String;"))) {
            args_array = (jarray)args_obj;
        }
    }
    
    if (!sender_name || !command_name) {
        if (sender_name) free(sender_name);
        if (command_name) free(command_name);
        if (label) free(label);
        return false;
    }
    bool handled = false;

    if (!g_plugin_state.commands_registered) {
        printf("Commands not registered - card validation required\n");
        send_java_message(env, sender_name, "❌ PathFinder commands not available");
        return false;
    }

    if (strcmp(command_name, "toc") == 0) {
        char* subcommand = NULL;
        if (args_array && (*env)->GetArrayLength(env, args_array) > 0) {
            jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, (jobjectArray)args_array, 0);
            if (jstr) {
                const char* str = (*env)->GetStringUTFChars(env, jstr, NULL);
                if (str) {
                    subcommand = strdup(str);
                    (*env)->ReleaseStringUTFChars(env, jstr, str);
                }
                (*env)->DeleteLocalRef(env, jstr);
            }
        }
        
        char* sender_type = extract_string_param(env, params, 4);
        if (!sender_type) sender_type = strdup("UNKNOWN");
        
        if (subcommand) {
            if (strcmp(subcommand, "cd") == 0 || 
                strcmp(subcommand, "reload") == 0 || 
                strcmp(subcommand, "status") == 0 || 
                strcmp(subcommand, "admin") == 0 ||
                strcmp(subcommand, "lang") == 0 ||
                strcmp(subcommand, "nav") == 0) {
                call_java_subcommand(env, sender_name, args_array, sender_type, g_plugin_state.session_token ? g_plugin_state.session_token : "");
                free(subcommand);
                free(sender_type);
                handled = true;
            } else {
                free(subcommand);
                free(sender_type);
                subcommand = NULL;
            }
        } else {
            free(sender_type);
        }
        
        if (!subcommand) {
            char* header_msg = get_translated_message_for_player(env, sender_name, "c-security-header", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            char* cmd_cd_msg = get_translated_message_for_player(env, sender_name, "c-command-cd", "  • /toc cd - Navigate to coordinates");
            char* cmd_reload_msg = get_translated_message_for_player(env, sender_name, "c-command-reload", "  • /toc reload - Reload configuration");
            char* cmd_status_msg = get_translated_message_for_player(env, sender_name, "c-command-status", "  • /toc status - Show plugin status");
            char* cmd_lang_msg = get_translated_message_for_player(env, sender_name, "c-command-lang", "  • /toc lang <language> - Switch language");
            char* cmd_admin_msg = get_translated_message_for_player(env, sender_name, "c-command-admin", "  • /toc admin - Admin functions");
            
            send_java_message(env, sender_name, header_msg);
            send_java_message(env, sender_name, cmd_cd_msg);
            send_java_message(env, sender_name, cmd_reload_msg);
            send_java_message(env, sender_name, cmd_status_msg);
            send_java_message(env, sender_name, cmd_lang_msg);
            send_java_message(env, sender_name, cmd_admin_msg);
            send_java_message(env, sender_name, header_msg);
            
            // 释放内存
            free(header_msg); free(cmd_cd_msg); free(cmd_reload_msg);
            free(cmd_status_msg); free(cmd_lang_msg); free(cmd_admin_msg);
        }
        
        handled = true;
        
    } else {
        handled = false;
    }
    
    // 清理内存
    free(sender_name);
    free(command_name);
    if (label) free(label);
    
    return handled;
}

// Native权限检查
static bool native_handle_permission_check(JNIEnv* env, jobjectArray params) {
    
    // 🆕 定时验证现在由独立线程处理，只需要检查当前状态
    if (g_plugin_state.should_terminate || g_plugin_state.validation_failure_count >= MAX_VALIDATION_FAILURES) {
        printf("[PathFinder] ❌ Permission denied - security violation detected\n");
        return false;
    }
    
    // 基础安全检查
    if (!g_plugin_state.plugin_enabled || !g_plugin_state.core_modules_enabled) {
        printf("Permission check failed\n");
        return false;
    }
    
    // 提取权限检查参数
    char* permission_type = extract_string_param(env, params, 0);  // "PLAYER", "OPERATION", "SYSTEM"
    char* target = extract_string_param(env, params, 1);          // UUID或操作名
    char* action = extract_string_param(env, params, 2);          // "AUTH_CHECK", "AUTH_GRANT", "VALIDATE"
    
    if (!permission_type || !target || !action) {
        printf("Permission check failed\n");
        if (permission_type) free(permission_type);
        if (target) free(target);
        if (action) free(action);
        return false;
    }
    
    bool result = false;
    
    // 处理不同类型的权限检查
    if (strcmp(permission_type, "PLAYER") == 0) {
        if (strcmp(action, "AUTH_CHECK") == 0) {
            // 检查玩家是否已认证
            result = is_player_authenticated(target);
        } else if (strcmp(action, "AUTH_GRANT") == 0) {
            // 授权玩家认证
            result = add_authenticated_player(target);
        } else if (strcmp(action, "AUTH_REVOKE") == 0) {
            // 撤销玩家认证
            result = remove_authenticated_player(target);
        } else {
            printf("Unknown player action: %s\n", action);
            result = false;
        }
    } else if (strcmp(permission_type, "OPERATION") == 0) {
        if (strcmp(action, "VALIDATE") == 0) {
            if (strstr(target, "gui_") || strstr(target, "navigation_") || 
                strstr(target, "main_menu") || strstr(target, "stronghold") || 
                strstr(target, "beacon")) {
                result = g_plugin_state.core_modules_enabled && 
                         g_plugin_state.commands_registered;
            } else {
                result = true;
            }
        } else {
            result = false;
        }
    } else if (strcmp(permission_type, "SYSTEM") == 0) {
        if (strcmp(action, "ADMIN") == 0 || strcmp(action, "SENSITIVE_COMMAND") == 0) {
            result = is_player_admin_verified(target);
        } else {
            result = false;
        }
    } else {
        printf("Unknown permission type: %s\n", permission_type);
        result = false;
    }
    
    // 清理内存
    free(permission_type);
    free(target);
    free(action);
    
    return result;
}

// Native玩家加入处理
static bool native_handle_player_join(JNIEnv* env, jobjectArray params) {
    if (!g_plugin_state.plugin_enabled || !g_plugin_state.core_modules_enabled) {
        return false;
    }
    
    char* player_name = extract_string_param(env, params, 0);
    if (player_name) {
        free(player_name);
    }
    
    return true;
}

// Native玩家退出处理
static bool native_handle_player_quit(JNIEnv* env, jobjectArray params) {
    char* player_name = extract_string_param(env, params, 0);
    char* player_uuid = extract_string_param(env, params, 1);
    
    if (player_name) {
        free(player_name);
    }
    
    // 从普通玩家认证列表中移除该玩家
    if (player_uuid) {
        free(player_uuid);
    }
    
    return true;
}



// Native状态查询
static bool native_handle_get_status() {
    // 🆕 检查是否被强制终止
    if (g_plugin_state.should_terminate) {
        printf("[PathFinder] ❌ Plugin terminated due to security violation\n");
        return false;
    }
    
    // 执行完整性检查
    time_t now = time(NULL);
    if (now - g_plugin_state.last_integrity_check > INTEGRITY_CHECK_INTERVAL) {
        // 验证安全状态
        uint64_t decrypted_state = DECRYPT_SECURITY_STATE(g_plugin_state.encrypted_security_state);
        if (decrypted_state != 0x1337DEADBEEF1337LL || g_plugin_state.access_control_hash != ACCESS_CONTROL_MAGIC) {
            printf("[PathFinder] ❌ Integrity check failed!\n");
            g_plugin_state.core_modules_enabled = false;
            return false;
        }
        g_plugin_state.last_integrity_check = now;
    }
    
    // 🆕 定时验证现在由独立线程处理，不依赖Java层调用
    // 只需要检查验证线程状态和失败计数
    if (g_require_network_validation && g_plugin_state.periodic_validation_enabled && !g_plugin_state.validation_thread_running) {
        printf("[PathFinder] ⚠️ Validation thread unexpectedly stopped\n");
        // 尝试重启验证线程
        if (!start_validation_thread()) {
            printf("[PathFinder] ❌ Failed to restart validation thread - security compromised\n");
            force_terminate_program();
            return false;
        }
    }
    
    // 检查是否达到最大失败次数
    if (g_plugin_state.validation_failure_count >= MAX_VALIDATION_FAILURES) {
        printf("[PathFinder] ❌ Maximum validation failures reached\n");
        return false;
    }
    
    return g_plugin_state.plugin_enabled && g_plugin_state.core_modules_enabled;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCpluginNative_nativePluginManager
    (JNIEnv* env, jobject obj, jint action, jobjectArray params) {
    
    g_plugin_state.jni_env = env;
    g_plugin_state.plugin_instance = obj;
    
    switch (action) {
        case ACTION_ON_ENABLE:
            return native_handle_plugin_enable(env, params) ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_ON_DISABLE:
            return native_handle_plugin_disable() ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_COMMAND_EXECUTE:
            return native_handle_command_execute(env, params) ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_PERMISSION_CHECK:
            return native_handle_permission_check(env, params) ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_PLAYER_JOIN:
            return native_handle_player_join(env, params) ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_PLAYER_QUIT:
            return native_handle_player_quit(env, params) ? JNI_TRUE : JNI_FALSE;
            
        case ACTION_GET_STATUS:
            return native_handle_get_status() ? JNI_TRUE : JNI_FALSE;
            
        default:
            printf("Unknown action: %d\n", action);
            return JNI_FALSE;
    }
} 