# PoLang 相机测试命令完整参考

## 命令格式

```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "<action_name>" [额外参数]
```

## 所有可用命令

### 拍照
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

### 切换摄像头
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "flip_camera"
```

### 切换拍摄模式
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_mode" --es mode "photo"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_mode" --es mode "video"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_mode" --es mode "pro"
```

### 设置美颜参数
参数范围: 0-100
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND \
    --es action "set_beauty" \
    --ei smooth 80 \
    --ei whiten 60 \
    --ei slim_face 50 \
    --ei big_eye 30
```

### 设置滤镜
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "none"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "leica_classic"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "leica_vibrant"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "leica_bw"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "film_gold"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "film_fuji"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "vintage"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "cool"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "warm"
```

### 设置风格滤镜
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "none"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "toon"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "sketch"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "posterize"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "emboss"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "crosshatch"
```

### 切换场景模式
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_scene" --es scene "none"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_scene" --es scene "night"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_scene" --es scene "moon"
```

### 切换画幅比例
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_ratio" --es ratio "4_3"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_ratio" --es ratio "16_9"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_ratio" --es ratio "full"
```

### 设置曝光补偿
范围: -2 ~ 2
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_exposure" --ei exposure -2
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_exposure" --ei exposure 0
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_exposure" --ei exposure 2
```

### 设置缩放
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 1.0
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 2.0
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 3.0
```

### 切换面板
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_beauty"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_filter"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_settings"
```

### 获取当前状态
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "get_state"
```

## 参数类型说明

| 参数类型 | adb 标志 | 示例 |
|----------|----------|------|
| 字符串 | `--es` | `--es mode "video"` |
| 整数 | `--ei` | `--ei smooth 80` |
| 浮点数 | `--ef` | `--ef zoom 2.0` |

## 自动化测试场景示例

### 场景1: 后置摄像头美颜拍照
```bash
#!/bin/bash
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3
# 切换到后置（如果需要）
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "flip_camera"
sleep 1
# 设置美颜
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND \
    --es action "set_beauty" --ei smooth 80 --ei whiten 60
sleep 0.5
# 拍照
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

### 场景2: 夜景模式拍照
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_scene" --es scene "night"
sleep 0.5
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

### 场景3: 批量滤镜测试
```bash
#!/bin/bash
filters=("none" "leica_classic" "leica_vibrant" "leica_bw" "film_gold")
for filter in "${filters[@]}"; do
    adb shell am broadcast -a com.mamba.picme.TEST_COMMAND \
        --es action "set_filter" --es filter "$filter"
    sleep 0.5
    adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
    sleep 1
done
```
