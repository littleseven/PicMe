#!/usr/bin/env python3
"""
Test Generator - PoLang 单元测试骨架自动生成工具
用途: 基于 Kotlin 源码中的 public 类/方法，自动生成 mockk 测试骨架
调用: python3 scripts/test-generator.py <source_file> [options]

Options:
    --output <path>     输出测试文件路径（默认自动推断）
    --class <name>      只生成指定类的测试
    --mock-deps         自动推断并 mock 依赖项
    --template <name>   测试模板: standard|viewmodel|repository|usecase

示例:
    python3 scripts/test-generator.py androidApp/src/main/java/com/picme/features/gallery/MediaViewModel.kt
    python3 scripts/test-generator.py engines/beauty-engine/src/main/java/com/picme/beauty/api/BeautySettings.kt --mock-deps
"""

import sys
import argparse
import re
from pathlib import Path


# 测试模板
TEMPLATES = {
    "standard": """package {package}

import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Auto-generated test for {class_name}
 * TODO: Replace placeholder tests with actual assertions
 */
class {test_class_name} {{

    private lateinit var subject: {class_name}
{mock_declarations}

    @Before
    fun setup() {{
{mock_setups}
        subject = {class_name}({constructor_args})
    }}

{test_methods}
}}
""",

    "viewmodel": """package {package}

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Auto-generated test for {class_name}
 * TODO: Replace placeholder tests with actual assertions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class {test_class_name} {{

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: {class_name}
{mock_declarations}

    @Before
    fun setup() {{
{mock_setups}
        viewModel = {class_name}({constructor_args})
    }}

{test_methods}
}}

@ExperimentalCoroutinesApi
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {{
    override fun starting(description: Description) {{
        Dispatchers.setMain(testDispatcher)
    }}

    override fun finished(description: Description) {{
        Dispatchers.resetMain()
    }}
}}
""",
}


def parse_kotlin_file(file_path: str) -> dict:
    """解析 Kotlin 文件，提取类、构造函数、public 方法"""
    with open(file_path, 'r') as f:
        content = f.read()
    
    # 提取 package
    package_match = re.search(r'package\s+([\w.]+)', content)
    package = package_match.group(1) if package_match else ""
    
    # 提取类定义（支持 class, data class, object）
    class_pattern = r'(data\s+)?class\s+(\w+)\s*(?:\([^)]*\))?\s*(?::\s*[\w<>,\s]+)?\s*\{'
    classes = []
    
    for match in re.finditer(class_pattern, content):
        is_data = match.group(1) is not None
        class_name = match.group(2)
        
        # 找到类体的范围（简单实现：匹配大括号）
        class_start = match.end() - 1
        brace_count = 0
        class_end = class_start
        for i, char in enumerate(content[class_start:], class_start):
            if char == '{':
                brace_count += 1
            elif char == '}':
                brace_count -= 1
                if brace_count == 0:
                    class_end = i
                    break
        
        class_body = content[class_start:class_end+1]
        
        # 提取构造函数参数
        ctor_match = re.search(r'class\s+' + class_name + r'\s*\((.*?)\)', content)
        ctor_params = []
        if ctor_match:
            params_str = ctor_match.group(1)
            # 解析参数
            for param_match in re.finditer(r'(?:val\s+|var\s+)?(\w+)\s*:\s*([\w<>.\s?]+)', params_str):
                param_name = param_match.group(1)
                param_type = param_match.group(2).strip()
                ctor_params.append({"name": param_name, "type": param_type})
        
        # 提取 public 方法
        methods = []
        # 匹配 fun 定义（简单匹配，不考虑嵌套）
        for method_match in re.finditer(r'fun\s+(\w+)\s*\((.*?)\)(?:\s*:\s*([\w<>.\s?]+))?', class_body):
            method_name = method_match.group(1)
            # 跳过构造函数、getter、特殊方法
            if method_name in (class_name, "equals", "hashCode", "toString", "copy"):
                continue
            
            params_str = method_match.group(2)
            return_type = method_match.group(3) if method_match.group(3) else "Unit"
            
            params = []
            if params_str.strip():
                for p in params_str.split(','):
                    p = p.strip()
                    if ':' in p:
                        parts = p.split(':')
                        params.append({
                            "name": parts[0].strip(),
                            "type": parts[1].strip()
                        })
            
            methods.append({
                "name": method_name,
                "params": params,
                "return_type": return_type.strip(),
            })
        
        classes.append({
            "name": class_name,
            "is_data": is_data,
            "constructor_params": ctor_params,
            "methods": methods,
        })
    
    return {
        "package": package,
        "classes": classes,
    }


