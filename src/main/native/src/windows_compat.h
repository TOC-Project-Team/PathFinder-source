#ifndef WINDOWS_COMPAT_H
#define WINDOWS_COMPAT_H

#ifdef WINDOWS_PLATFORM

// 包含Windows特定的头文件
#include <windows.h>
#include <winhttp.h>
#include <wincrypt.h>
#include <process.h>
#include <stdbool.h>

// 链接Windows库
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "advapi32.lib")

// min函数定义
#ifndef min
#define min(a, b) ((a) < (b) ? (a) : (b))
#endif

// 信号相关定义（占位符）
#define SIGTRAP     5
#define SIGILL      4
#define SIGKILL     9
#define SIGINT      2
#define SIGTERM     15
#define SIG_DFL     ((void (*)(int))0)

// ptrace相关定义（占位符）
#define PTRACE_TRACEME      0
#define PTRACE_DETACH       17

// 内存保护相关定义
#define PROT_READ       0x1
#define PROT_EXEC       0x4

// 其他定义
#define RTLD_DEFAULT    ((void *) 0)

// HTTP响应结构
struct http_response {
    char* data;
    size_t size;
};

// Windows原生HTTP客户端实现
static bool windows_http_request(const char* url, const char* post_data, struct http_response* response) {
    if (!url || !response) return false;
    
    // 初始化响应
    response->data = NULL;
    response->size = 0;
    
    // 解析URL
    wchar_t wide_url[1024];
    MultiByteToWideChar(CP_UTF8, 0, url, -1, wide_url, 1024);
    
    URL_COMPONENTS urlComp;
    memset(&urlComp, 0, sizeof(urlComp));
    urlComp.dwStructSize = sizeof(urlComp);
    
    wchar_t hostname[256], path[512];
    urlComp.lpszHostName = hostname;
    urlComp.dwHostNameLength = 256;
    urlComp.lpszUrlPath = path;
    urlComp.dwUrlPathLength = 512;
    
    if (!WinHttpCrackUrl(wide_url, 0, 0, &urlComp)) {
        return false;
    }
    
    // 创建WinHTTP会话
    HINTERNET hSession = WinHttpOpen(L"PathFinder/1.4.0", 
                                     WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                     WINHTTP_NO_PROXY_NAME, 
                                     WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession) return false;
    
    // 连接到服务器
    HINTERNET hConnect = WinHttpConnect(hSession, hostname, urlComp.nPort, 0);
    if (!hConnect) {
        WinHttpCloseHandle(hSession);
        return false;
    }
    
    // 创建请求
    HINTERNET hRequest = WinHttpOpenRequest(hConnect, post_data ? L"POST" : L"GET", path,
                                           NULL, WINHTTP_NO_REFERER, 
                                           WINHTTP_DEFAULT_ACCEPT_TYPES, 
                                           urlComp.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0);
    if (!hRequest) {
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return false;
    }
    
    // 设置超时
    WinHttpSetTimeouts(hRequest, 30000, 30000, 30000, 30000);
    
    // 发送请求
    bool result = false;
    if (post_data) {
        // POST请求
        const char* headers = "Content-Type: application/x-www-form-urlencoded\r\n";
        wchar_t wide_headers[512];
        MultiByteToWideChar(CP_UTF8, 0, headers, -1, wide_headers, 512);
        
        if (WinHttpSendRequest(hRequest, wide_headers, -1, 
                              (LPVOID)post_data, strlen(post_data), 
                              strlen(post_data), 0)) {
            result = (WinHttpReceiveResponse(hRequest, NULL) == TRUE);
        }
    } else {
        // GET请求
        if (WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                              WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
            result = (WinHttpReceiveResponse(hRequest, NULL) == TRUE);
        }
    }
    
    if (result) {
        // 读取响应数据
        DWORD bytesAvailable = 0;
        DWORD bytesRead = 0;
        char buffer[4096];
        
        do {
            if (WinHttpQueryDataAvailable(hRequest, &bytesAvailable) && bytesAvailable > 0) {
                DWORD toRead = min(bytesAvailable, sizeof(buffer));
                if (WinHttpReadData(hRequest, buffer, toRead, &bytesRead) && bytesRead > 0) {
                    // 扩展响应缓冲区
                    char* newData = realloc(response->data, response->size + bytesRead + 1);
                    if (newData) {
                        response->data = newData;
                        memcpy(response->data + response->size, buffer, bytesRead);
                        response->size += bytesRead;
                        response->data[response->size] = 0;
                    }
                }
            }
        } while (bytesAvailable > 0);
    }
    
    WinHttpCloseHandle(hRequest);
    WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    
    return result && response->data;
}

