#ifndef MACHINE_CODE_H
#define MACHINE_CODE_H

#include <stddef.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 生成真实机械码（对应Java的getRealMachineCode）
 * @return 动态分配的机械码字符串，需要调用free_machine_code释放
 */
char* generate_real_machine_code(void);

/**
 * 获取机械码（带错误处理和备用方案）
 * @return 动态分配的机械码字符串，需要调用free_machine_code释放
 */
char* get_machine_code_safe(void);

/**
 * 释放机械码内存
 * @param machine_code 要释放的机械码字符串
 */
void free_machine_code(char* machine_code);

/**
 * 获取机械码的简化接口（与原有代码兼容）
 * @param buffer 输出缓冲区
 * @param buffer_size 缓冲区大小
 */
void get_machine_code_to_buffer(char* buffer, size_t buffer_size);

#ifdef __cplusplus
}
#endif

#endif // MACHINE_CODE_H 