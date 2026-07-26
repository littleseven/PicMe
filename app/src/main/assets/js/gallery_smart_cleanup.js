// 智能清理建议（包内 JS，只读，不触网）
// 分析相册中可能需要清理的照片：旧截图、低标签覆盖等。
// 由 Debug 页运行 / 远程 LLM 生成参考。
// （按「async 函数体」语义经 evalAsync 执行：顶层 await/return 合法，无需自包 IIFE）
var s = await bridge.callAsync("gallery.summary", {});
var tags = await bridge.callAsync("gallery.tags", {});
var total = s.totalMedia;

if (total === 0) {
    return { suggestion: "相册为空，无需清理" };
}

var pct = function (n) {
    return total > 0 ? Math.round((n / total) * 1000) / 10 : 0;
};

// 查找截图类标签
var screenshotTags = Object.keys(tags).filter(function (k) {
    return k.toLowerCase().indexOf("截图") >= 0 ||
           k.toLowerCase().indexOf("screenshot") >= 0 ||
           k.toLowerCase().indexOf("screen") >= 0;
});
var screenshotCount = screenshotTags.reduce(function (sum, k) {
    return sum + tags[k];
}, 0);

// 查找文档/票据类标签
var docTags = Object.keys(tags).filter(function (k) {
    return k.toLowerCase().indexOf("文档") >= 0 ||
           k.toLowerCase().indexOf("票据") >= 0 ||
           k.toLowerCase().indexOf("document") >= 0 ||
           k.toLowerCase().indexOf("receipt") >= 0;
});
var docCount = docTags.reduce(function (sum, k) {
    return sum + tags[k];
}, 0);

// 未打标 = 潜在可清理（不确定内容）
var unlabeledCount = s.unlabeledCount;

// 时间线：查找很久没拍照或集中某时段
var timeline = await bridge.callAsync("gallery.timeline", {});
var months = Object.keys(timeline).sort();
var staleMonths = 0;
if (months.length > 0) {
    var lastBucket = parseInt(months[months.length - 1]);
    var sixMonthsAgo = lastBucket - 6 * 30 * 24 * 60 * 60 * 1000;
    staleMonths = months.filter(function (k) {
        return parseInt(k) < sixMonthsAgo;
    }).reduce(function (sum, k) {
        return sum + timeline[k];
    }, 0);
}

// 查询截图实际 id（供清理使用，限制数量）
var screenshotIds = [];
if (screenshotTags.length > 0) {
    var q = await bridge.callAsync("gallery.query", {
        label: screenshotTags[0],
        limit: 50
    });
    screenshotIds = q.ids || [];
}

return {
    totalMedia: total,
    screenshotCount: screenshotCount,
    screenshotPct: pct(screenshotCount),
    docCount: docCount,
    docPct: pct(docCount),
    unlabeledCount: unlabeledCount,
    unlabeledPct: pct(unlabeledCount),
    staleMediaCount: staleMonths,
    stalePct: pct(staleMonths),
    screenshotSampleIds: screenshotIds,
    cleanupPriority: screenshotCount > total * 0.2 ? "high" :
                     unlabeledCount > total * 0.5 ? "medium" : "low",
    suggestion: screenshotCount > 0
        ? "发现 " + screenshotCount + " 张截图（" + pct(screenshotCount) + "%），建议定期清理"
        : "相册结构良好，暂无紧急清理需求"
};
