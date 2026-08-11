import SwiftUI

/// MARKUP tab 绘制覆盖层（editor.yaml §11）。
/// 在预览图 Fit 矩形上接管手势：DOODLE/MOSAIC 拖拽成笔，TEXT 点按弹输入框。
/// 渲染 in-progress 笔画 + pending（已提交但未烘焙到 preview 的动作），避免烘焙空窗闪烁。
/// 坐标归一化（0..1）相对处理后图片，与 RecipeApplier.markup 烘焙坐标系一致。
struct MarkupDrawingCanvas: View {
    let bitmapRatio: CGFloat            // 图片 w/h
    @ObservedObject var toolState: MarkupToolState
    let pendingActions: [MarkupAction]  // 已提交未烘焙
    let onCommit: (MarkupAction) -> Void
    let onTextTap: (NormPoint) -> Void

    @State private var currentPoints: [NormPoint] = []

    var body: some View {
        GeometryReader { geo in
            let fit = fitRect(in: geo.size)
            // ViewBuilder 分支避免不同手势 Value 类型的三元歧义
            if toolState.tool == .text {
                strokesLayer(in: fit).contentShape(Rectangle())
                    .gesture(tapGesture(in: fit))
            } else {
                strokesLayer(in: fit).contentShape(Rectangle())
                    .gesture(dragGesture(in: fit))
            }
        }
    }

    @ViewBuilder
    private func strokesLayer(in fit: CGRect) -> some View {
        ZStack {
            Color.clear
            ForEach(pendingActions, id: \.id) { a in strokeView(a, in: fit) }
            if !currentPoints.isEmpty { pendingStrokeView(in: fit) }
        }
    }

    private func fitRect(in size: CGSize) -> CGRect {
        let viewRatio = size.width / max(1, size.height)
        let (w, h): (CGFloat, CGFloat)
        if bitmapRatio > viewRatio {
            w = size.width
            h = size.width / max(0.001, bitmapRatio)
        } else {
            h = size.height
            w = size.height * bitmapRatio
        }
        return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
    }

    private func dragGesture(in fit: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { d in
                let p = normalize(d.location, in: fit)
                if let last = currentPoints.last, abs(p.x - last.x) + abs(p.y - last.y) < 0.003 { return }
                currentPoints.append(p)
            }
            .onEnded { _ in
                guard !currentPoints.isEmpty else { return }
                let pts = currentPoints
                currentPoints = []
                switch toolState.tool {
                case .doodle:
                    onCommit(.doodle(id: UUID().uuidString, points: pts,
                                     color: toolState.color, strokeWidth: toolState.strokeWidth))
                case .mosaic:
                    onCommit(.mosaic(id: UUID().uuidString, points: pts,
                                     strokeWidth: toolState.strokeWidth, mode: .pixel))
                case .text:
                    break
                }
            }
    }

    private func tapGesture(in fit: CGRect) -> some Gesture {
        SpatialTapGesture()
            .onEnded { value in
                onTextTap(normalize(value.location, in: fit))
            }
    }

    private func normalize(_ p: CGPoint, in fit: CGRect) -> NormPoint {
        NormPoint(x: Float(min(1, max(0, (p.x - fit.minX) / max(1, fit.width)))),
                  y: Float(min(1, max(0, (p.y - fit.minY) / max(1, fit.height)))))
    }

    // MARK: 笔画渲染

    @ViewBuilder
    private func strokeView(_ action: MarkupAction, in fit: CGRect) -> some View {
        switch action {
        case .doodle(_, let points, let color, let strokeWidth):
            Path { path in addPoints(&path,points, in: fit) }
                .stroke(Color(uiColor: UIColor(argb: color)),
                        style: .init(lineWidth: CGFloat(strokeWidth) * fit.width,
                                     lineCap: .round, lineJoin: .round))
        case .mosaic(_, let points, let strokeWidth, _):
            Path { path in addPoints(&path,points, in: fit) }
                .stroke(Color.black.opacity(0.35),
                        style: .init(lineWidth: CGFloat(strokeWidth) * fit.width,
                                     lineCap: .round, lineJoin: .round))
        case .text(_, let text, let position, let color, let size):
            Text(text)
                .font(.system(size: CGFloat(size) * fit.width, weight: .semibold))
                .foregroundStyle(Color(uiColor: UIColor(argb: color)))
                .position(x: fit.minX + CGFloat(position.x) * fit.width,
                          y: fit.minY + CGFloat(position.y) * fit.height)
        }
    }

    private func pendingStrokeView(in fit: CGRect) -> some View {
        let color = toolState.tool == .mosaic
            ? Color.black.opacity(0.35)
            : Color(uiColor: UIColor(argb: toolState.color))
        return Path { path in addPoints(&path,currentPoints, in: fit) }
            .stroke(color,
                    style: .init(lineWidth: CGFloat(toolState.strokeWidth) * fit.width,
                                 lineCap: .round, lineJoin: .round))
    }

    private func addPoints(_ path: inout Path, _ points: [NormPoint], in fit: CGRect) {
        guard let first = points.first else { return }
        path.move(to: CGPoint(x: fit.minX + CGFloat(first.x) * fit.width,
                              y: fit.minY + CGFloat(first.y) * fit.height))
        for pt in points.dropFirst() {
            path.addLine(to: CGPoint(x: fit.minX + CGFloat(pt.x) * fit.width,
                                     y: fit.minY + CGFloat(pt.y) * fit.height))
        }
    }
}