def generate_mock_declarations(params: list, template_type: str) -> tuple:
    """生成 mock 声明和 setup 代码"""
    declarations = []
    setups = []
    args = []
    
    for param in params:
        param_name = param["name"]
        param_type = param["type"]
        
        # 判断是否可 mock（接口或类）
        is_mockable = not any(t in param_type for t in ["Int", "Float", "Double", "Boolean", "String", "Long", "Byte", "Char", "List<", "Map<", "Set<", "Array<"])
        
        if is_mockable:
            declarations.append(f"    @MockK\n    private lateinit var {param_name}: {param_type}")
            setups.append(f"        {param_name} = mockk(relaxed = true)")
            args.append(param_name)
        else:
            # 简单类型提供默认值
            if "String" in param_type:
                args.append('""')
            elif "Int" in param_type:
                args.append("0")
            elif "Float" in param_type:
                args.append("0f")
            elif "Boolean" in param_type:
                args.append("false")
            elif "Long" in param_type:
                args.append("0L")
            else:
                args.append("/* TODO: provide $param_type */")
    
    return "\n".join(declarations), "\n".join(setups), ", ".join(args)


def generate_test_methods(methods: list, class_name: str) -> str:
    """生成测试方法骨架"""
    tests = []
    
    for method in methods:
        method_name = method["name"]
        params = method["params"]
        return_type = method["return_type"]
        
        # 生成参数列表
        param_args = []
        for p in params:
            p_type = p["type"]
            if "String" in p_type:
                param_args.append('"test"')
            elif "Int" in p_type:
                param_args.append("1")
            elif "Float" in p_type:
                param_args.append("1.0f")
            elif "Boolean" in p_type:
                param_args.append("true")
            else:
                param_args.append("/* TODO */")
        
        param_str = ", ".join(param_args)
        
        # 根据返回类型生成断言模板
        if return_type == "Unit":
            assertion = "// 验证副作用（如状态变更、方法调用）"
        elif "Boolean" in return_type:
            assertion = f"assertTrue(result)  // 或 assertFalse(result)"
        elif "Int" in return_type or "Float" in return_type or "Long" in return_type:
            assertion = f"assertEquals(EXPECTED_VALUE, result)"
        elif "String" in return_type:
            assertion = f'assertEquals("expected", result)'
        elif "List" in return_type:
            assertion = f"assertEquals(EXPECTED_SIZE, result.size)"
        else:
            assertion = f"assertNotNull(result)"
        
        test_method = f"""    @Test
    fun `{method_name} should return expected result`() {{
        // Given
        {chr(10).join([f"val {p['name']} = {param_args[i] if i < len(param_args) else '/* TODO */'}" for i, p in enumerate(params)])}
        
        // When
        val result = subject.{method_name}({param_str})
        
        // Then
        {assertion}
    }}
"""
        tests.append(test_method)
    
    return "\n".join(tests) if tests else "    // TODO: Add tests for public methods\n"


def generate_test_file(parsed: dict, class_info: dict, template_type: str = "standard") -> str:
    """生成完整测试文件"""
    template = TEMPLATES.get(template_type, TEMPLATES["standard"])
    
    package = parsed["package"]
    class_name = class_info["name"]
    test_class_name = f"{class_name}Test"
    
    mock_decls, mock_setups, ctor_args = generate_mock_declarations(
        class_info["constructor_params"], template_type
    )
    
    test_methods = generate_test_methods(class_info["methods"], class_name)
    
    return template.format(
        package=package,
        class_name=class_name,
        test_class_name=test_class_name,
        mock_declarations=mock_decls,
        mock_setups=mock_setups,
        constructor_args=ctor_args,
        test_methods=test_methods,
    )


