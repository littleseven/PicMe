// 标签交叉分析（包内 JS，只读，不触网）
// 在条件过滤后的结果集内统计标签分布，实现多维交叉分析。
// 示例场景："人像照片里最常见的场景标签是什么"
// 由 Debug 页运行 / 远程 LLM 生成参考。
(function () {
    var s = bridge.call("gallery.summary");
    var total = s.totalMedia;

    // 全局标签分布
    var globalTags = bridge.call("gallery.tags");

    // 人像照片（hasFace=true）内的标签分布
    var faceTags = bridge.call("gallery.stats_by_tag", { hasFace: true });

    // 非人像照片的标签分布（通过差集推断）
    var nonFaceTags = {};
    Object.keys(globalTags).forEach(function (k) {
        var faceCount = faceTags[k] || 0;
        var globalCount = globalTags[k];
        if (globalCount > faceCount) {
            nonFaceTags[k] = globalCount - faceCount;
        }
    });

    // 排序取 top 5
    var sortTop = function (obj, n) {
        return Object.keys(obj)
            .sort(function (a, b) { return obj[b] - obj[a]; })
            .slice(0, n)
            .map(function (k) { return { tag: k, count: obj[k] }; });
    };

    // 找出"人像偏好标签"：在人像中占比远高于全局的标签
    var faceOverRep = [];
    if (total > 0) {
        var faceTotal = s.hasFaceCount;
        var nonFaceTotal = total - faceTotal;
        Object.keys(globalTags).forEach(function (k) {
            var fc = faceTags[k] || 0;
            var nfc = nonFaceTags[k] || 0;
            if (fc >= 3 && faceTotal > 0 && nonFaceTotal > 0) {
                var faceRatio = fc / faceTotal;
                var nonFaceRatio = nfc / nonFaceTotal;
                if (nonFaceRatio > 0) {
                    var ratio = faceRatio / nonFaceRatio;
                    if (ratio > 1.5) {
                        faceOverRep.push({
                            tag: k,
                            faceCount: fc,
                            nonFaceCount: nfc,
                            overRepRatio: Math.round(ratio * 100) / 100
                        });
                    }
                }
            }
        });
        faceOverRep.sort(function (a, b) { return b.overRepRatio - a.overRepRatio; });
    }

    return {
        totalMedia: total,
        faceCount: s.hasFaceCount,
        facePct: total > 0 ? Math.round(s.hasFaceCount / total * 1000) / 10 : 0,
        topFaceTags: sortTop(faceTags, 5),
        topNonFaceTags: sortTop(nonFaceTags, 5),
        faceOverRepresentedTags: faceOverRep.slice(0, 5),
        insight: faceOverRep.length > 0
            ? "人像照片中 '" + faceOverRep[0].tag + "' 出现频率是其他照片的 " + faceOverRep[0].overRepRatio + " 倍"
            : "人像与非人像照片的标签分布相近"
    };
})();
