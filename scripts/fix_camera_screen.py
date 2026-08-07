import re

with open('androidApp/src/main/java/com/picme/features/camera/CameraScreen.kt', 'r') as f:
    lines = f.readlines()

# Find line numbers for the test command block
start_line = None
end_line = None
for i, line in enumerate(lines):
    if 'RD 测试命令收集器' in line:
        start_line = i
    if start_line is not None and 'val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }' in line:
        end_line = i
        break

print(f"Test command block: lines {start_line+1} to {end_line+1}")

# Find line numbers for the test state snapshot block at the end
state_start = None
state_end = None
for i, line in enumerate(lines):
    if 'RD 定期更新测试状态快照' in line:
        state_start = i
    # Find the CameraTestStateSnapshot closing
    if state_start is not None and i > state_start + 20:
        # Look for the pattern that ends the state snapshot block
        if 'isAnyPanelOpen = isAnyPanelOpen' in line:
            # Find the closing braces after this
            for j in range(i+1, min(i+10, len(lines))):
                if lines[j].strip() == ')' and j+1 < len(lines) and lines[j+1].strip() == '}':
                    state_end = j + 2
                    break
            if state_end:
                break

print(f"State snapshot block: lines {state_start+1} to {state_end+1}")

# Now construct the new file
new_lines = []

# Add everything before test command block
new_lines.extend(lines[:start_line])

# Add the cameraProviderFuture line (skip test blocks)
new_lines.append('    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }\n')

# Add everything from after test command block to before state snapshot block
new_lines.extend(lines[end_line+1:state_start])

# Add everything after state snapshot block
new_lines.extend(lines[state_end:])

with open('androidApp/src/main/java/com/picme/features/camera/CameraScreen.kt', 'w') as f:
    f.writelines(new_lines)

print("File updated successfully!")