def infer_test_path(source_path: str, package: str) -> str:
    """推断测试文件输出路径"""
    path = Path(source_path)
    
    # 将 main/java 替换为 test/java
    parts = list(path.parts)
    if "main" in parts:
        main_idx = parts.index("main")
        parts[main_idx] = "test"
    
    # 修改文件名
    test_name = path.stem + "Test.kt"
    parts = list(parts[:-1]) + [test_name]
    
    return str(Path(*parts))


def infer_template_type(class_info: dict) -> str:
    """根据类名推断模板类型"""
    name = class_info["name"]
    if "ViewModel" in name:
        return "viewmodel"
    # 可以扩展更多类型判断
    return "standard"


def main():
    parser = argparse.ArgumentParser(description="PoLang Test Generator")
    parser.add_argument("source", help="Kotlin 源码文件路径")
    parser.add_argument("--output", help="输出测试文件路径")
    parser.add_argument("--class", dest="class_name", help="只生成指定类的测试")
    parser.add_argument("--mock-deps", action="store_true", help="自动 mock 依赖")
    parser.add_argument("--template", choices=["standard", "viewmodel"], help="测试模板")
    parser.add_argument("--dry-run", action="store_true", help="只输出不写入文件")
    
    args = parser.parse_args()
    
    if not Path(args.source).exists():
        print(f"❌ 源文件不存在: {args.source}", file=sys.stderr)
        sys.exit(1)
    
    # 解析源码
    parsed = parse_kotlin_file(args.source)
    
    if not parsed["classes"]:
        print(f"⚠️ 未在 {args.source} 中找到类定义")
        sys.exit(0)
    
    # 生成测试
    generated = []
    for class_info in parsed["classes"]:
        if args.class_name and class_info["name"] != args.class_name:
            continue
        
        template_type = args.template or infer_template_type(class_info)
        test_code = generate_test_file(parsed, class_info, template_type)
        
        # 推断输出路径
        if args.output:
            output_path = args.output
        else:
            output_path = infer_test_path(args.source, parsed["package"])
        
        generated.append({
            "class_name": class_info["name"],
            "test_class_name": f"{class_info['name']}Test",
            "output_path": output_path,
            "code": test_code,
            "method_count": len(class_info["methods"]),
        })
    
    # 输出结果
    print("=" * 60)
    print("🧪 PoLang Test Generator")
    print("=" * 60)
    print(f"源文件: {args.source}")
    print(f"解析到 {len(parsed['classes'])} 个类")
    print(f"将生成 {len(generated)} 个测试文件")
    print("")
    
    for gen in generated:
        print(f"📄 {gen['test_class_name']} ({gen['method_count']} 个方法)")
        print(f"   输出: {gen['output_path']}")
        
        if args.dry_run:
            print("")
            print("-" * 40)
            print(gen["code"])
            print("-" * 40)
        else:
            # 创建目录
            Path(gen["output_path"]).parent.mkdir(parents=True, exist_ok=True)
            
            # 检查文件是否已存在
            if Path(gen["output_path"]).exists():
                print(f"   ⚠️ 文件已存在，跳过（使用 --dry-run 查看内容）")
            else:
                with open(gen["output_path"], 'w') as f:
                    f.write(gen["code"])
                print(f"   ✅ 已生成")
        
        print("")
    
    print("=" * 60)
    print("提示:")
    print("  1. 生成的测试包含 TODO 标记，请替换为实际断言")
    print("  2. 使用 --dry-run 预览生成的代码")
    print("  3. mockk 依赖需已在 build.gradle 中配置")
    print("=" * 60)


if __name__ == "__main__":
    main()
