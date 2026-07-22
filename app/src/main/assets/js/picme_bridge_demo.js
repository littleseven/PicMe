// PoLang JSBridge 演示脚本（包内脚本，不触网）
// 由 Debug 页「运行 JS Bridge 演示」加载；运行后结果打到 PoLang:Js 日志。
// JS 只能通过 bridge 间接访问原生（ClassShutter deny-all 沙箱禁止直接访问 Java）。
(function () {
    console.log("picme jsbridge demo start");

    // 同步调用原生 handler
    var sum = bridge.call("math.add", [18, 24]);
    console.log("math.add =>", sum);

    var up = bridge.call("string.upper", "polang");
    console.log("string.upper =>", up);

    // 异步调用原生 handler，回调里打印
    bridge.callAsync("device.info", null, function (err, info) {
        if (err) {
            console.log("device.info error", err);
        } else {
            console.log("device.info =>", info);
        }
    });

    // 列出已注册 handler
    console.log("handlers =>", bridge.list());

    return "demo-done";
})();
