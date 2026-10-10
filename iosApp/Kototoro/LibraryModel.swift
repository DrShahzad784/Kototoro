import Foundation
import Combine
import KototoroShared

@MainActor
final class LibraryModel: ObservableObject {
    @Published private(set) var categories: [IosCategory] = []
    @Published private(set) var isLoading = true
    @Published private(set) var error: String?
    @Published private(set) var schemaVersion: Int32?
    private var library: IosLibrary?

    func load() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            if library == nil {
                let directory = try FileManager.default.url(
                    for: .applicationSupportDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                ).appendingPathComponent("Kototoro", isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                library = try IosLibrary(databasePath: directory.appendingPathComponent("library.db").path)
            }
            guard let library else { return }
            categories = try await library.categories()
            schemaVersion = library.schemaVersion
        } catch {
            self.error = error.localizedDescription
        }
    }

    func addCategory(_ title: String) async -> Bool {
        guard let library, !isLoading else { return false }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            try await library.addCategory(title: title)
            categories = try await library.categories()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }
}
