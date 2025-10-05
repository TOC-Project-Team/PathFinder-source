#include "../include/startup_validator.h"
#include "../include/machine_code.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <stdbool.h>
#include "../include/native_config.h"

#define COLOR_RED     "\033[91m"
#define COLOR_YELLOW  "\033[93m"
#define COLOR_GREEN   "\033[92m"
#define COLOR_BLUE    "\033[94m"
#define COLOR_RESET   "\033[0m"
// Windows
#ifdef _WIN32
    #include <windows.h>
    #include <tlhelp32.h>
    #include <psapi.h>
#include "../src/windows_compat.h"
#else
// Linux/Unix
    #include <unistd.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/utsname.h>
#include <signal.h>
#endif
#ifndef NO_CURL
#include <curl/curl.h>
#endif
#ifndef NO_OPENSSL
#include <openssl/md5.h>
#include <openssl/evp.h>
#endif

static bool g_debug_detected = false;
static bool g_integrity_verified = false;
static bool g_core_modules_enabled = false;
static volatile uint32_t g_anti_tamper_seed = 0;

#define MEMORY_GUARD_MAGIC_1    0xDEADBEEF
#define MEMORY_GUARD_MAGIC_2    0xCAFEBABE
#define MEMORY_GUARD_MAGIC_3    0xBAADF00D

static ValidationResult* g_validation_results[16] = {0};
static bool g_result_slots_used[16] = {0};

bool validate_with_wig_c(const char* host, const char* app_id, const char* card, 
                         const char* rc4_key, int timeout);

static bool g_plugin_enabled = false;
static bool g_commands_registered = false;
static char* g_data_folder_path = NULL;
static char* g_validation_token = NULL;
static long g_last_validation_time = 0;

// Global toggle: require network validation to start plugin
// If set to false, all network validation will be skipped
bool g_require_network_validation = (REQUIRE_NETWORK_VALIDATION ? true : false);

#ifndef WINDOWS_PLATFORM
struct http_response {
    char* data;
    size_t size;
};
#endif

// JNI辅助函数
static char* jstring_to_cstring(JNIEnv* env, jstring jstr);
static bool check_server_version_compatibility(const char* server_version);

// C层安全初始化函数声明（在native_plugin_manager.c中实现）
extern bool perform_native_secure_initialization(JNIEnv* env, jobject plugin_instance);

// 初始化反破解保护（简化版本 - VMP保护已启用）
bool initialize_anti_crack_protection() {
    // VMP保护已启用，无需额外的反破解检测
    g_debug_detected = false;
    g_integrity_verified = true;
    
    return true;
}

bool detect_debugging_environment(void) {
    return false;
            }

bool verify_code_integrity(void) {
    return true;
}

// 生成反篡改种子
uint64_t generate_anti_tamper_seed(void) {
    srand(time(NULL));
    uint64_t seed = 0;
    
    // 基于多种系统信息生成种子
    seed ^= (uint64_t)getpid();
    seed ^= (uint64_t)time(NULL);
    seed ^= (uint64_t)rand();
    
    // 混淆种子
    for (int i = 0; i < 8; i++) {
        seed = (seed << 8) | (seed >> 56);
        seed ^= 0xDEADBEEFCAFEBABE;
    }
    
    return seed;
}

// 字符串混淆
void obfuscate_string(char* str, int len) {
    for (int i = 0; i < len; i++) {
        str[i] ^= (char)(g_anti_tamper_seed >> (8 * (i % 8)));
    }
}

// HTTP响应回调
static size_t http_write_callback(void* contents, size_t size, size_t nmemb, struct http_response* response) {
    size_t total_size = size * nmemb;
    response->data = realloc(response->data, response->size + total_size + 1);
    if (response->data) {
        memcpy(&(response->data[response->size]), contents, total_size);
        response->size += total_size;
        response->data[response->size] = 0;
    }
    return total_size;
}

// MD5哈希函数（Windows使用原生CryptoAPI，Linux使用OpenSSL）
char* md5_hash(const char* input) {
    if (!input) return NULL;
    
#ifdef WINDOWS_PLATFORM
    // Windows平台使用原生CryptoAPI
    char* result = malloc(33);
    if (windows_md5_hash(input, result)) {
        return result;
    } else {
        free(result);
        return NULL;
    }
#else
    // Linux平台使用OpenSSL
    MD5_CTX ctx;
    MD5_Init(&ctx);
    MD5_Update(&ctx, input, strlen(input));
    
    unsigned char hash[MD5_DIGEST_LENGTH];
    MD5_Final(hash, &ctx);
    
    char* result = malloc(33);
    for (int i = 0; i < MD5_DIGEST_LENGTH; i++) {
        sprintf(result + i * 2, "%02x", hash[i]);
    }
    result[32] = '\0';
    
    return result;
#endif
}

