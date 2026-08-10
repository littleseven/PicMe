//
//  ORTBoolTensorHelper.h
//  PoLang
//
//  创建 BOOL 类型的 ORT 标量张量。
//
//  背景：onnxruntime-objc pod 的 ORTTensorElementDataType 枚举不包含 BOOL
//  （仅 Float/Int8/UInt8/Int32/UInt32/Int64/UInt64/String），而 Florence-2 merged
//  decoder 的 use_cache_branch 输入是 BOOL 类型标量。本辅助函数通过 C++ API
//  直接创建 BOOL 张量，再包装为 ObjC ORTValue。
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@class ORTValue;

/// 创建一个 BOOL 类型的标量 ORTValue（shape [1]，值 = value）。
/// @param value 布尔值
/// @param error 错误信息
/// @return ORTValue 实例（BOOL 张量），失败返回 nil
FOUNDATION_EXPORT ORTValue *_Nullable ortCreateBoolTensor(BOOL value, NSError *_Nullable *_Nullable error);

NS_ASSUME_NONNULL_END
