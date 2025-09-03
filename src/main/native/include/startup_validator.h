#ifndef STARTUP_VALIDATOR_H
#define STARTUP_VALIDATOR_H

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

// 验证结果代码（与Java兼容）
#define VALIDATION_SUCCESS          1
#define VALIDATION_FAILED          -1
#define VALIDATION_NETWORK_ERROR    -2
#define VALIDATION_TAMPER_DETECTED  -3
#define VALIDATION_DEBUG_DETECTED   -4

// 高级验证专用混淆值
#define ADVANCED_VALIDATION_SUCCESS    0x12345678
#define ADVANCED_VALIDATION_FAILED    -0x87654321

// 防破解混淆宏
#define ANTI_CRACK_XOR_KEY          0xDEADBEEF
#define ANTI_CRACK_CHECKSUM         0xCAFEBABE
#define OBFUSCATE(x)                ((x) ^ ANTI_CRACK_XOR_KEY)
#define DEOBFUSCATE(x)              ((x) ^ ANTI_CRACK_XOR_KEY)

// 复杂验证结果结构
typedef struct {
    uint32_t magic_header;           // 魔数头
    uint32_t validation_result;      // 混淆后的验证结果
    uint32_t timestamp;              // 验证时间戳
    uint32_t checksum;               // 结构体校验和
    char validation_token[64];       // 验证令牌
    uint32_t magic_footer;           // 魔数尾
} ValidationResult;

// 反破解保护函数
bool initialize_anti_crack_protection(void);
bool detect_debugging_environment(void);
bool verify_code_integrity(void);
uint64_t generate_anti_tamper_seed(void);
void obfuscate_string(char* str, int len);

// 混淆的验证函数
ValidationResult* create_validation_result(int result_code, const char* token);
bool verify_validation_result(ValidationResult* result);
void destroy_validation_result(ValidationResult* result);

// JNI接口（使用复杂返回值）
JNIEXPORT jlong JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeStartupValidationAdvanced
    (JNIEnv* env, jobject obj, jstring app_id, jobjectArray cards_array, 
     jstring host, jstring rc4_key, jint timeout);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeVerifyValidationResult
    (JNIEnv* env, jobject obj, jlong result_ptr);

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetValidationToken
    (JNIEnv* env, jobject obj, jlong result_ptr);

JNIEXPORT void JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeDestroyValidationResult
    (JNIEnv* env, jobject obj, jlong result_ptr);

JNIEXPORT jint JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeStartupValidation
    (JNIEnv* env, jobject obj, jstring app_id, jobjectArray cards_array, 
     jstring host, jstring rc4_key, jint timeout);

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetOrCreateImei
    (JNIEnv* env, jobject obj, jstring app_id);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeVerifyIntegrity
    (JNIEnv* env, jobject obj, jstring app_id);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeOnEnable
    (JNIEnv* env, jobject obj, jstring server_version, jstring plugin_data_folder);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeRegisterCommands
    (JNIEnv* env, jobject obj);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeCanRegisterCommands
    (JNIEnv* env, jobject obj);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeHandleTocCommand
    (JNIEnv* env, jobject obj, jstring sender_name, jobjectArray args);

JNIEXPORT void JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeOnDisable
    (JNIEnv* env, jobject obj);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeIsCoreEnabled
    (JNIEnv* env, jobject obj);

JNIEXPORT jstring JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetCurrentValidationToken
    (JNIEnv* env, jobject obj);

JNIEXPORT jlong JNICALL Java_org_momu_tOCplugin_TOCplugin_nativeGetLastValidationTime
    (JNIEnv* env, jobject obj);

JNIEXPORT jboolean JNICALL Java_org_momu_tOCplugin_TOCpluginNative_nativePluginManager
    (JNIEnv* env, jobject obj, jint action, jobjectArray params);

bool validate_with_wig_c(const char* host, const char* app_id, const char* card,
                         const char* rc4_key, int timeout);


#endif