// RC4加密/解密（Windows使用原生CryptoAPI，Linux使用自实现）
char* rc4_encrypt_decrypt(const char* data, const char* key, bool is_hex_input) {
    if (!data || !key) {
        return NULL;
    }
    
#ifdef WINDOWS_PLATFORM
    // Windows平台使用原生CryptoAPI
    char* result = NULL;
    if (windows_rc4_encrypt_decrypt(data, key, is_hex_input, &result)) {
        return result;
    } else {
        return NULL;
    }
#else
    // Linux平台使用自实现的RC4
    int key_len = strlen(key);
    int data_len = strlen(data);
    
    // 初始化S盒
    unsigned char S[256];
    for (int i = 0; i < 256; i++) {
        S[i] = i;
    }
    
    int j = 0;
    for (int i = 0; i < 256; i++) {
        j = (j + S[i] + key[i % key_len]) % 256;
        unsigned char temp = S[i];
        S[i] = S[j];
        S[j] = temp;
    }
    
    // 处理输入数据
    unsigned char* input_data;
    int input_len;
    
    if (is_hex_input) {
        // 十六进制转字节
        input_len = data_len / 2;
        input_data = malloc(input_len);
        for (int i = 0; i < input_len; i++) {
            sscanf(data + i * 2, "%2hhx", &input_data[i]);
        }
    } else {
        input_len = data_len;
        input_data = (unsigned char*)strdup(data);
    }
    
    // RC4加密/解密
    unsigned char* output = malloc(input_len);
    int i = 0;
    j = 0;
    for (int k = 0; k < input_len; k++) {
        i = (i + 1) % 256;
        j = (j + S[i]) % 256;
        unsigned char temp = S[i];
        S[i] = S[j];
        S[j] = temp;
        unsigned char K = S[(S[i] + S[j]) % 256];
        output[k] = input_data[k] ^ K;
    }
    
    char* result;
    if (is_hex_input) {
        // 输出为字符串
        result = malloc(input_len + 1);
        memcpy(result, output, input_len);
        result[input_len] = '\0';
    } else {
        // 输出为十六进制
        result = malloc(input_len * 2 + 1);
        for (int k = 0; k < input_len; k++) {
            sprintf(result + k * 2, "%02x", output[k]);
        }
        result[input_len * 2] = '\0';
    }
    
    free(input_data);
    free(output);
    
    return result;
#endif
}

// 生成机械码
char* generate_machine_code(void) {
    char* machine_code = get_machine_code_safe();
    if (!machine_code) {
        return strdup("emergency_machine_code");
    }
    
    char raw_machine_code[2048];
    snprintf(raw_machine_code, sizeof(raw_machine_code), 
             "%s_machine_code_salt_2024", machine_code);
    
    char* result = md5_hash(raw_machine_code);
    free(machine_code);
    
    return result;
}

// 获取或创建IMEI
char* get_or_create_imei_internal(const char* app_id) {
    if (!app_id) return NULL;
    
    char* machine_code = get_machine_code_safe();
    if (!machine_code) {
        return NULL;
    }
    
    char imei_data[512];
    snprintf(imei_data, sizeof(imei_data), "%s_machine_code_salt_2024", machine_code);
    free(machine_code);
    
    char* imei = md5_hash(imei_data);
    if (!imei) {
        return NULL;
    }
    
    char filename[256];
    snprintf(filename, sizeof(filename), "wig%s.imei", app_id);
    
    FILE* fp = fopen(filename, "w");
    if (fp) {
        fprintf(fp, "%s\n", imei);
        fclose(fp);
    }
    
    return imei;
}

// 验证IMEI完整性（允许首次创建）
bool verify_imei_integrity(const char* app_id) {
    if (!app_id) return false;
    
    char filename[256];
    snprintf(filename, sizeof(filename), "wig%s.imei", app_id);
    
    // 先尝试读取存储的IMEI
    FILE* fp = fopen(filename, "r");
    if (!fp) {
        // 文件不存在，可能是首次运行，尝试创建IMEI
        char* new_imei = get_or_create_imei_internal(app_id);
        if (new_imei) {
            free(new_imei);
            return true; // 首次创建视为验证通过
        } else {
            return false;
        }
    }
    
    char stored_imei[64];
    if (!fgets(stored_imei, sizeof(stored_imei), fp)) {
        fclose(fp);
        return false;
    }
    fclose(fp);
    
    // 移除换行符
    stored_imei[strcspn(stored_imei, "\n")] = 0;
    
    // 重新计算IMEI
    char* machine_code = generate_machine_code();
    if (!machine_code) {
        return false;
    }
    
    char imei_data[512];
    snprintf(imei_data, sizeof(imei_data), "%s_machine_code_salt_2024", machine_code);
    
    char* calculated_imei = md5_hash(imei_data);
    free(machine_code);
    
    if (!calculated_imei) {
        return false;
    }
    
    bool result = (strcmp(stored_imei, calculated_imei) == 0);
    
    if (!result) {
        // 自动更新IMEI文件为新计算的值
        char imei_filename[256];
        snprintf(imei_filename, sizeof(imei_filename), "wig%s.imei", app_id);
        
        FILE* update_fp = fopen(imei_filename, "w");
        if (update_fp) {
            fprintf(update_fp, "%s\n", calculated_imei);
            fclose(update_fp);
            result = true; // 更新成功后认为验证通过
        }
    }
    
    free(calculated_imei);
    return result;
}

