// 相册健康度报告（包内 JS，只读，不触网）
// 综合评估相册的打标覆盖、人脸覆盖、标签分布，给出健康度评分和建议。
// 由 Debug 页运行 / 远程 LLM 生成参考。
// （按「async 函数体」语义经 evalAsync 执行：顶层 await/return 合法，无需自包 IIFE）
var s = await bridge.callAsync("gallery.summary", {});
var tags = await bridge.callAsync("gallery.tags", {});
var total = s.totalMedia;

if (total === 0) {
    return { healthScore: 0, suggestion: "相册为空" };
}

var pct = function (n) {
    return Math.round((n / total) * 1000) / 10;
};

// 健康度评分（0-100），综合打标率、人脸识别率、语义编码率
var labeledPct = pct(s.labeledCount);
var facePct = pct(s.hasFaceCount);
var semanticPct = pct(s.semanticEncodedCount);
var unlabeledPct = pct(s.unlabeledCount);

// 权重：打标 40%、语义 35%、人脸 25%
var healthScore = Math.round(
    labeledPct * 0.4 + semanticPct * 0.35 + facePct * 0.25
);

// top 5 标签
var tagKeys = Object.keys(tags).sort(function (a, b) { return tags[b] - tags[a]; });
var topTags = tagKeys.slice(0, 5).map(function (k) {
    return { tag: k, count: tags[k], pct: pct(tags[k]) };
});

// 生成建议
var suggestions = [];
if (unlabeledPct > 30) {
    suggestions.push("未打标照片较多（" + unlabeledPct + "%），建议运行 TAG 扫描");
}
if (facePct < 20 && total > 100) {
    suggestions.push("人脸识别覆盖率低（" + facePct + "%），可能遗漏人像照片");
}
if (semanticPct < 50) {
    suggestions.push("语义编码未完成（" + semanticPct + "%），影响自然语言搜索精度");
}
if (suggestions.length === 0) {
    suggestions.push("相册状态良好，各维度覆盖均衡");
}

return {
    totalMedia: total,
    healthScore: healthScore,
    labeledPct: labeledPct,
    unlabeledPct: unlabeledPct,
    facePct: facePct,
    semanticPct: semanticPct,
    topTags: topTags,
    tagDiversity: tagKeys.length,
    isScanning: s.isScanning,
    suggestions: suggestions
};
