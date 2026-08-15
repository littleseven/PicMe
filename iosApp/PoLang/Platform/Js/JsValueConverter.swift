import Foundation
import JavaScriptCore
import SharedKit

/// JSValue（JavaScriptCore）↔ commonMain `JsValue`（SharedKit）双向转换。
///
/// 对齐 Android `QuickJsConverter`：两端语义一致——
/// JS null/undefined → `JsValue.Null`；number → `.Num`；boolean → `.Bool`；
/// string → `.Str`；array → `.Arr`；object → `.Obj`；其余降级 `.Str`。
///
/// 布尔识别用 `CFBooleanGetTypeID()`（JS `true/false` 经 JSCore 桥接为 CFBoolean，
/// 与 NSNumber 数值区分；`objCType` 在 32/64 位不稳，CFBoolean 是正解）。
enum JsValueConverter {

    /// JavaScriptCore `JSValue` → commonMain `JsValue`。
    /// 经 `toObject()` 拿到 Obj-C 投影后递归转换（NSArray/NSDictionary 自动展开）。
    static func toJsValue(_ value: JSValue?) -> JsValue {
        fromAny(value?.toObject())
    }

    /// Obj-C `Any?`（JSValue.toObject() 产物）→ commonMain `JsValue`。
    static func fromAny(_ any: Any?) -> JsValue {
        // null / undefined → .Null
        if any == nil { return JsValue.Null() }
        if any is NSNull { return JsValue.Null() }
        // number / boolean（NSNumber；先判 bool 再判 num）
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return JsValue.Bool(value: number.boolValue)
            }
            return JsValue.Num(value: number.doubleValue)
        }
        // string
        if let string = any as? String {
            return JsValue.Str(value: string)
        }
        // array
        if let array = any as? [Any] {
            return JsValue.Arr(items: array.map { fromAny($0) })
        }
        // object
        if let dict = any as? [String: Any] {
            var entries: [String: JsValue] = [:]
            for (key, val) in dict {
                entries[key] = fromAny(val)
            }
            return JsValue.Obj(entries: entries)
        }
        // 兜底：降级为字符串（对齐 QuickJsConverter 未知类型处理）
        return JsValue.Str(value: String(describing: any))
    }

    /// commonMain `JsValue` → Obj-C `Any`（可喂 `JSValue(object:)` 或 block 参数）。
    static func toAny(_ jsValue: JsValue) -> Any {
        switch jsValue {
        case is JsValue.Null:
            return NSNull()
        case let bool as JsValue.Bool:
            return bool.value
        case let number as JsValue.Num:
            return number.value
        case let string as JsValue.Str:
            return string.value
        case let array as JsValue.Arr:
            return array.items.map { toAny($0) }
        case let object as JsValue.Obj:
            var dict: [String: Any] = [:]
            for (key, value) in object.entries {
                dict[key] = toAny(value)
            }
            return dict
        default:
            return NSNull()
        }
    }

    /// commonMain `JsValue` → JavaScriptCore `JSValue`（注入 [JSContext] 构造）。
    static func toJSValue(_ jsValue: JsValue, in context: JSContext) -> JSValue {
        JSValue(object: toAny(jsValue), in: context)
    }
}