// 网络验证实现（完全按照Java validateWithWIG逻辑）
bool validate_with_wig_c(const char* host, const char* app_id, const char* card, 
                         const char* rc4_key, int timeout) {
    // Short-circuit: skip network validation entirely when disabled
    if (!g_require_network_validation) {
        return true;
    }
    if (!host || !app_id || !card || !rc4_key) {
        printf(COLOR_RED "[ERROR] Parameter validation failed - one or more parameters are NULL" COLOR_RESET "\n");
        return false;
    }
    
#ifdef WINDOWS_PLATFORM
    bool success = false;
    struct http_response response = {0};
    long now = time(NULL);
    char url[512];
    snprintf(url, sizeof(url), "http://%s/api/login/%ld/%s/%s", host, now, app_id, card);
    char* imei = get_or_create_imei_internal(app_id);
    if (!imei) {
        printf(COLOR_RED "[ERROR] Failed to generate IMEI" COLOR_RESET "\n");
        return false;
    }
    char post_data[512];
    snprintf(post_data, sizeof(post_data), "imei=%s", imei);
    if (windows_http_request(url, post_data, &response)) {  
        if (response.data && strlen(response.data) > 0) {
            char* json_text = NULL;
            if (windows_rc4_encrypt_decrypt(response.data, rc4_key, true, &json_text)) {
                if (json_text && strlen(json_text) > 0 && json_text[0] == '{') {            
                    char* code_pos = strstr(json_text, "\"code\":");
                    char* msg_pos = strstr(json_text, "\"msg\":");
                    if (code_pos) {
                        char code[16] = {0};
                        if (sscanf(code_pos, "\"code\":\"%15[^\"]\"", code) == 1 ||
                            sscanf(code_pos, "\"code\":%15[^,}]", code) == 1) {                            
                            if (strcmp(code, "1") == 0 || strcmp(code, "20000") == 0) {
                                success = true;
                            } else {
                                if (msg_pos) {
                                    char msg[256] = {0};
                                    if (sscanf(msg_pos, "\"msg\":\"%255[^\"]\"", msg) == 1) {
                                        printf(COLOR_RED "[PathFinder] ERROR: %s" COLOR_RESET "\n", msg);
                                    }
                                }
                            }
                        }
                    } else {
                        printf(COLOR_RED "[ERROR] Invalid JSON response - no 'code' field" COLOR_RESET "\n");
                    }                   
                    free(json_text);
                } else {
                    printf(COLOR_RED "[ERROR] RC4 decryption failed or invalid JSON" COLOR_RESET "\n");
                    if (json_text) free(json_text);
                }
            } else {
                printf(COLOR_RED "[ERROR] RC4 decryption failed" COLOR_RESET "\n");
            }
        } else {
            printf(COLOR_RED "[ERROR] Empty HTTP response" COLOR_RESET "\n");
        }
    } else {
        printf(COLOR_RED "[ERROR] HTTP request failed" COLOR_RESET "\n");
    }
    
    if (response.data) {
        free(response.data);
    }
    free(imei);
    
    return success;
    
#else
    CURL* curl = curl_easy_init();
    if (!curl) {
        return false;
    }
    
    bool success = false;
    struct http_response response = {0};
    long now = time(NULL);
    char url[512];
    snprintf(url, sizeof(url), "http://%s/api/login/%ld/%s/%s", host, now, app_id, card);
    char* imei = get_or_create_imei_internal(app_id);
    if (!imei) {
        curl_easy_cleanup(curl);
        return false;
    }
    char post_data[512];
    snprintf(post_data, sizeof(post_data), "imei=%s", imei);
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, post_data);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, timeout);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, timeout);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, http_write_callback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    CURLcode res = curl_easy_perform(curl);
    long http_code = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);
    if (res != CURLE_OK) {
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }
    
    if (http_code != 200) {
        printf(COLOR_RED "[ERROR] HTTP error: %ld" COLOR_RESET "\n", http_code);
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }
    
    if (!response.data || strlen(response.data) == 0) {
        printf(COLOR_RED "[ERROR] Empty response data" COLOR_RESET "\n");
        curl_easy_cleanup(curl);
        free(imei);
        return false;
    }
    
    char* body = response.data;   
    char* json_text = rc4_encrypt_decrypt(body, rc4_key, true);
    
    if (!json_text) {
        printf(COLOR_RED "[ERROR] RC4 decryption failed - returned NULL" COLOR_RESET "\n");
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }
    
    if (strlen(json_text) == 0) {
        printf(COLOR_RED "[ERROR] RC4 decryption returned empty string" COLOR_RESET "\n");
        free(json_text);
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }
    
    if (json_text[0] != '{') {
        printf(COLOR_RED "[ERROR] Decrypted text is not valid JSON (doesn't start with '{')" COLOR_RESET "\n");
        printf(COLOR_RED "[ERROR] First 50 chars: %.50s" COLOR_RESET "\n", json_text);
        free(json_text);
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }
    
    char* code_pos = strstr(json_text, "\"code\":");
    char* msg_pos = strstr(json_text, "\"msg\":");
    char* api_time_pos = strstr(json_text, "\"api_time\":");
    char* user_time_pos = strstr(json_text, "\"user_time\":");
    char* checktrue_pos = strstr(json_text, "\"checktrue\":");
    char code[16] = {0};
    char api_time_str[32] = {0};
    char user_time_str[32] = {0};
    
    if (!code_pos) {
        printf(COLOR_RED "[ERROR] 'code' field not found in JSON response" COLOR_RESET "\n");
        free(json_text);
        curl_easy_cleanup(curl);
        free(imei);
        if (response.data) free(response.data);
        return false;
    }

    if (sscanf(code_pos, "\"code\":\"%15[^\"]\"", code) == 1 ||
        sscanf(code_pos, "\"code\":%15[^,}]", code) == 1) {
        
        if (strcmp(code, "1") == 0 || strcmp(code, "20000") == 0) {
            success = true;
        } else {
            if (msg_pos) {
                char msg[256] = {0};
                if (sscanf(msg_pos, "\"msg\":\"%255[^\"]\"", msg) == 1) {
                    printf(COLOR_RED "[PathFinder] ERROR: %s" COLOR_RESET "\n", msg);
                }
            }
                free(json_text);
                curl_easy_cleanup(curl);
                free(imei);
                if (response.data) free(response.data);
                return false;
        }
            } else {
        printf(COLOR_RED "[ERROR] Failed to extract 'code' field from JSON" COLOR_RESET "\n");
        printf(COLOR_RED "[ERROR] Code field content: %.50s" COLOR_RESET "\n", code_pos ? code_pos : "NULL");
                free(json_text);
                curl_easy_cleanup(curl);
                free(imei);
                if (response.data) free(response.data);
                return false;
            }
    
    if (success) {
        if (api_time_pos && user_time_pos) {
                if ((sscanf(api_time_pos, "\"api_time\":\"%31[^\"]\"", api_time_str) == 1 ||
                     sscanf(api_time_pos, "\"api_time\":%31[^,}]", api_time_str) == 1) &&
                    (sscanf(user_time_pos, "\"user_time\":\"%31[^\"]\"", user_time_str) == 1 ||
                     sscanf(user_time_pos, "\"user_time\":%31[^,}]", user_time_str) == 1)) {
                    
                    long api_time = strtol(api_time_str, NULL, 10);
                    long now_sec = time(NULL);
                    long skew = labs(now_sec - api_time);
                    if (api_time <= 0 || skew > 30) {
                        if (json_text) free(json_text);
                        curl_easy_cleanup(curl);
                        return false;
                    }
                }
            } else {
                success = true;
            }

            if (checktrue_pos) {
                char checktrue_enc[256] = {0};
                if (sscanf(checktrue_pos, "\"checktrue\":\"%255[^\"]\"", checktrue_enc) == 1) {
                    char* checktrue_dec = rc4_encrypt_decrypt(checktrue_enc, rc4_key, true);
                    if (checktrue_dec) {
                        char expected_checktrue[512];
                        snprintf(expected_checktrue, sizeof(expected_checktrue), 
                                "%s%s%s%s%s", imei, user_time_str, api_time_str, app_id, code);
                        
                        if (strcmp(expected_checktrue, checktrue_dec) == 0) {
                            success = true;
                        } else {
                            if (json_text) free(json_text);
                            free(checktrue_dec);
                            curl_easy_cleanup(curl);
                            return false;
                        }
                        free(checktrue_dec);
                    } else {
                        if (json_text) free(json_text);
                        curl_easy_cleanup(curl);
                        return false;
                    }
                }
            } else {
                success = true;
            }
    }
    
    free(json_text);
    curl_easy_cleanup(curl);
    free(imei);
    if (response.data) free(response.data);
    return success;