// Windows原生MD5实现
static bool windows_md5_hash(const char* input, char* output) {
    if (!input || !output) return false;
    
    HCRYPTPROV hProv = 0;
    HCRYPTHASH hHash = 0;
    BYTE hash[16];
    DWORD hashLen = 16;
    
    // 获取加密提供者
    if (!CryptAcquireContext(&hProv, NULL, NULL, PROV_RSA_FULL, CRYPT_VERIFYCONTEXT)) {
        return false;
    }
    
    // 创建哈希对象
    if (!CryptCreateHash(hProv, CALG_MD5, 0, 0, &hHash)) {
        CryptReleaseContext(hProv, 0);
        return false;
    }
    
    // 计算哈希
    if (!CryptHashData(hHash, (BYTE*)input, strlen(input), 0)) {
        CryptDestroyHash(hHash);
        CryptReleaseContext(hProv, 0);
        return false;
    }
    
    // 获取哈希值
    if (!CryptGetHashParam(hHash, HP_HASHVAL, hash, &hashLen, 0)) {
        CryptDestroyHash(hHash);
        CryptReleaseContext(hProv, 0);
        return false;
    }
    
    // 转换为十六进制字符串
    for (int i = 0; i < 16; i++) {
        sprintf(output + i * 2, "%02x", hash[i]);
    }
    output[32] = '\0';
    
    CryptDestroyHash(hHash);
    CryptReleaseContext(hProv, 0);
    return true;
}

// Windows原生RC4实现（使用标准RC4算法确保与Linux兼容）
static bool windows_rc4_encrypt_decrypt(const char* data, const char* key, bool is_hex_input, char** result) {
    if (!data || !key || !result) return false;
    
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
        input_data = malloc(input_len + 1);
        if (!input_data) return false;
        
        for (int i = 0; i < input_len; i++) {
            unsigned int byte;
            if (sscanf(data + i * 2, "%2x", &byte) != 1) {
                free(input_data);
                return false;
            }
            input_data[i] = (unsigned char)byte;
        }
    } else {
        input_len = data_len;
        input_data = malloc(input_len + 1);
        if (!input_data) return false;
        memcpy(input_data, data, input_len);
    }
    
    // RC4加密/解密
    unsigned char* output = malloc(input_len + 1);
    if (!output) {
        free(input_data);
        return false;
    }
    
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
    
    // 生成结果
    bool success = false;
    if (is_hex_input) {
        // 输出为字符串
        *result = malloc(input_len + 1);
        if (*result) {
            memcpy(*result, output, input_len);
            (*result)[input_len] = '\0';
            success = true;
        }
    } else {
        // 输出为十六进制
        *result = malloc(input_len * 2 + 1);
        if (*result) {
            for (int k = 0; k < input_len; k++) {
                sprintf(*result + k * 2, "%02x", output[k]);
            }
            (*result)[input_len * 2] = '\0';
            success = true;
        }
    }
    
    free(input_data);
    free(output);
    
    return success;
}

// OpenSSL/MD5相关定义（当NO_OPENSSL时）
#ifdef NO_OPENSSL
typedef struct {
    char dummy[128];
} MD5_CTX;
#define MD5_DIGEST_LENGTH 16

