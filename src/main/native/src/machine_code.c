#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include "machine_code.h"
#ifdef WINDOWS_PLATFORM
    #include <windows.h>
    #include <iphlpapi.h>
    #include "windows_compat.h"
#else
    #include <unistd.h>
    #include <sys/utsname.h>
    #include <pwd.h>
#endif

/**
 * 获取系统信息部分
 */
static void get_system_info(char* buffer, size_t buffer_size) {
    struct utsname sys_info;
    struct passwd *pw;
    char temp[512] = {0};
    
    // 清空缓冲区
    memset(buffer, 0, buffer_size);
    
    // 获取系统信息
    if (uname(&sys_info) == 0) {
        // os.name 对应 sys_info.sysname
        strncat(buffer, sys_info.sysname, buffer_size - strlen(buffer) - 1);
        
        // os.arch 对应 sys_info.machine  
        strncat(buffer, sys_info.machine, buffer_size - strlen(buffer) - 1);
    } else {
        strncat(buffer, "unknown_system", buffer_size - strlen(buffer) - 1);
    }
    
    // user.name 对应当前用户名
    pw = getpwuid(getuid());
    if (pw && pw->pw_name) {
        strncat(buffer, pw->pw_name, buffer_size - strlen(buffer) - 1);
    } else {
        strncat(buffer, "unknown_user", buffer_size - strlen(buffer) - 1);
    }
}

/**
 * 读取CPU信息
 */
static void get_cpu_info(char* buffer, size_t buffer_size) {
    FILE* file = fopen("/proc/cpuinfo", "r");
    if (!file) {
        // 移除调试输出
        buffer[0] = '\0';
        return;
    }
    
    char line[256];
    buffer[0] = '\0';
    
    while (fgets(line, sizeof(line), file) && strlen(buffer) < buffer_size - 50) {
        if (strncmp(line, "processor", 9) == 0 || 
            strncmp(line, "model name", 10) == 0 || 
            strncmp(line, "cpu MHz", 7) == 0) {
            strncat(buffer, line, buffer_size - strlen(buffer) - 1);
            break;
        }
    }
    
    fclose(file);
}

/**
 * 生成真实机械码（对应Java的getRealMachineCode）
 */
char* generate_real_machine_code(void) {
    // 移除调试输出
    
    char system_info[256];
    char cpu_info[256];
    
    get_system_info(system_info, sizeof(system_info));
    // 移除调试输出
    
    get_cpu_info(cpu_info, sizeof(cpu_info));
    // 移除调试输出
    
    size_t total_size = strlen(system_info) + strlen(cpu_info) + 100;
    char* result = malloc(total_size);
    if (!result) {
        // 移除调试输出
        return NULL;
    }
    
    snprintf(result, total_size, "%s%s", system_info, cpu_info);
    
    // 移除调试输出
    
    return result;
}

/**
 * 获取机械码（带错误处理）
 */
char* get_machine_code_safe(void) {
    char* machine_code = generate_real_machine_code();
    if (!machine_code) {
        // 移除调试输出，使用紧急备用方案
        return strdup("emergency_machine_code_linux_fallback");
    }
    
    return machine_code;
}

/**
 * 释放机械码内存
 */
void free_machine_code(char* machine_code) {
    if (machine_code) {
        free(machine_code);
    }
}

/**
 * 获取机械码的简化接口（与原有代码兼容）
 */
void get_machine_code_to_buffer(char* buffer, size_t buffer_size) {
    char* machine_code = get_machine_code_safe();
    
    if (machine_code && buffer && buffer_size > 0) {
        strncpy(buffer, machine_code, buffer_size - 1);
        buffer[buffer_size - 1] = '\0';
        free_machine_code(machine_code);
    } else if (buffer && buffer_size > 0) {
        strncpy(buffer, "emergency_fallback", buffer_size - 1);
        buffer[buffer_size - 1] = '\0';
    }
} 