#endif
}

char* validate_with_wig_internal(const char* host, const char* app_id, const char* card, 
                                const char* rc4_key, const char* expected_code, int timeout) {
    (void)expected_code;
    bool success = validate_with_wig_c(host, app_id, card, rc4_key, timeout);
    return success ? strdup("SUCCESS") : NULL;
}

bool perform_startup_validation_internal(const char* app_id, char** cards, int card_count, 
                                       const char* host, const char* rc4_key, int timeout) {
    if (!app_id || !cards || card_count <= 0 || !host || !rc4_key) {
        // Allow startup when network validation is disabled
        if (!g_require_network_validation) {
            g_core_modules_enabled = true;
            g_last_validation_time = time(NULL);
            if (g_validation_token) {
                free(g_validation_token);
                g_validation_token = NULL;
            }
            char token_data[256];
            snprintf(token_data, sizeof(token_data), "VALIDATED_%s_%ld", app_id, g_last_validation_time);
            char* token_hash = md5_hash(token_data);
            if (token_hash) {
                g_validation_token = strdup(token_hash);
                free(token_hash);
            } else {
                g_validation_token = strdup("DEFAULT_TOKEN");
            }
            return true;
        }
        return false;
    }
    
    initialize_anti_crack_protection();
    
    if (!verify_imei_integrity(app_id)) {
        return false;
    }
    
    for (int i = 0; i < card_count; i++) {
        if (!cards[i] || strlen(cards[i]) == 0) continue;
        
        if (validate_with_wig_c(host, app_id, cards[i], rc4_key, timeout)) {
            g_core_modules_enabled = true;
            g_last_validation_time = time(NULL);
            
            if (g_validation_token) {
                free(g_validation_token);
            }
            char token_data[256];
            snprintf(token_data, sizeof(token_data), "VALIDATED_%s_%ld", 
                    app_id, g_last_validation_time);
            
            char* token_hash = md5_hash(token_data);
            if (token_hash) {
                g_validation_token = strdup(token_hash);
                free(token_hash);
            } else {
                g_validation_token = strdup("DEFAULT_TOKEN");
            }
            
            return true;
        }
    }
    
    return false;
}