// 使用Windows原生实现替代OpenSSL
static inline void MD5_Init(MD5_CTX *ctx) { (void)ctx; }
static inline void MD5_Update(MD5_CTX *ctx, const void *data, unsigned long len) { (void)ctx; (void)data; (void)len; }
static inline void MD5_Final(unsigned char *md, MD5_CTX *ctx) { 
    (void)ctx; 
    if(md) memset(md, 0, MD5_DIGEST_LENGTH);
}
#endif

// CURL相关定义（当NO_CURL时）
#ifdef NO_CURL
typedef void CURL;
typedef int CURLcode;
#define CURLE_OK            0
#define CURLOPT_URL         10002
#define CURLOPT_POSTFIELDS  10015
#define CURLOPT_CONNECTTIMEOUT  78
#define CURLOPT_TIMEOUT     13
#define CURLOPT_WRITEFUNCTION   20011
#define CURLOPT_WRITEDATA   10001
#define CURLINFO_RESPONSE_CODE  0x200002

// 使用Windows原生实现替代CURL
static inline CURL* curl_easy_init(void) { return (CURL*)1; } // 返回非NULL表示成功
static inline void curl_easy_cleanup(CURL *curl) { (void)curl; }
static inline CURLcode curl_easy_setopt(CURL *curl, int option, ...) { (void)curl; (void)option; return CURLE_OK; }
static inline CURLcode curl_easy_perform(CURL *curl) { (void)curl; return CURLE_OK; }
static inline CURLcode curl_easy_getinfo(CURL *curl, int info, ...) { (void)curl; (void)info; return CURLE_OK; }
static inline const char* curl_easy_strerror(CURLcode code) { (void)code; return "success"; }
#endif

// 函数占位符实现
static inline int ptrace(int request, int pid, void *addr, void *data) {
    (void)request; (void)pid; (void)addr; (void)data;
    return -1; // Windows上不支持ptrace
}

static inline int signal(int sig, void (*func)(int)) {
    (void)sig; (void)func;
    return 0; // 占位符
}

static inline int mprotect(void *addr, size_t len, int prot) {
    (void)addr; (void)len; (void)prot;
    return 0; // 占位符
}

static inline int kill(int pid, int sig) {
    (void)pid; (void)sig;
    return 0; // 占位符
}

static inline void usleep(unsigned long usec) {
    Sleep(usec / 1000); // 转换为毫秒
}

static inline void* dlsym(void *handle, const char *symbol) {
    (void)handle; (void)symbol;
    return NULL; // Windows上不支持dlsym
}

// 用户信息结构体占位符
struct passwd {
    char *pw_name;
};

static inline struct passwd* getpwuid(int uid) {
    (void)uid;
    return NULL; // Windows上不支持
}

static inline int getuid(void) {
    return 0; // Windows上不支持
}

// utsname结构体和函数
struct utsname {
    char sysname[65];
    char nodename[65];
    char release[65];
    char version[65];
    char machine[65];
};

static inline int uname(struct utsname *buf) {
    if (!buf) return -1;
    
    strcpy(buf->sysname, "Windows");
    strcpy(buf->nodename, "windows-host");
    strcpy(buf->release, "10.0");
    strcpy(buf->version, "Windows");
    strcpy(buf->machine, "x86_64");
    
    return 0;
}

// pthread相关定义和占位符
typedef HANDLE pthread_t;
static inline int pthread_create(pthread_t *thread, void *attr, void *(*start_routine)(void *), void *arg) {
    (void)thread; (void)attr; (void)start_routine; (void)arg;
    return -1; // Windows上不支持pthread
}
static inline int pthread_detach(pthread_t thread) {
    (void)thread;
    return 0; // 占位符
}

// 外部符号占位符（仅声明，不定义避免重复定义错误）
#ifndef _START_END_DEFINED
#define _START_END_DEFINED
static char _start_placeholder = 0;
static char _end_placeholder = 0;
#define _start _start_placeholder
#define _end _end_placeholder
#endif

#endif // WINDOWS_PLATFORM

#endif // WINDOWS_COMPAT_H 