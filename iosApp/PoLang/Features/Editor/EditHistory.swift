import Foundation

// MARK: - EditHistory
// 非破坏性编辑的撤销/重做状态栈。行为对齐 androidApp features/editor/EditHistory.kt：
//   - push() 丢弃当前 index 之后的 redo 分支，再追加
//   - undo()/redo() 仅移动 index，不修改历史列表
//   - maxSize 溢出时丢最早一条并校正 index
// contracts.md B5 / editor.yaml §2(历史栈) 的 iOS 实现。

final class EditHistory {
    private let maxSize: Int
    private var stack: [EditRecipe] = []
    private var index: Int = -1

    init(maxSize: Int = 30) {
        self.maxSize = maxSize
    }

    var canUndo: Bool { index > 0 }

    var canRedo: Bool { index < stack.count - 1 }

    /// 当前生效的配方；栈空时为 nil。
    func current() -> EditRecipe? {
        guard stack.indices.contains(index) else { return nil }
        return stack[index]
    }

    /// 追加新配方：先丢弃 redo 分支（index 之后），再入栈。
    func push(_ recipe: EditRecipe) {
        if index < stack.count - 1 {
            stack.removeSubrange((index + 1)..<stack.count)
        }
        stack.append(recipe)
        if stack.count > maxSize {
            stack.removeFirst()
            if index > 0 { index -= 1 }
        }
        index = stack.count - 1
    }

    @discardableResult
    func undo() -> EditRecipe? {
        guard canUndo else { return nil }
        index -= 1
        return stack[index]
    }

    @discardableResult
    func redo() -> EditRecipe? {
        guard canRedo else { return nil }
        index += 1
        return stack[index]
    }

    /// 清空并以 recipe 为唯一基线。
    func reset(_ recipe: EditRecipe) {
        stack.removeAll()
        index = -1
        push(recipe)
    }
}