ValidationResult* create_validation_result(int result_code, const char* token) {
    
    int slot = -1;
    for (int i = 0; i < 16; i++) {
        if (!g_result_slots_used[i]) {
            slot = i;
            break;
        }
    }
    
    if (slot == -1) {
        return NULL;
    }
    
    ValidationResult* result = malloc(sizeof(ValidationResult));
    if (!result) return NULL;
    
    result->magic_header = MEMORY_GUARD_MAGIC_1;
    result->magic_footer = MEMORY_GUARD_MAGIC_3;
    result->validation_result = OBFUSCATE((uint32_t)result_code);
    result->timestamp = (uint32_t)time(NULL);
    
    if (token) {
        strncpy(result->validation_token, token, sizeof(result->validation_token) - 1);
        result->validation_token[sizeof(result->validation_token) - 1] = '\0';
    } else {
        result->validation_token[0] = '\0';
    }
    
    uint32_t checksum = result->magic_header ^ result->validation_result ^ 
                       result->timestamp ^ result->magic_footer;
    
    for (size_t i = 0; i < strlen(result->validation_token); i++) {
        checksum ^= (uint32_t)result->validation_token[i];
    }
    
    result->checksum = OBFUSCATE(checksum);
    
    g_validation_results[slot] = result;
    g_result_slots_used[slot] = true;
    
    return result;
}

bool verify_validation_result(ValidationResult* result) {
    if (!result) return false;
    
    bool found_in_pool = false;
    for (int i = 0; i < 16; i++) {
        if (g_validation_results[i] == result && g_result_slots_used[i]) {
            found_in_pool = true;
            break;
        }
    }
    
    if (!found_in_pool) {
        return false;
    }
    
    if (result->magic_header != MEMORY_GUARD_MAGIC_1 || 
        result->magic_footer != MEMORY_GUARD_MAGIC_3) {
        return false;
    }
    
    uint32_t expected_checksum = result->magic_header ^ result->validation_result ^ 
                                result->timestamp ^ result->magic_footer;
    
    for (size_t i = 0; i < strlen(result->validation_token); i++) {
        expected_checksum ^= (uint32_t)result->validation_token[i];
    }
    
    expected_checksum = OBFUSCATE(expected_checksum);
    
    if (result->checksum != expected_checksum) {
        return false;
    }
    
    uint32_t current_time = (uint32_t)time(NULL);
    if (current_time - result->timestamp > 300) {
        return false;
    }
    
    return true;
}

void destroy_validation_result(ValidationResult* result) {
    if (!result) return;
    
    for (int i = 0; i < 16; i++) {
        if (g_validation_results[i] == result) {
            g_validation_results[i] = NULL;
            g_result_slots_used[i] = false;
            break;
        }
    }
    
    memset(result, 0, sizeof(ValidationResult));
    free(result);
    
    return;
}

