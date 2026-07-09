from datetime import datetime, date, timedelta
from collections import Counter
import calendar

# 定义 2026 年中国法定节假日（3月-7月区间）
chinese_holidays = set()
chinese_workdays = set()  # 补班日（周末上班）

# 清明节 4/4(六)-4/6(一)
for d in range(4, 7):
    chinese_holidays.add(date(2026, 4, d))
# 劳动节 5/1(五)-5/5(二)
for d in range(1, 6):
    chinese_holidays.add(date(2026, 5, d))
# 端午节 6/25(四)-6/27(六)
for d in range(25, 28):
    chinese_holidays.add(date(2026, 6, d))

# 补班日：4月26日(周日)补劳动节，6月21日(周日)补端午节
chinese_workdays.add(date(2026, 4, 26))
chinese_workdays.add(date(2026, 6, 21))

def is_holiday(d):
    """判断是否是周末或法定节假日"""
    if d in chinese_holidays:
        return True
    if d in chinese_workdays:
        return False
    return d.weekday() >= 5

def is_weekend(d):
    return d.weekday() >= 5

# 读取所有提交时间
commits = []
with open('/tmp/commit_times.txt', 'r') as f:
    for line in f:
        line = line.strip()
        if line:
            commits.append(line)

total = len(commits)
print(f"总提交数: {total}")
print(f"时间范围: {commits[-1][:10]} ~ {commits[0][:10]}")
print()

# 分类统计
cat_holiday = 0      # 非工作日（周末+法定节假日）
cat_before10 = 0     # 工作日早10点前
cat_after21 = 0      # 工作日晚9点后
cat_normal = 0       # 工作日正常时段 10:00-19:00

for c in commits:
    dt = datetime.strptime(c, "%Y-%m-%d %H:%M:%S %z")
    d = dt.date()
    hour = dt.hour
    is_hol = is_holiday(d)
    
    if is_hol:
        cat_holiday += 1
    elif hour < 10:
        cat_before10 += 1
    elif hour >= 19:
        cat_after21 += 1
    else:
        cat_normal += 1

print("=" * 60)
print(" 提交时间分布统计")
print("=" * 60)
print()

print(f"总提交数: {total}")
print()

# 类别1: 非工作日
print(f" 非工作日提交（周末 + 法定节假日）:")
print(f"   提交数: {cat_holiday} / {total}")
print(f"   占比: {cat_holiday/total*100:.1f}%")
print()

# 非工作日中的周末 vs 法定节假日细分
cat_weekend = 0
cat_cn_holiday = 0
for c in commits:
    dt = datetime.strptime(c, "%Y-%m-%d %H:%M:%S %z")
    d = dt.date()
    if d in chinese_holidays:
        cat_cn_holiday += 1
    elif is_weekend(d):
        cat_weekend += 1

print(f"   其中: 周末提交 {cat_weekend} 个, 法定节假日提交 {cat_cn_holiday} 个")
print(f"   周末占比: {cat_weekend/total*100:.1f}%, 法定节假日占比: {cat_cn_holiday/total*100:.1f}%")
print()

# 类别2: 工作日早10点前
print(f" 工作日早 10:00 前提交:")
print(f"   提交数: {cat_before10} / {total}")
print(f"   占比: {cat_before10/total*100:.1f}%")
print()

# 类别3: 工作日晚9点后
print(f" 工作日晚 19:00 后提交:")
print(f"   提交数: {cat_after21} / {total}")
print(f"   占比: {cat_after21/total*100:.1f}%")
print()

# 类别4: 工作日正常时段
print(f" 工作日正常时段 (10:00-19:00) 提交:")
print(f"   提交数: {cat_normal} / {total}")
print(f"   占比: {cat_normal/total*100:.1f}%")
print()

# 合并统计
non_normal = cat_holiday + cat_before10 + cat_after21
print("=" * 60)
print(f" 非正常时段提交合计: {non_normal} / {total} = {non_normal/total*100:.1f}%")
print(f" 正常时段提交 (工作日10:00-19:00): {cat_normal} / {total} = {cat_normal/total*100:.1f}%")
print("=" * 60)

# 按小时分布
hour_counter = Counter()
for c in commits:
    dt = datetime.strptime(c, "%Y-%m-%d %H:%M:%S %z")
    hour_counter[dt.hour] += 1

print()
print("按小时分布:")
print("-" * 50)
max_count = max(hour_counter.values()) if hour_counter else 1
for h in range(24):
    count = hour_counter.get(h, 0)
    bar_len = count * 50 // max_count
    bar = "█" * bar_len
    pct = count / total * 100
    print(f"  {h:02d}:00  {count:4d}  {pct:5.1f}%  {bar}")

print()
print("按星期分布:")
weekday_names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
weekday_counter = Counter()
for c in commits:
    dt = datetime.strptime(c, "%Y-%m-%d %H:%M:%S %z")
    weekday_counter[dt.weekday()] += 1

print("-" * 50)
for wd in range(7):
    count = weekday_counter.get(wd, 0)
    bar_len = count * 50 // max(weekday_counter.values()) if max(weekday_counter.values()) > 0 else 0
    bar = "█" * bar_len
    pct = count / total * 100
    print(f"  {weekday_names[wd]}  {count:4d}  {pct:5.1f}%  {bar}")
