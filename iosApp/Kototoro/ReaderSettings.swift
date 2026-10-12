import Foundation
import Combine
import SwiftUI

struct ReaderPreferences: Codable, Equatable {
    var mode = 0
    var fit = 0
    var doublePage = false
    var cover = true
    var splitWidePages = false
    var cropMargins = false
    var animation = 0
    var background = 0
    var inverted = false
    var grayscale = false
    var brightness = 0.0
    var contrast = 1.0
    var bookEffect = false
    var bookmarkButton = true
    var bottomPanels = false
    var autoSpeed = 0.24
    var floatingActions = ["bookmark"]
    var tapZones = true
    var reverseTaps = false

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        mode = try container.decodeIfPresent(Int.self, forKey: .mode) ?? 0
        fit = try container.decodeIfPresent(Int.self, forKey: .fit) ?? 0
        doublePage = try container.decodeIfPresent(Bool.self, forKey: .doublePage) ?? false
        cover = try container.decodeIfPresent(Bool.self, forKey: .cover) ?? true
        splitWidePages = try container.decodeIfPresent(Bool.self, forKey: .splitWidePages) ?? false
        cropMargins = try container.decodeIfPresent(Bool.self, forKey: .cropMargins) ?? false
        animation = try container.decodeIfPresent(Int.self, forKey: .animation) ?? 0
        background = try container.decodeIfPresent(Int.self, forKey: .background) ?? 0
        inverted = try container.decodeIfPresent(Bool.self, forKey: .inverted) ?? false
        grayscale = try container.decodeIfPresent(Bool.self, forKey: .grayscale) ?? false
        brightness = try container.decodeIfPresent(Double.self, forKey: .brightness) ?? 0.0
        contrast = try container.decodeIfPresent(Double.self, forKey: .contrast) ?? 1.0
        bookEffect = try container.decodeIfPresent(Bool.self, forKey: .bookEffect) ?? false
        bookmarkButton = try container.decodeIfPresent(Bool.self, forKey: .bookmarkButton) ?? true
        bottomPanels = try container.decodeIfPresent(Bool.self, forKey: .bottomPanels) ?? false
        autoSpeed = try container.decodeIfPresent(Double.self, forKey: .autoSpeed) ?? 0.24
        floatingActions = try container.decodeIfPresent([String].self, forKey: .floatingActions) ?? ["bookmark"]
        tapZones = try container.decodeIfPresent(Bool.self, forKey: .tapZones) ?? true
        reverseTaps = try container.decodeIfPresent(Bool.self, forKey: .reverseTaps) ?? false
    }
}

/// Work-specific settings override a user-controlled global default.
@MainActor
final class ReaderSettings: ObservableObject {
    @Published var value: ReaderPreferences { didSet { save() } }
    private let key: String
    init(contentID: String) {
        key = "reader.work.\(contentID)"
        let data = UserDefaults.standard.data(forKey: key) ?? UserDefaults.standard.data(forKey: "reader.defaults")
        value = data.flatMap { try? JSONDecoder().decode(ReaderPreferences.self, from: $0) } ?? ReaderPreferences()
    }
    private func save() {
        if let data = try? JSONEncoder().encode(value) { UserDefaults.standard.set(data, forKey: key) }
    }
    func saveAsDefault() {
        if let data = try? JSONEncoder().encode(value) { UserDefaults.standard.set(data, forKey: "reader.defaults") }
    }
    var background: Color { value.background == 1 ? .white : value.background == 2 ? Color(red: 0.93, green: 0.88, blue: 0.77) : .black }
}
