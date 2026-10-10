import SwiftUI

@main
struct KototoroApp: App {
    @StateObject private var library = LibraryModel()

    var body: some Scene {
        WindowGroup {
            ContentView(library: library)
                .task { await library.load() }
        }
    }
}
