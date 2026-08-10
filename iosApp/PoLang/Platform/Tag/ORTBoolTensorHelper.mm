//
//  ORTBoolTensorHelper.mm
//  PoLang
//
//  ObjC++ 实现：通过 ORT C++ API 创建 BOOL 张量，绕过 ObjC 枚举不支持 BOOL 的限制。
//

#import "ORTBoolTensorHelper.h"

// 导入 ObjC 公开头文件（ORTValue 等类）
#import <onnxruntime_objc/ort_value.h>

// 导入 ORT C/C++ API 头文件（来自 onnxruntime-c pod 的公共头）
#include "onnxruntime_c_api.h"
#include "onnxruntime_cxx_api.h"

// ORTValue 内部指定初始化方法（非公开头，通过 category 在运行时动态调用）
@interface ORTValue (Florence2InternalInit)
- (nullable instancetype)initWithCXXAPIOrtValue:(Ort::Value &&)rvalue
                             externalTensorData:(nullable NSMutableData *)data
                                          error:(NSError **)error;
@end

ORTValue *_Nullable ortCreateBoolTensor(BOOL value, NSError **error) {
    try {
        // CPU 内存描述符
        auto memInfo = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeCPU);

        // BOOL 张量：1 字节，值 0(false)/1(true)
        uint8_t data = value ? 1 : 0;
        NSMutableData *tensorData = [NSMutableData dataWithBytes:&data length:1];

        // shape [1]（对齐 Android booleanArrayOf(false) 的 1-D shape）
        int64_t shape[] = {1};
        Ort::Value ortValue = Ort::Value::CreateTensor(
            memInfo,
            tensorData.mutableBytes,
            tensorData.length,
            shape,
            1,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);

        // 通过内部初始化方法包装为 ObjC ORTValue
        return [[ORTValue alloc] initWithCXXAPIOrtValue:std::move(ortValue)
                                    externalTensorData:tensorData
                                                 error:error];
    } catch (const std::exception &e) {
        if (error) {
            *error = [NSError errorWithDomain:@"ORTBoolTensorHelper" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithUTF8String:e.what()]}];
        }
        return nil;
    } catch (...) {
        if (error) {
            *error = [NSError errorWithDomain:@"ORTBoolTensorHelper" code:2
                                     userInfo:@{NSLocalizedDescriptionKey: @"Unknown C++ exception"}];
        }
        return nil;
    }
}
