import SwiftUI

struct ThumbnailView: View {
    let localIdentifier: String
    let size: CGSize
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color.gray.opacity(0.2)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .task {
            image = await ThumbnailLoader.shared.thumbnail(for: localIdentifier, size: size)
        }
        .accessibilityIdentifier("thumb_\(localIdentifier)")
    }
}
