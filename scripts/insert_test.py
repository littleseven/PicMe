with open('androidApp/src/main/java/com/picme/features/camera/CameraScreen.kt', 'r') as f:
    lines = f.readlines()

# Find insertion point: after "var renderPerfStats" block
insert_line = None
for i, line in enumerate(lines):
    if 'var renderPerfStats by remember {' in line:
        for j in range(i+1, min(i+5, len(lines))):
            if lines[j].strip() == '}':
                insert_line = j + 1
                break
        break

print(f"Inserting after line {insert_line}")

# Read test code from file
with open('test_code.txt', 'r') as f:
    test_code = f.read()

new_lines = lines[:insert_line]
new_lines.append(test_code)
new_lines.extend(lines[insert_line:])

with open('androidApp/src/main/java/com/picme/features/camera/CameraScreen.kt', 'w') as f:
    f.writelines(new_lines)

print("Done!")
