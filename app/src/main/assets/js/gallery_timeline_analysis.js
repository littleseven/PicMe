// 相册时间线趋势分析（包内 JS，只读，不触网）
// 按月分桶统计拍照数量，计算环比趋势，找出高峰月份。
// 由 Debug 页运行 / 远程 LLM 生成参考。
(function () {
    var timeline = bridge.call("gallery.timeline");
    var summary = bridge.call("gallery.summary");
    var total = summary.totalMedia;

    // 提取月份数据并排序
    var keys = Object.keys(timeline).sort();
    var months = keys.map(function (k) {
        return { bucket: parseInt(k), count: timeline[k] };
    });

    // 计算环比变化
    var changes = [];
    for (var i = 1; i < months.length; i++) {
        var prev = months[i - 1].count;
        var curr = months[i].count;
        var change = prev > 0 ? Math.round((curr / prev - 1) * 1000) / 10 : 0;
        changes.push({ bucket: months[i].bucket, count: curr, changePct: change });
    }

    // 找高峰月
    var peak = months.reduce(function (a, b) {
        return a.count >= b.count ? a : b;
    }, { bucket: 0, count: 0 });

    // 计算月均
    var avgPerMonth = months.length > 0
        ? Math.round(total / months.length * 10) / 10
        : 0;

    // 判断趋势：最近3个月 vs 之前
    var recentIdx = Math.max(0, months.length - 3);
    var recentSum = months.slice(recentIdx).reduce(function (s, m) { return s + m.count; }, 0);
    var priorSum = months.slice(0, recentIdx).reduce(function (s, m) { return s + m.count; }, 0);
    var recentAvg = months.length - recentIdx > 0 ? recentSum / (months.length - recentIdx) : 0;
    var priorAvg = recentIdx > 0 ? priorSum / recentIdx : 0;
    var trend = priorAvg > 0
        ? (recentAvg / priorAvg > 1.1 ? "上升" : recentAvg / priorAvg < 0.9 ? "下降" : "平稳")
        : "数据不足";

    return {
        totalMedia: total,
        monthCount: months.length,
        avgPerMonth: avgPerMonth,
        peakMonth: peak.bucket,
        peakCount: peak.count,
        trend: trend,
        monthlyData: months.map(function (m) {
            return { month: m.bucket, count: m.count };
        })
    };
})();