JNIEXPORT jint JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeStartupValidation
    (JNIEnv* env, jobject obj, jstring app_id, jobjectArray cards_array, 
     jstring host, jstring rc4_key, jint timeout) {
    (void)obj;
    
    const char* c_app_id = (*env)->GetStringUTFChars(env, app_id, NULL);
    const char* c_host = (*env)->GetStringUTFChars(env, host, NULL);
    const char* c_rc4_key = (*env)->GetStringUTFChars(env, rc4_key, NULL);
    
    int card_count = (*env)->GetArrayLength(env, cards_array);
    char** cards = malloc(card_count * sizeof(char*));
    
    for (int i = 0; i < card_count; i++) {
        jstring card_str = (jstring)(*env)->GetObjectArrayElement(env, cards_array, i);
        cards[i] = (char*)(*env)->GetStringUTFChars(env, card_str, NULL);
    }
    
    bool success = perform_startup_validation_internal(c_app_id, cards, card_count, 
                                                     c_host, c_rc4_key, timeout);
    
    (*env)->ReleaseStringUTFChars(env, app_id, c_app_id);
    (*env)->ReleaseStringUTFChars(env, host, c_host);
    (*env)->ReleaseStringUTFChars(env, rc4_key, c_rc4_key);
    
    for (int i = 0; i < card_count; i++) {
        jstring card_str = (jstring)(*env)->GetObjectArrayElement(env, cards_array, i);
        (*env)->ReleaseStringUTFChars(env, card_str, cards[i]);
    }
    free(cards);
    
    return success ? VALIDATION_SUCCESS : VALIDATION_FAILED;
}

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetOrCreateImei
    (JNIEnv* env, jobject obj, jstring app_id) {
    (void)obj;
    
    const char* c_app_id = (*env)->GetStringUTFChars(env, app_id, NULL);
    char* imei = get_or_create_imei_internal(c_app_id);
    (*env)->ReleaseStringUTFChars(env, app_id, c_app_id);
    
    if (imei) {
        jstring result = (*env)->NewStringUTF(env, imei);
        free(imei);
        return result;
    }
    
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeVerifyIntegrity
    (JNIEnv* env, jobject obj, jstring app_id) {
    (void)obj;
    
    const char* c_app_id = (*env)->GetStringUTFChars(env, app_id, NULL);
    bool result = verify_imei_integrity(c_app_id);
    (*env)->ReleaseStringUTFChars(env, app_id, c_app_id);
    
    return result;
}

JNIEXPORT jlong JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeStartupValidationAdvanced
    (JNIEnv* env, jobject obj, jstring app_id, jobjectArray cards_array, 
     jstring host, jstring rc4_key, jint timeout) {
    (void)obj;
    
    const char* c_app_id = (*env)->GetStringUTFChars(env, app_id, NULL);
    const char* c_host = (*env)->GetStringUTFChars(env, host, NULL);
    const char* c_rc4_key = (*env)->GetStringUTFChars(env, rc4_key, NULL);
    
    int card_count = (*env)->GetArrayLength(env, cards_array);
    char** cards = malloc(card_count * sizeof(char*));
    
    for (int i = 0; i < card_count; i++) {
        jstring card_str = (jstring)(*env)->GetObjectArrayElement(env, cards_array, i);
        cards[i] = (char*)(*env)->GetStringUTFChars(env, card_str, NULL);
    }
    
    bool success = perform_startup_validation_internal(c_app_id, cards, card_count, 
                                                     c_host, c_rc4_key, timeout);
    
    ValidationResult* result = NULL;
    if (success) {
        char token[64];
        snprintf(token, sizeof(token), "VALID_%s_%ld_%08x", 
                c_app_id, time(NULL), (unsigned int)g_anti_tamper_seed);
        
        result = create_validation_result(ADVANCED_VALIDATION_SUCCESS, token);
    } else {
        result = create_validation_result(ADVANCED_VALIDATION_FAILED, NULL);
    }
    
    (*env)->ReleaseStringUTFChars(env, app_id, c_app_id);
    (*env)->ReleaseStringUTFChars(env, host, c_host);
    (*env)->ReleaseStringUTFChars(env, rc4_key, c_rc4_key);
    
    for (int i = 0; i < card_count; i++) {
        jstring card_str = (jstring)(*env)->GetObjectArrayElement(env, cards_array, i);
        (*env)->ReleaseStringUTFChars(env, card_str, cards[i]);
    }
    free(cards);
    
    return (jlong)result;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeVerifyValidationResult
    (JNIEnv* env, jobject obj, jlong result_ptr) {
    (void)env; (void)obj;
    
    ValidationResult* result = (ValidationResult*)result_ptr;
    
    if (!verify_validation_result(result)) {
        return JNI_FALSE;
    }
    
    uint32_t actual_result = DEOBFUSCATE(result->validation_result);
    bool is_success = (actual_result == (uint32_t)ADVANCED_VALIDATION_SUCCESS);
    
    return is_success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetValidationToken
    (JNIEnv* env, jobject obj, jlong result_ptr) {
    (void)obj;
    
    ValidationResult* result = (ValidationResult*)result_ptr;
    
    if (!verify_validation_result(result)) {
        return NULL;
    }
    
    return (*env)->NewStringUTF(env, result->validation_token);
}

JNIEXPORT void JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeDestroyValidationResult
    (JNIEnv* env, jobject obj, jlong result_ptr) {
    (void)env; (void)obj;
    
    ValidationResult* result = (ValidationResult*)result_ptr;
    destroy_validation_result(result);
}

static char* jstring_to_cstring(JNIEnv* env, jstring jstr) {
    if (!jstr) return NULL;
    
    const char* temp = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (!temp) return NULL;
    
    char* result = strdup(temp);
    (*env)->ReleaseStringUTFChars(env, jstr, temp);
    return result;
}

// 已无调用方，移除 cstring_to_jstring

static bool check_server_version_compatibility(const char* server_version) {
    if (!server_version) return false;
    
    if (strstr(server_version, "1.21") != NULL) {
        return true;
    }
    
    return true;
}

/* removed unused call_java_method_* helpers */

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeOnEnable
    (JNIEnv* env, jobject obj, jstring server_version, jstring plugin_data_folder) {
    
    char* version_str = jstring_to_cstring(env, server_version);
    char* data_folder = jstring_to_cstring(env, plugin_data_folder);
    
    if (!version_str || !data_folder) {
        if (version_str) free(version_str);
        if (data_folder) free(data_folder);
        return JNI_FALSE;
    }
    
    if (g_data_folder_path) free(g_data_folder_path);
    g_data_folder_path = strdup(data_folder);
    
    if (!check_server_version_compatibility(version_str)) {
        free(version_str);
        free(data_folder);
        return JNI_FALSE;
    }
    
    initialize_anti_crack_protection();
    
    const char* host = "www.wigwy.xyz";
    const char* app_id = "31283";
    const char* rc4_key = "5xzdMDzPt5duTf7";
    int timeout = 30;
    
    char config_path[512];
    snprintf(config_path, sizeof(config_path), "%s/validata.yml", data_folder);
    FILE* config_file = fopen(config_path, "r");
    char** cards = NULL;
    int card_count = 0;
    bool validation_success = false;
    
    if (config_file) {
        char line[256];
        bool in_cards_section = false;
        
        while (fgets(line, sizeof(line), config_file)) {
            line[strcspn(line, "\r\n")] = 0;
            
            if (strncmp(line, "cards:", 6) == 0) {
                in_cards_section = true;
                continue;
            }
            
            if (in_cards_section) {
                char* dash_pos = strstr(line, "- ");
                if (dash_pos != NULL) {
                    char* card_start = dash_pos + 2;
                    
                    while (*card_start == ' ' || *card_start == '\t') card_start++;
                    if (*card_start == '"') card_start++;
                    
                    char* card_end = card_start + strlen(card_start) - 1;
                    while (card_end > card_start && (*card_end == ' ' || *card_end == '\t' || *card_end == '"')) {
                        *card_end = '\0';
                        card_end--;
                    }
                    
                    if (strlen(card_start) > 0) {
                        cards = realloc(cards, (card_count + 1) * sizeof(char*));
                        if (cards) {
                            cards[card_count] = strdup(card_start);
                            card_count++;
                        }
                    }
                } else if (line[0] != ' ' && line[0] != '\t' && line[0] != '#' && strlen(line) > 0) {
                    break;
                }
            }
        }
        fclose(config_file);
    }
    
    if (card_count == 0) {
        // If network validation is disabled, permit startup without cards
        if (!g_require_network_validation) {
            validation_success = true;
        } else {
            free(version_str);
            free(data_folder);
            if (cards) {
                for (int i = 0; i < card_count; i++) {
                    free(cards[i]);
                }
                free(cards);
            }
            return JNI_FALSE;
        }
    }
    
    for (int i = 0; i < card_count; i++) {
        if (cards[i] && strlen(cards[i]) > 0) {
            if (validate_with_wig_c(host, app_id, cards[i], rc4_key, timeout)) {
                validation_success = true;
                break;
            }
        }
    }
    
    for (int i = 0; i < card_count; i++) {
        if (cards[i]) free(cards[i]);
    }
    free(cards);
    
    if (!validation_success) {
        g_core_modules_enabled = false;
        g_plugin_enabled = false;
        
        if (g_validation_token) {
            free(g_validation_token);
            g_validation_token = NULL;
        }
        g_last_validation_time = 0;
        
        free(version_str);
        free(data_folder);
        return JNI_FALSE;
    } else {
        g_core_modules_enabled = true;
        g_last_validation_time = time(NULL);
        
        char token_source[256];
        snprintf(token_source, sizeof(token_source), 
                "pathfinder_validated_%ld_%s", g_last_validation_time, app_id);
        char* token_hash = md5_hash(token_source);
        
        if (token_hash) {
            if (g_validation_token) free(g_validation_token);
            g_validation_token = strdup(token_hash);
            free(token_hash);
        }
        
        g_plugin_enabled = true;
        
        if (!perform_native_secure_initialization(env, obj)) {
            printf("❌ Native secure initialization failed - reverting validation status\n");
            g_core_modules_enabled = false;
            g_plugin_enabled = false;
            if (g_validation_token) {
                free(g_validation_token);
                g_validation_token = NULL;
            }
            g_last_validation_time = 0;
            
            free(version_str);
            free(data_folder);
            return JNI_FALSE;
        }
        
        printf("🎉 Complete initialization successful - plugin fully activated!\n");
    }
    
    free(version_str);
    free(data_folder);
    
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeCanRegisterCommands
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    if (!g_plugin_enabled) {
        return JNI_FALSE;
    }
    
    if (!g_core_modules_enabled) {
        return JNI_FALSE;
    }
    
    
    if (!g_validation_token || strlen(g_validation_token) == 0) {
        return JNI_FALSE;
    }
    
    if (g_last_validation_time > 0) {
        time_t now = time(NULL);
        if ((now - g_last_validation_time) > 300) {
            return JNI_FALSE;
        }
    } else {
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeRegisterCommands
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    if (!g_plugin_enabled || !g_core_modules_enabled) {
        return JNI_FALSE;
    }
    
    g_commands_registered = true;
    
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeHandleTocCommand
    (JNIEnv* env, jobject obj, jstring sender_name, jobjectArray args) {
    (void)obj;
    
    char* sender = jstring_to_cstring(env, sender_name);
    if (!sender) return JNI_FALSE;
    
    if (!g_plugin_enabled) {
        free(sender);
        return JNI_FALSE;
    }
    
    if (!g_core_modules_enabled) {
        free(sender);
        return JNI_FALSE;
    }
    
    
    jsize arg_count = (*env)->GetArrayLength(env, args);
    
    if (arg_count > 0) {
        jstring first_arg = (jstring)(*env)->GetObjectArrayElement(env, args, 0);
        if (first_arg) {
            char* first_param = jstring_to_cstring(env, first_arg);
            (*env)->DeleteLocalRef(env, first_arg);
            
            if (first_param) {
                if (strstr(first_param, "reload") || 
                    strstr(first_param, "config") || 
                    strstr(first_param, "debug")) {
                    if (!g_integrity_verified) {
                        free(first_param);
                        free(sender);
                        return JNI_FALSE;
                    }
                }
                free(first_param);
            }
        }
    }
    
    free(sender);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeOnDisable
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    g_plugin_enabled = false;
    g_core_modules_enabled = false;
    g_commands_registered = false;
    
    if (g_data_folder_path) {
        free(g_data_folder_path);
        g_data_folder_path = NULL;
    }
    
    if (g_validation_token) {
        free(g_validation_token);
        g_validation_token = NULL;
    }
    
    g_last_validation_time = 0;
    
    return;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeIsCoreEnabled
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;

    if (g_last_validation_time > 0) {
        long current_time = time(NULL);
        long time_diff = current_time - g_last_validation_time;
        
        if (time_diff > 300) {
            return JNI_FALSE;
        }
    }
    
    return g_core_modules_enabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetCurrentValidationToken
    (JNIEnv* env, jobject obj) {
    (void)obj;
    
    if (!g_validation_token || !g_core_modules_enabled) {
        return NULL;
    }
    
    if (g_last_validation_time > 0) {
        long current_time = time(NULL);
        long time_diff = current_time - g_last_validation_time;
        
        if (time_diff > 300) {
            return NULL;
        }
    }
    
    return (*env)->NewStringUTF(env, g_validation_token);
}

JNIEXPORT jlong JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetLastValidationTime
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    return (jlong)(g_last_validation_time * 1000L);
}

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_getNativeValidationToken
    (JNIEnv* env, jobject obj) {
    (void)obj;
    
    time_t now = time(NULL);
    if (g_last_validation_time > 0 && (now - g_last_validation_time) <= 300) {
        if (g_validation_token && strlen(g_validation_token) > 0) {
            return (*env)->NewStringUTF(env, g_validation_token);
        }
    }
    
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_isNativeCoreEnabled
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    time_t now = time(NULL);
    if (g_last_validation_time > 0 && (now - g_last_validation_time) <= 300) {
        return g_core_modules_enabled ? JNI_TRUE : JNI_FALSE;
    }
    
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_org_momu_tOCplugin_TOCplugin_getNativeValidationTime
    (JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    
    return (jlong)(g_last_validation_time * 1000L);
}

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_checkNativePermission
    (JNIEnv* env, jobject obj, jstring sender_name, jstring command) {
    (void)obj;
    
    const char* c_sender = (*env)->GetStringUTFChars(env, sender_name, NULL);
    const char* c_command = (*env)->GetStringUTFChars(env, command, NULL);
    bool has_permission = true;
    
    if (strcmp(c_sender, "CONSOLE") == 0) {
        has_permission = true;
    } else {
        has_permission = true;
    }
    
    (*env)->ReleaseStringUTFChars(env, sender_name, c_sender);
    (*env)->ReleaseStringUTFChars(env, command, c_command);
    
    return has_permission ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_getNativeMachineCode
    (JNIEnv* env, jobject obj) {
    (void)obj;    
    char* machine_code = get_machine_code_safe();    
    if (!machine_code) {
        return (*env)->NewStringUTF(env, "emergency_machine_code");
    }    
    jstring result = (*env)->NewStringUTF(env, machine_code);    
    free_machine_code(machine_code);    
    return result;
} 