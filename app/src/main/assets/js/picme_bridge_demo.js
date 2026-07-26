// PoLang JSBridge 演示脚本（包内脚本，不触网）
// 由 Debug 页「运行 JS Bridge 演示」加载；运行后结果打到 PoLang:Js 日志。
// JS 只能通过 bridge 间接访问原生（QuickJS 无 Java 桥，天然沙箱隔离）。
// （按「async 函数体」语义经 evalAsync 执行：顶层 await/return 合法，无需自包 IIFE）
console.log("picme jsbridge demo start");

// 同步调用原生 handler（BuiltInHandlers 的 sync handler）
var sum = bridge.call("math.add", [18, 24]);
console.log("math.add =>", sum);

var up = bridge.call("string.upper", "polang");
console.log("string.upper =>", up);

// 异步调用原生 handler，await Promise
try {
    var info = await bridge.callAsync("device.info", null);
    console.log("device.info =>", info);
} catch (err) {
    console.log("device.info error", err);
}

// 列出已注册 handler
console.log("handlers =>", bridge.list());

return "demo-done";
