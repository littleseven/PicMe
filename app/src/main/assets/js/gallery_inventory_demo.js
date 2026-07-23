// 相册盘点演示脚本（包内 JS，只读，不触网）
// 调 bridge.call('gallery.summary') 取端侧聚合统计，在 JS 内做组合计算（比率/占比/分布），
// return 结构化结果对象。该对象经 ChatRunScriptCapability 回传远程 LLM 做自然语言总结。
// Debug 页运行 / 远程 LLM 生成参考。
(function () {
    var s = bridge.call("gallery.summary");
    var total = s.totalMedia;
    var pct = function (n) {
        return total > 0 ? Math.round((n / total) * 1000) / 10 : 0; // 保留 1 位小数的百分比
    };
    var namedRatio = s.personClusterCount > 0
        ? Math.round((s.namedPersonCount / s.personClusterCount) * 1000) / 10
        : 0;
    return {
        totalMedia: total,
        photos: s.totalPhotos,
        videos: s.totalVideos,
        labeledRatioPct: pct(s.labeledCount),
        unlabeledRatioPct: pct(s.unlabeledCount),
        faceRatioPct: pct(s.hasFaceCount),
        semanticEncodedRatioPct: pct(s.semanticEncodedCount),
        personClusterCount: s.personClusterCount,
        namedPersonRatioPct: namedRatio,
        personPerMedia: total > 0 ? Math.round((s.personClusterCount / total) * 1000) / 1000 : 0,
        recommendation: s.recommendation,
        isScanning: s.isScanning
    };
})();
